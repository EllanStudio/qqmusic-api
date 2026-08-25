package com.coloryr.allmusic.server.core.api.qqmusic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMusicLoginTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearConfigOverride() {
        System.clearProperty("qqmusic.config");
    }

    @Test
    void parsesSuccessfulPtloginCallback() throws Exception {
        String body = "ptuiCB('0','0','https://example.test/check?uin=123456\\x26ptsigx=sig-value',"
                + "'0','login ok','nickname');";

        QQMusicLogin.QrStatus status = QQMusicLogin.parseCallback(body);

        assertEquals("0", status.code);
        assertEquals("123456", QQMusicSupport.queryParameter(status.callbackUrl, "uin"));
        assertEquals("sig-value", QQMusicSupport.queryParameter(status.callbackUrl, "ptsigx"));
    }

    @Test
    void rejectsUnexpectedPtloginResponse() {
        assertThrows(IOException.class, () -> QQMusicLogin.parseCallback("not a callback"));
    }

    @Test
    void ensureFreshQueuesRefreshWithoutBlockingCaller() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        Path configFile = temporaryDirectory.resolve("qqmusic.json");
        String json = "{\"credential\":{"
                + "\"openid\":\"open\",\"refresh_token\":\"refresh-token\","
                + "\"access_token\":\"access-token\",\"musicid\":\"123456\","
                + "\"musickey\":\"Q_H_L_old\",\"str_musicid\":\"123456\","
                + "\"refresh_key\":\"refresh-key\",\"musickeyCreateTime\":"
                + (now - QQMusicCredential.DEFAULT_KEY_EXPIRES_IN)
                + ",\"keyExpiresIn\":" + QQMusicCredential.DEFAULT_KEY_EXPIRES_IN
                + ",\"loginType\":2},\"qrLogin\":false,\"autoRefresh\":true,"
                + "\"qualities\":\"m4a\",\"searchLimit\":20,\"timeoutSeconds\":20}";
        Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
        System.setProperty("qqmusic.config", configFile.toString());

        QQMusicConfig config = QQMusicConfig.load();
        QQMusicClient client = new QQMusicClient(config);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicReference<String> refreshThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "qqmusic-refresh-test-worker");
                thread.setDaemon(true);
                return thread;
            }
        });
        QQMusicLogin.CredentialRefresher refresher = new QQMusicLogin.CredentialRefresher() {
            @Override
            public QQMusicCredential refresh(QQMusicCredential current) throws IOException {
                refreshThread.set(Thread.currentThread().getName());
                refreshStarted.countDown();
                try {
                    if (!releaseRefresh.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test refresh was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test refresh interrupted", e);
                }
                return new QQMusicCredential(
                        current.openId, current.refreshToken, current.accessToken, current.expiredAt,
                        current.musicId, "Q_H_L_new", current.unionId, current.stringMusicId,
                        current.refreshKey, System.currentTimeMillis() / 1000L,
                        QQMusicCredential.DEFAULT_KEY_EXPIRES_IN, current.loginType
                );
            }
        };
        QQMusicLogin login = new QQMusicLogin(config, client, executor, refresher);

        try {
            String callerThread = Thread.currentThread().getName();
            long startedAt = System.nanoTime();
            login.ensureFresh();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(elapsedMillis < 1000L,
                    "ensureFresh must only enqueue work, elapsed=" + elapsedMillis + "ms");
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS));
            assertNotEquals(callerThread, refreshThread.get());
            assertEquals("qqmusic-refresh-test-worker", refreshThread.get());
        } finally {
            releaseRefresh.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
