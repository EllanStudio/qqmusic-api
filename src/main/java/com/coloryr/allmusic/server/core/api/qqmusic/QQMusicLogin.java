package com.coloryr.allmusic.server.core.api.qqmusic;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QQMusicLogin {
    private static final String RELOGIN_TRIGGER = "qqmusic-relogin";
    private static final long TRIGGER_POLL_MILLIS = 2000L;
    private static final String APP_ID = "716027609";
    private static final String DAID = "383";
    private static final String THIRD_APP_ID = "100497308";
    private static final String LOGIN_JUMP = "https://graph.qq.com/oauth2.0/login_jump";
    private static final String LOGIN_REDIRECT = "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/";
    // ptlogin2 requires a xui referer; this bare URL is what the reference
    // implementation (L-1124/QQMusicApi) sends for both ptqrshow and ptqrlogin.
    private static final String XUI_REFERER = "https://xui.ptlogin2.qq.com/";
    private static final String CHECK_SIG_URL = "https://ssl.ptlogin2.graph.qq.com/check_sig";
    private static final String AUTHORIZE_URL = "https://graph.qq.com/oauth2.0/authorize";
    private static final Pattern CALLBACK = Pattern.compile("ptuiCB\\((.*)\\)");
    private static final Pattern CALLBACK_VALUE = Pattern.compile("'((?:\\\\.|[^'])*)'");

    private final QQMusicConfig config;
    private final QQMusicClient client;
    private final QQMusicHttp http;
    private final AtomicBoolean loginBusy = new AtomicBoolean(false);
    private final AtomicBoolean refreshBusy = new AtomicBoolean(false);

    QQMusicLogin(QQMusicConfig config, QQMusicClient client) {
        this.config = config;
        this.client = client;
        this.http = client.http();
    }

    private static final long AUTO_REFRESH_CHECK_MILLIS = 30 * 60 * 1000L; // 30 minutes

    void start() {
        config.reloadIfChanged();
        if (config.autoRefresh() && config.credential().expiresSoon()) {
            startRefresh();
        } else if (!config.credential().isComplete()) {
            startQrLogin("startup", false);
        }
        startReloginWatcher();
        startAutoRefreshDaemon();
    }

    private void startAutoRefreshDaemon() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(AUTO_REFRESH_CHECK_MILLIS);
                        config.reloadIfChanged();
                        if (config.autoRefresh() && config.credential().expiresSoon()) {
                            refreshNow();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable e) {
                        QQMusicSupport.logError("QQ Music auto-refresh daemon failed: " + e.getMessage());
                    }
                }
            }
        }, "AllMusic-QQMusic-AutoRefreshDaemon");
        thread.setDaemon(true);
        thread.start();
    }

    void ensureFresh() {
        config.reloadIfChanged();
        QQMusicCredential credential = config.credential();
        if (config.autoRefresh() && credential.expiresSoon()) {
            refreshNow();
        }
    }

    private void startRefresh() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                refreshNow();
            }
        }, "AllMusic-QQMusic-Refresh");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshNow() {
        if (!refreshBusy.compareAndSet(false, true)) {
            return;
        }
        try {
            config.reloadIfChanged();
            QQMusicCredential current = config.credential();
            if (!current.canRefresh()) {
                QQMusicSupport.logInfo("QQ Music credential needs a new QR login");
                startQrLogin("credential-refresh-unavailable", false);
                return;
            }
            QQMusicCredential refreshed = client.refreshCredential(current);
            config.saveCredential(refreshed);
            QQMusicSupport.logInfo("QQ Music credential refreshed");
        } catch (Exception e) {
            QQMusicSupport.logError("QQ Music credential refresh failed: " + e.getMessage());
            startQrLogin("credential-refresh-failed", false);
        } finally {
            refreshBusy.set(false);
        }
    }

    private void startQrLogin(final String reason, boolean force) {
        if (!config.qrLogin() || (!force && config.credential().isComplete())) {
            return;
        }
        if (!loginBusy.compareAndSet(false, true)) {
            QQMusicSupport.logInfo("QQ Music QR login already in progress");
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    performQrLogin();
                } catch (Exception e) {
                    QQMusicSupport.logError("QQ Music QR login stopped: " + e.getMessage());
                } finally {
                    loginBusy.set(false);
                }
            }
        }, "AllMusic-QQMusic-QRLogin");
        thread.setDaemon(true);
        thread.start();
        QQMusicSupport.logInfo("QQ Music QR login started, reason=" + reason);
    }

    private void performQrLogin() throws Exception {
        QQMusicCredential startingCredential = config.credential();
        QrCode qrCode = requestQrCode();
        writeQrFiles(qrCode.image);
        QQMusicHttp.CookieJar cookies = qrCode.cookies;
        long endAt = System.currentTimeMillis() + config.qrLoginTimeoutSeconds() * 1000L;
        String lastStatus = "";

        while (System.currentTimeMillis() < endAt) {
            config.reloadIfChanged();
            if (!config.qrLogin()) {
                QQMusicSupport.logInfo("QQ Music QR login stopped by config reload");
                return;
            }
            QQMusicCredential activeCredential = config.credential();
            if (activeCredential.isComplete()
                    && !sameCredential(startingCredential, activeCredential)) {
                QQMusicSupport.logInfo("QQ Music QR login stopped because a new credential was hot-loaded");
                return;
            }
            QrStatus status = checkQrCode(qrCode.qrSig, cookies);
            if ("0".equals(status.code)) {
                String authorizationCode = completeQQAuthorization(status.callbackUrl, cookies);
                QQMusicCredential credential = client.exchangeQQCode(authorizationCode);
                config.saveCredential(credential);
                QQMusicSupport.logInfo("QQ Music QR login success, credential=true");
                return;
            }
            if ("65".equals(status.code)) {
                throw new IOException("QR code expired" + messageSuffix(status));
            }
            if ("68".equals(status.code)) {
                throw new IOException("QR login refused" + messageSuffix(status));
            }
            if (!status.code.equals(lastStatus)) {
                if ("67".equals(status.code)) {
                    QQMusicSupport.logInfo("QQ Music QR scanned, confirm login on your phone");
                } else if ("66".equals(status.code)) {
                    QQMusicSupport.logInfo("QQ Music QR waiting for scan");
                } else {
                    QQMusicSupport.logInfo("QQ Music QR status=" + status.code + messageSuffix(status));
                }
                lastStatus = status.code;
            }
            Thread.sleep(config.qrLoginPollSeconds() * 1000L);
        }
        throw new IOException("QR login timeout");
    }

    private QrCode requestQrCode() throws IOException {
        // Mirror the reference implementation (L-1124/QQMusicApi) exactly:
        // no u1 parameter, bare xui referer.
        String url = "https://ssl.ptlogin2.qq.com/ptqrshow"
                + "?appid=" + APP_ID
                + "&e=2&l=M&s=3&d=72&v=4"
                + "&t=" + Math.random()
                + "&daid=" + DAID
                + "&pt_3rd_aid=" + THIRD_APP_ID;
        QQMusicHttp.Response response = http.get(url, QQMusicHttp.headers(
                "Referer", XUI_REFERER
        ));
        if (!response.isSuccess()) {
            throw new IOException("QQ QR request failed, HTTP " + response.status);
        }
        QQMusicHttp.CookieJar cookies = new QQMusicHttp.CookieJar();
        cookies.mergeSetCookies(response.headers);
        String qrSig = cookies.get("qrsig");
        if (QQMusicSupport.isBlank(qrSig)) {
            throw new IOException("QQ QR response did not contain qrsig");
        }
        return new QrCode(qrSig, response.body, cookies);
    }

    private QrStatus checkQrCode(String qrSig, QQMusicHttp.CookieJar cookies) throws IOException {
        String url = "https://ssl.ptlogin2.qq.com/ptqrlogin"
                + "?u1=" + QQMusicSupport.encode(LOGIN_JUMP)
                + "&ptqrtoken=" + QQMusicSupport.hash33(qrSig, 0)
                + "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052"
                + "&action=0-0-" + System.currentTimeMillis()
                + "&js_ver=20102616&js_type=1&pt_uistyle=40"
                + "&aid=" + APP_ID
                + "&daid=" + DAID
                + "&pt_3rd_aid=" + THIRD_APP_ID
                + "&has_onekey=1";
        QQMusicHttp.Response response = http.get(url, QQMusicHttp.headers(
                "Cookie", cookies.header(),
                "Referer", XUI_REFERER
        ));
        if (!response.isSuccess()) {
            throw new IOException("QQ QR status failed, HTTP " + response.status);
        }
        cookies.mergeSetCookies(response.headers);
        return parseCallback(response.text());
    }

    private String completeQQAuthorization(String callbackUrl, QQMusicHttp.CookieJar cookies)
            throws IOException {
        String uin = QQMusicSupport.queryParameter(callbackUrl, "uin");
        String sigx = QQMusicSupport.queryParameter(callbackUrl, "ptsigx");
        if (QQMusicSupport.isBlank(uin) || QQMusicSupport.isBlank(sigx)) {
            throw new IOException("QQ login callback did not contain uin and ptsigx");
        }

        String checkUrl = CHECK_SIG_URL
                + "?uin=" + QQMusicSupport.encode(uin)
                + "&pttype=1&service=ptqrlogin&nodirect=0"
                + "&ptsigx=" + QQMusicSupport.encode(sigx)
                + "&s_url=" + QQMusicSupport.encode(LOGIN_JUMP)
                + "&ptlang=2052&ptredirect=100"
                + "&aid=" + APP_ID
                + "&daid=" + DAID
                + "&j_later=0&low_login_hour=0&regmaster=0"
                + "&pt_login_type=3&pt_aid=0&pt_aaid=16&pt_light=0"
                + "&pt_3rd_aid=" + THIRD_APP_ID;
        QQMusicHttp.Response checkResponse = http.get(checkUrl, QQMusicHttp.headers(
                "Cookie", cookies.header(),
                "Referer", "https://xui.ptlogin2.qq.com/"
        ));
        if (checkResponse.status < 200 || checkResponse.status >= 400) {
            throw new IOException("QQ check_sig failed, HTTP " + checkResponse.status);
        }
        cookies.mergeSetCookies(checkResponse.headers);
        String pSkey = cookies.get("p_skey");
        if (QQMusicSupport.isBlank(pSkey)) {
            throw new IOException("QQ check_sig completed without p_skey; cookies=" + cookies.names());
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("response_type", "code");
        form.put("client_id", THIRD_APP_ID);
        form.put("redirect_uri", LOGIN_REDIRECT);
        form.put("scope", "get_user_info,get_app_friends");
        form.put("state", "state");
        form.put("switch", "");
        form.put("from_ptlogin", "1");
        form.put("src", "1");
        form.put("update_auth", "1");
        form.put("openapi", "1010_1030");
        form.put("g_tk", String.valueOf(QQMusicSupport.hash33(pSkey, 5381)));
        form.put("auth_time", String.valueOf(System.currentTimeMillis()));
        form.put("ui", UUID.randomUUID().toString().toUpperCase(Locale.ROOT));

        QQMusicHttp.Response authorize = http.postForm(AUTHORIZE_URL, form, QQMusicHttp.headers(
                "Cookie", cookies.header(),
                "Origin", "https://graph.qq.com",
                "Referer", LOGIN_JUMP
        ));
        String location = authorize.header("Location");
        if (!authorize.isRedirect() || QQMusicSupport.isBlank(location)) {
            throw new IOException("QQ OAuth authorize failed, HTTP " + authorize.status);
        }
        String code = QQMusicSupport.queryParameter(location, "code");
        if (QQMusicSupport.isBlank(code)) {
            throw new IOException("QQ OAuth authorize response did not contain code");
        }
        return code;
    }

    private void writeQrFiles(byte[] image) throws IOException {
        File png = new File(config.directory(), "qqmusic-login.png");
        File html = new File(config.directory(), "qqmusic-login.html");
        QQMusicSupport.writeBytes(png, image);
        String encoded = Base64.getEncoder().encodeToString(image);
        String page = "<!doctype html><meta charset=\"utf-8\"><title>QQ Music Login</title>"
                + "<body style=\"font-family:sans-serif;text-align:center;padding:32px\">"
                + "<h1>QQ Music Login</h1><p>Scan with QQ and confirm on your phone.</p>"
                + "<img alt=\"QQ Music login QR\" width=\"288\" height=\"288\" "
                + "src=\"data:image/png;base64," + encoded + "\"></body>";
        QQMusicSupport.writeText(html, page);
        QQMusicSupport.logInfo("QQ Music QR login file: " + html.getAbsolutePath());
    }

    private void startReloginWatcher() {
        final File trigger = new File(config.directory(), RELOGIN_TRIGGER);
        try {
            final WatchService watcher = FileSystems.getDefault().newWatchService();
            config.directory().toPath().register(
                    watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            consumeReloginTrigger(trigger);
            startEventWatcher(watcher, trigger);
            QQMusicSupport.logInfo("QQ Music config hot-reload watcher: "
                    + config.file.getAbsolutePath());
            QQMusicSupport.logInfo("QQ Music QR relogin trigger: " + trigger.getAbsolutePath());
        } catch (Exception e) {
            QQMusicSupport.logError("QQ Music file watcher unavailable; using low-frequency fallback: "
                    + e.getMessage());
            startPollingWatcher(trigger);
        }
    }

    private void startEventWatcher(final WatchService watcher, final File trigger) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        WatchKey key = watcher.take();
                        boolean configChanged = false;
                        boolean triggerChanged = false;
                        boolean overflow = false;
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                                overflow = true;
                                continue;
                            }
                            Object context = event.context();
                            if (!(context instanceof Path)) {
                                continue;
                            }
                            String name = ((Path) context).getFileName().toString();
                            if (config.file.getName().equals(name)) {
                                configChanged = true;
                            } else if (RELOGIN_TRIGGER.equals(name)) {
                                triggerChanged = true;
                            }
                        }
                        if ((configChanged || overflow) && config.reloadNow()) {
                            handleConfigReload();
                        }
                        if (triggerChanged || overflow) {
                            consumeReloginTrigger(trigger);
                        }
                        if (!key.reset()) {
                            return;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable e) {
                        QQMusicSupport.logError("QQ Music file watcher event failed: " + e.getMessage());
                    }
                }
            }
        }, "AllMusic-QQMusic-ConfigWatch");
        thread.setDaemon(true);
        thread.start();
    }

    private void startPollingWatcher(final File trigger) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        if (config.reloadNow()) {
                            handleConfigReload();
                        }
                        consumeReloginTrigger(trigger);
                        Thread.sleep(TRIGGER_POLL_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable e) {
                        QQMusicSupport.logError("QQ Music fallback watcher failed: " + e.getMessage());
                    }
                }
            }
        }, "AllMusic-QQMusic-ConfigPoll");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleConfigReload() {
        QQMusicCredential credential = config.credential();
        if (config.autoRefresh() && credential.expiresSoon()) {
            startRefresh();
        } else if (config.qrLogin() && !credential.isComplete()) {
            startQrLogin("config-reload", false);
        }
    }

    private void consumeReloginTrigger(File trigger) {
        if (trigger.isFile() && trigger.delete()) {
            startQrLogin("trigger-file", true);
        }
    }

    private static boolean sameCredential(QQMusicCredential first, QQMusicCredential second) {
        return first != null && second != null
                && first.musicId.equals(second.musicId)
                && first.musicKey.equals(second.musicKey);
    }

    static QrStatus parseCallback(String body) throws IOException {
        Matcher callback = CALLBACK.matcher(body == null ? "" : body);
        if (!callback.find()) {
            throw new IOException("Unexpected QQ QR status response: "
                    + QQMusicSupport.limit(body == null ? "" : body, 200));
        }
        Matcher valuesMatcher = CALLBACK_VALUE.matcher(callback.group(1));
        List<String> values = new ArrayList<>();
        while (valuesMatcher.find()) {
            values.add(valuesMatcher.group(1));
        }
        if (values.isEmpty()) {
            throw new IOException("QQ QR status response was empty");
        }
        String callbackUrl = values.size() > 2
                ? values.get(2).replace("\\/", "/").replace("\\x26", "&")
                : "";
        String message = values.size() > 4 ? values.get(4) : "";
        return new QrStatus(values.get(0), callbackUrl, message);
    }

    private static String messageSuffix(QrStatus status) {
        return QQMusicSupport.isBlank(status.message)
                ? ""
                : ": " + status.message;
    }

    private static final class QrCode {
        final String qrSig;
        final byte[] image;
        final QQMusicHttp.CookieJar cookies;

        QrCode(String qrSig, byte[] image, QQMusicHttp.CookieJar cookies) {
            this.qrSig = qrSig;
            this.image = image;
            this.cookies = cookies;
        }
    }

    static final class QrStatus {
        final String code;
        final String callbackUrl;
        final String message;

        QrStatus(String code, String callbackUrl) {
            this(code, callbackUrl, "");
        }

        QrStatus(String code, String callbackUrl, String message) {
            this.code = code == null ? "" : code;
            this.callbackUrl = callbackUrl == null ? "" : callbackUrl;
            this.message = message == null ? "" : message;
        }
    }
}
