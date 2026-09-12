package com.coloryr.allmusic.server.core.api.qqmusic;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMusicClientTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearConfigOverride() {
        System.clearProperty("qqmusic.config");
    }

    @Test
    void buildsCurrentMusicuRequestForAnonymousCalls() throws Exception {
        QQMusicClient client = clientWithConfig();
        JsonObject request = client.buildRequest(
                "music.adaptor.SearchAdaptor", "do_search_v2", new JsonObject(),
                QQMusicCredential.EMPTY, null);

        assertEquals("music.adaptor.SearchAdaptor", request.getAsJsonObject("req_0")
                .get("module").getAsString());
        assertEquals("do_search_v2", request.getAsJsonObject("req_0")
                .get("method").getAsString());
        JsonObject comm = request.getAsJsonObject("comm");
        assertEquals(24, comm.get("ct").getAsInt());
        assertEquals("yqq.json", comm.get("platform").getAsString());
        assertEquals("0", comm.get("uin").getAsString());
        assertFalse(comm.has("authst"));
    }

    @Test
    void requestsEncryptedQrcTranslationAndRomanizationBySongMid() {
        JsonObject param = QQMusicClient.lyricParam("001Bbywq2gicae");

        assertEquals("001Bbywq2gicae", param.get("songMid").getAsString());
        assertEquals(19, param.get("ct").getAsInt());
        assertEquals(2111, param.get("cv").getAsInt());
        assertEquals(1, param.get("crypt").getAsInt());
        assertEquals(1, param.get("qrc").getAsInt());
        assertEquals(1, param.get("trans").getAsInt());
        assertEquals(1, param.get("roma").getAsInt());
    }

    @Test
    void addsAuthenticatedCommFieldsWithoutSerializingRefreshSecrets() throws Exception {
        QQMusicClient client = clientWithConfig();
        QQMusicCredential credential = new QQMusicCredential(
                "open", "refresh-token", "access-token", 0L,
                "123456", "Q_H_L_key", "union", "123456",
                "refresh-key", 0L, 0L, 2);

        JsonObject comm = client.buildComm(credential, null);

        assertEquals("123456", comm.get("uin").getAsString());
        assertEquals("Q_H_L_key", comm.get("authst").getAsString());
        assertEquals(2, comm.get("tmeLoginType").getAsInt());
        assertTrue(comm.has("g_tk_new_20200303"));
        assertFalse(comm.toString().contains("refresh-token"));
        assertFalse(comm.toString().contains("access-token"));
        assertFalse(comm.toString().contains("refresh-key"));
    }

    @Test
    void unwrapsTheNestedLoginCredentialEnvelope() throws Exception {
        JsonObject credentialData = new JsonObject();
        credentialData.addProperty("musicid", "123456");
        credentialData.addProperty("musickey", "Q_H_L_key");
        credentialData.addProperty("str_musicid", "123456");
        credentialData.addProperty("loginType", 2);
        JsonObject loginEnvelope = new JsonObject();
        loginEnvelope.addProperty("code", 0);
        loginEnvelope.add("data", credentialData);
        QQMusicClient.CallResult result = new QQMusicClient.CallResult(
                0, loginEnvelope, new JsonObject());

        QQMusicCredential credential = QQMusicClient.loginCredential(result, "test login");

        assertTrue(credential.isComplete());
        assertEquals("123456", credential.musicId);
    }

    @Test
    void rejectsInnerLoginErrors() {
        JsonObject loginEnvelope = new JsonObject();
        loginEnvelope.addProperty("code", 20261);
        QQMusicClient.CallResult result = new QQMusicClient.CallResult(
                0, loginEnvelope, new JsonObject());

        assertThrows(Exception.class, () -> QQMusicClient.loginCredential(result, "test login"));
    }

    @Test
    void buildsValidRefreshParamWithoutAbsoluteTimestampCorruption() {
        QQMusicCredential credential = new QQMusicCredential(
                "open-123", "refresh-token-123", "access-token-123", 1800000000L,
                "123456", "Q_H_L_key", "union-123", "123456",
                "refresh-key-123", 1700000000L, 259200L, 2);

        JsonObject param = QQMusicClient.refreshParam(credential);

        assertEquals("open-123", param.get("openid").getAsString());
        assertEquals("refresh-token-123", param.get("refresh_token").getAsString());
        assertEquals("Q_H_L_key", param.get("musickey").getAsString());
        assertEquals("refresh-key-123", param.get("refresh_key").getAsString());
        assertEquals(2, param.get("loginMode").getAsInt());
        assertEquals(1800000000L, param.get("expired_in").getAsLong());
        assertEquals("access-token-123", param.get("access_token").getAsString());
        assertEquals(123456L, param.get("musicid").getAsLong());
    }

    @Test
    void buildsMobileRefreshCommFields() {
        QQMusicCredential credential = new QQMusicCredential(
                "open", "refresh", "access", 1800000000L,
                "123456", "Q_H_L_key", "union", "123456",
                "", 0L, 259200L, 2);

        JsonObject comm = QQMusicClient.buildMobileComm(credential);

        assertEquals(11, comm.get("ct").getAsInt());
        assertEquals(14090008, comm.get("cv").getAsInt());
        assertEquals(14090008, comm.get("v").getAsInt());
        assertEquals("10003505", comm.get("chid").getAsString());
        assertEquals("qqmusic", comm.get("tmeAppID").getAsString());
        assertEquals("123456", comm.get("uin").getAsString());
        assertEquals("Q_H_L_key", comm.get("authst").getAsString());
        assertEquals(2, comm.get("tmeLoginType").getAsInt());
    }

    private QQMusicClient clientWithConfig() throws Exception {
        Path config = temporaryDirectory.resolve("qqmusic.json");
        String json = "{\"credential\":{},\"qrLogin\":false,\"autoRefresh\":false,"
                + "\"qualities\":\"m4a,128,320\",\"searchLimit\":20,\"timeoutSeconds\":20}";
        Files.write(config, json.getBytes(StandardCharsets.UTF_8));
        System.setProperty("qqmusic.config", config.toString());
        return new QQMusicClient(QQMusicConfig.load());
    }
}
