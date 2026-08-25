package com.coloryr.allmusic.server.core.api.qqmusic;

import com.google.gson.JsonObject;

final class QQMusicCredential {
    static final long DEFAULT_KEY_EXPIRES_IN = 259200L; // 3 days in seconds
    static final QQMusicCredential EMPTY = new QQMusicCredential(
            "", "", "", 0L, "0", "", "", "0", "",
            0L, 0L, 0
    );

    final String openId;
    final String refreshToken;
    final String accessToken;
    final long expiredAt;
    final String musicId;
    final String musicKey;
    final String unionId;
    final String stringMusicId;
    final String refreshKey;
    final long musicKeyCreateTime;
    final long keyExpiresIn;
    final int loginType;

    QQMusicCredential(String openId, String refreshToken, String accessToken, long expiredAt,
                      String musicId, String musicKey, String unionId, String stringMusicId,
                      String refreshKey, long musicKeyCreateTime, long keyExpiresIn, int loginType) {
        this.openId = QQMusicSupport.trim(openId);
        this.refreshToken = QQMusicSupport.trim(refreshToken);
        this.accessToken = QQMusicSupport.trim(accessToken);
        this.expiredAt = Math.max(0L, expiredAt);
        this.musicId = QQMusicSupport.normalizeUin(musicId);
        this.musicKey = QQMusicSupport.trim(musicKey);
        this.unionId = QQMusicSupport.trim(unionId);
        this.stringMusicId = QQMusicSupport.normalizeUin(
                QQMusicSupport.firstNonBlank(stringMusicId, musicId)
        );
        this.refreshKey = QQMusicSupport.trim(refreshKey);
        this.musicKeyCreateTime = Math.max(0L, musicKeyCreateTime);
        this.keyExpiresIn = Math.max(0L, keyExpiresIn);
        this.loginType = loginType > 0 ? loginType : inferLoginType(this.musicKey);
    }

    static QQMusicCredential fromConfig(JsonObject root) {
        JsonObject source = QQMusicSupport.object(root, "credential");
        if (source == null) {
            return EMPTY;
        }
        String id = QQMusicSupport.firstNonBlank(
                QQMusicSupport.string(source, "musicid"),
                QQMusicSupport.string(source, "str_musicid"),
                QQMusicSupport.string(source, "musicId"),
                QQMusicSupport.string(source, "uin"),
                QQMusicSupport.string(source, "login_uin")
        );
        String key = QQMusicSupport.firstNonBlank(
                QQMusicSupport.string(source, "musickey"),
                QQMusicSupport.string(source, "musicKey"),
                QQMusicSupport.string(source, "qm_keyst"),
                QQMusicSupport.string(source, "qqmusic_key")
        );
        long createTime = firstLong(source, "musickeyCreateTime", "musicKeyCreateTime",
                "psrf_musickey_createtime", "createtime");
        long expiresIn = firstLong(source, "keyExpiresIn", "key_expires_in",
                "expired_in", "expires_in", "expire", "vkey_valid_period");
        if (createTime <= 0L && !QQMusicSupport.isBlank(key)) {
            createTime = System.currentTimeMillis() / 1000L;
        }
        if (expiresIn <= 0L && !QQMusicSupport.isBlank(key)) {
            expiresIn = DEFAULT_KEY_EXPIRES_IN;
        }
        return new QQMusicCredential(
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "openid"),
                        QQMusicSupport.string(source, "openId"),
                        QQMusicSupport.string(source, "psrf_qqopenid"),
                        QQMusicSupport.string(source, "wxopenid")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "refresh_token"),
                        QQMusicSupport.string(source, "refreshToken"),
                        QQMusicSupport.string(source, "psrf_qqrefresh_token"),
                        QQMusicSupport.string(source, "wxrefresh_token")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "access_token"),
                        QQMusicSupport.string(source, "accessToken"),
                        QQMusicSupport.string(source, "psrf_qqaccess_token")
                ),
                firstLong(source, "expired_at", "expiredAt", "psrf_access_token_expiresAt", "accessTokenExpireAt"),
                id,
                key,
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "unionid"),
                        QQMusicSupport.string(source, "unionId"),
                        QQMusicSupport.string(source, "psrf_qqunionid")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "str_musicid"),
                        QQMusicSupport.string(source, "stringMusicId"),
                        id
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "refresh_key"),
                        QQMusicSupport.string(source, "refreshKey")
                ),
                createTime,
                expiresIn,
                firstInt(source, "loginType", "login_type", "tmeLoginType", "logintype")
        );
    }

    static QQMusicCredential fromLoginData(JsonObject data) {
        return fromLoginData(data, null);
    }

    static QQMusicCredential fromLoginData(JsonObject data, QQMusicCredential previous) {
        if (data == null) {
            return previous == null ? EMPTY : previous;
        }
        String id = QQMusicSupport.firstNonBlank(
                QQMusicSupport.string(data, "musicid"),
                QQMusicSupport.string(data, "musicId"),
                QQMusicSupport.string(data, "str_musicid"),
                QQMusicSupport.string(data, "login_uin"),
                QQMusicSupport.string(data, "uin"),
                previous == null ? null : previous.musicId
        );
        String key = QQMusicSupport.firstNonBlank(
                QQMusicSupport.string(data, "musickey"),
                QQMusicSupport.string(data, "musicKey"),
                QQMusicSupport.string(data, "qm_keyst"),
                QQMusicSupport.string(data, "qqmusic_key"),
                previous == null ? null : previous.musicKey
        );
        long createTime = firstLong(data, "musickeyCreateTime", "musicKeyCreateTime",
                "psrf_musickey_createtime", "createtime");
        if (createTime <= 0L) {
            createTime = previous != null && previous.musicKeyCreateTime > 0L
                    && QQMusicSupport.trim(key).equals(previous.musicKey)
                    ? previous.musicKeyCreateTime
                    : System.currentTimeMillis() / 1000L;
        }
        long expiresIn = firstLong(data, "keyExpiresIn", "key_expires_in",
                "expired_in", "expires_in", "expire", "vkey_valid_period");
        if (expiresIn <= 0L) {
            expiresIn = previous != null && previous.keyExpiresIn > 0L
                    ? previous.keyExpiresIn
                    : DEFAULT_KEY_EXPIRES_IN;
        }
        long expiredAt = firstLong(data, "expired_at", "expiredAt",
                "psrf_access_token_expiresAt", "accessTokenExpireAt");
        if (expiredAt <= 0L && previous != null) {
            expiredAt = previous.expiredAt;
        }
        int loginType = firstInt(data, "loginType", "login_type", "tmeLoginType", "logintype");
        if (loginType <= 0 && previous != null) {
            loginType = previous.loginType;
        }

        return new QQMusicCredential(
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "openid"),
                        QQMusicSupport.string(data, "openId"),
                        QQMusicSupport.string(data, "psrf_qqopenid"),
                        QQMusicSupport.string(data, "wxopenid"),
                        previous == null ? null : previous.openId
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "refresh_token"),
                        QQMusicSupport.string(data, "refreshToken"),
                        QQMusicSupport.string(data, "psrf_qqrefresh_token"),
                        QQMusicSupport.string(data, "wxrefresh_token"),
                        previous == null ? null : previous.refreshToken
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "access_token"),
                        QQMusicSupport.string(data, "accessToken"),
                        QQMusicSupport.string(data, "psrf_qqaccess_token"),
                        previous == null ? null : previous.accessToken
                ),
                expiredAt,
                id,
                key,
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "unionid"),
                        QQMusicSupport.string(data, "unionId"),
                        QQMusicSupport.string(data, "psrf_qqunionid"),
                        previous == null ? null : previous.unionId
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "str_musicid"),
                        QQMusicSupport.string(data, "stringMusicId"),
                        id,
                        previous == null ? null : previous.stringMusicId
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "refresh_key"),
                        QQMusicSupport.string(data, "refreshKey"),
                        previous == null ? null : previous.refreshKey
                ),
                createTime,
                expiresIn,
                loginType
        );
    }

    boolean isComplete() {
        return !"0".equals(musicId) && !QQMusicSupport.isBlank(musicKey);
    }

    boolean canRefresh() {
        return isComplete() && (!QQMusicSupport.isBlank(refreshKey)
                || !QQMusicSupport.isBlank(refreshToken)
                || !QQMusicSupport.isBlank(accessToken));
    }

    boolean expiresSoon() {
        if (!isComplete()) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000L;
        long createTime = musicKeyCreateTime > 0L ? musicKeyCreateTime : now;
        long duration = keyExpiresIn > 0L ? keyExpiresIn : DEFAULT_KEY_EXPIRES_IN;
        long refreshAt = createTime + duration - 86400L;
        if (now >= refreshAt) {
            return true;
        }
        if (expiredAt > 0L) {
            long oauthExpiry = expiredAt > 1000000000L ? expiredAt : (createTime + expiredAt);
            if (now >= (oauthExpiry - 86400L * 2)) {
                return true;
            }
        }
        return false;
    }

    String cookieHeader() {
        if (!isComplete()) {
            return "";
        }
        QQMusicHttp.CookieJar cookies = new QQMusicHttp.CookieJar();
        cookies.put("uin", stringMusicId);
        cookies.put("qqmusic_uin", stringMusicId);
        cookies.put("qm_keyst", musicKey);
        cookies.put("qqmusic_key", musicKey);
        return cookies.header();
    }

    JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("openid", openId);
        object.addProperty("refresh_token", refreshToken);
        object.addProperty("access_token", accessToken);
        object.addProperty("expired_at", expiredAt);
        object.addProperty("musicid", musicId);
        object.addProperty("musickey", musicKey);
        object.addProperty("unionid", unionId);
        object.addProperty("str_musicid", stringMusicId);
        object.addProperty("refresh_key", refreshKey);
        object.addProperty("musickeyCreateTime", musicKeyCreateTime);
        object.addProperty("keyExpiresIn", keyExpiresIn);
        object.addProperty("loginType", loginType);
        return object;
    }

    private static int inferLoginType(String key) {
        if (QQMusicSupport.isBlank(key)) {
            return 0;
        }
        return key.startsWith("W_X") ? 1 : 2;
    }

    private static long firstLong(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object != null && object.has(key)) {
                return QQMusicSupport.longValue(object, key, 0L);
            }
        }
        return 0L;
    }

    private static int firstInt(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object != null && object.has(key)) {
                return QQMusicSupport.integer(object, key, 0);
            }
        }
        return 0;
    }
}
