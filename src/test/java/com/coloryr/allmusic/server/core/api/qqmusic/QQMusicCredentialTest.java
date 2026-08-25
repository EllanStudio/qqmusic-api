package com.coloryr.allmusic.server.core.api.qqmusic;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMusicCredentialTest {
    @Test
    void loadsOnlyTheVersionTwoNestedCredential() {
        JsonObject source = new JsonObject();
        source.addProperty("musicid", "o00123456");
        source.addProperty("musickey", "Q_H_L_test");
        source.addProperty("str_musicid", "00123456");
        source.addProperty("refresh_key", "refresh");
        source.addProperty("loginType", 2);
        JsonObject root = new JsonObject();
        root.add("credential", source);

        QQMusicCredential credential = QQMusicCredential.fromConfig(root);

        assertTrue(credential.isComplete());
        assertEquals("123456", credential.musicId);
        assertEquals(2, credential.loginType);
        assertTrue(credential.canRefresh());
        assertTrue(credential.cookieHeader().contains("uin=123456"));
        assertTrue(credential.cookieHeader().contains("qm_keyst=Q_H_L_test"));
    }

    @Test
    void doesNotTreatLegacyTopLevelFieldsAsCredentials() {
        JsonObject root = new JsonObject();
        root.addProperty("uin", "123456");
        root.addProperty("qqmusicKey", "legacy-key");

        assertFalse(QQMusicCredential.fromConfig(root).isComplete());
    }

    @Test
    void rejectsPartialCredentials() {
        JsonObject data = new JsonObject();
        data.addProperty("musicid", "123456");
        assertFalse(QQMusicCredential.fromLoginData(data).isComplete());
    }

    @Test
    void parsesServerStandardExpiredInAndCreateTimeFields() {
        JsonObject data = new JsonObject();
        data.addProperty("musicid", "123456");
        data.addProperty("musickey", "Q_H_L_abc");
        data.addProperty("expired_in", 259200L);
        data.addProperty("psrf_musickey_createtime", 1700000000L);
        data.addProperty("psrf_qqopenid", "test-openid");
        data.addProperty("psrf_qqrefresh_token", "test-refresh");

        QQMusicCredential credential = QQMusicCredential.fromLoginData(data);

        assertTrue(credential.isComplete());
        assertEquals(259200L, credential.keyExpiresIn);
        assertEquals(1700000000L, credential.musicKeyCreateTime);
        assertEquals("test-openid", credential.openId);
        assertEquals("test-refresh", credential.refreshToken);
        assertTrue(credential.expiresSoon());
    }

    @Test
    void appliesSafeDefaultsWhenExpiryFieldsAreOmitted() {
        JsonObject data = new JsonObject();
        data.addProperty("musicid", "123456");
        data.addProperty("musickey", "Q_H_L_fresh");

        QQMusicCredential credential = QQMusicCredential.fromLoginData(data);

        assertTrue(credential.isComplete());
        assertEquals(QQMusicCredential.DEFAULT_KEY_EXPIRES_IN, credential.keyExpiresIn);
        assertTrue(credential.musicKeyCreateTime > 0L);
        assertFalse(credential.expiresSoon());
    }

    @Test
    void mergesPreviousCredentialFieldsOnRefreshResponse() {
        QQMusicCredential previous = new QQMusicCredential(
                "orig-openid", "orig-refresh-token", "orig-access-token", 1800000000L,
                "123456", "Q_H_L_old", "orig-union", "123456",
                "orig-refresh-key", 1700000000L, 259200L, 2
        );

        JsonObject refreshData = new JsonObject();
        refreshData.addProperty("musickey", "Q_H_L_renewed");
        refreshData.addProperty("expired_in", 259200L);

        QQMusicCredential merged = QQMusicCredential.fromLoginData(refreshData, previous);

        assertEquals("123456", merged.musicId);
        assertEquals("Q_H_L_renewed", merged.musicKey);
        assertEquals("orig-openid", merged.openId);
        assertEquals("orig-refresh-token", merged.refreshToken);
        assertEquals("orig-refresh-key", merged.refreshKey);
        assertEquals(2, merged.loginType);
    }
}
