package com.coloryr.allmusic.server.core.api.qqmusic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

final class QQMusicClient {
    private static final String MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String REFERER = "https://y.qq.com/";
    private static final String FALLBACK_CDN = "https://isure.stream.qqmusic.qq.com/";

    private final QQMusicConfig config;
    private final QQMusicHttp http;
    private final Random random = new Random();

    QQMusicClient(QQMusicConfig config) {
        this.config = config;
        this.http = new QQMusicHttp(config);
    }

    QQMusicHttp http() {
        return http;
    }

    List<QQMusicTrack> search(String query) throws IOException {
        return searchModern(query);
    }

    QQMusicTrack getTrack(String songMid) throws IOException {
        JsonObject param = new JsonObject();
        param.addProperty("song_mid", songMid);
        JsonObject data = callData(
                "music.pf_song_detail_svr",
                "get_song_detail_yqq",
                param,
                config.credential(),
                null
        );
        return QQMusicTrack.fromJson(QQMusicSupport.object(data, "track_info"));
    }

    JsonObject getLyrics(String songMid) throws IOException {
        return callData(
                "music.musichallSong.PlayLyricInfo",
                "GetPlayLyricInfo",
                lyricParam(songMid),
                config.credential(),
                null
        );
    }

    static JsonObject lyricParam(String songMid) {
        JsonObject param = new JsonObject();
        param.addProperty("crypt", 1);
        // PlayLyricInfo still selects its response shape with the desktop
        // protocol version even when musicu itself uses the web comm block.
        param.addProperty("ct", 19);
        param.addProperty("cv", 2111);
        param.addProperty("lrc_t", 0);
        param.addProperty("qrc", 1);
        param.addProperty("qrc_t", 0);
        param.addProperty("roma", 1);
        param.addProperty("roma_t", 0);
        param.addProperty("songMid", songMid);
        param.addProperty("trans", 1);
        param.addProperty("trans_t", 0);
        param.addProperty("type", 0);
        return param;
    }

    String getPlayUrl(QQMusicTrack track) throws IOException {
        for (String configured : config.qualityList()) {
            QQMusicTrack.Quality quality = QQMusicTrack.Quality.fromConfig(configured);
            if (quality == null) {
                continue;
            }
            String url = requestModernVkey(track, quality);
            if (!QQMusicSupport.isBlank(url)) {
                return url;
            }
        }
        return null;
    }

    QQMusicCredential exchangeQQCode(String code) throws IOException {
        JsonObject param = new JsonObject();
        param.addProperty("code", code);
        CallResult result = call(
                "QQConnectLogin.LoginServer",
                "QQLogin",
                param,
                QQMusicCredential.EMPTY,
                2
        );
        return loginCredential(result, "QQ Music QQLogin", null);
    }

    QQMusicCredential refreshCredential(QQMusicCredential current) throws IOException {
        if (current == null || !current.canRefresh()) {
            throw new IOException("QQ Music credential cannot be refreshed");
        }
        JsonObject param = refreshParam(current);

        CallResult result = call(
                "music.login.LoginServer",
                "Login",
                param,
                current,
                current.loginType
        );
        return loginCredential(result, "QQ Music credential refresh", current);
    }

    static JsonObject refreshParam(QQMusicCredential current) {
        JsonObject param = new JsonObject();
        param.addProperty("openid", current.openId);
        param.addProperty("refresh_token", current.refreshToken);
        param.addProperty("musickey", current.musicKey);
        param.addProperty("refresh_key", current.refreshKey);
        param.addProperty("loginMode", 2);
        long expiresIn = current.keyExpiresIn > 0L ? current.keyExpiresIn : QQMusicCredential.DEFAULT_KEY_EXPIRES_IN;
        param.addProperty("expired_in", expiresIn);
        if (current.loginType == 1) {
            param.addProperty("str_musicid", current.stringMusicId);
            param.addProperty("unionid", current.unionId);
        } else if (current.loginType == 2) {
            param.addProperty("access_token", current.accessToken);
            param.addProperty("musicid", current.musicId);
        } else {
            param.addProperty("access_token", current.accessToken);
            param.addProperty("str_musicid", current.stringMusicId);
            param.addProperty("musicid", current.musicId);
            param.addProperty("unionid", current.unionId);
        }
        return param;
    }

    static QQMusicCredential loginCredential(CallResult result, String operation) throws IOException {
        return loginCredential(result, operation, null);
    }

    static QQMusicCredential loginCredential(CallResult result, String operation, QQMusicCredential previous) throws IOException {
        result.requireSuccess(operation);
        JsonObject envelope = result.data;
        int loginCode = QQMusicSupport.integer(envelope, "code", 0);
        if (loginCode != 0) {
            throw new IOException(operation + " failed, login code=" + loginCode);
        }
        JsonObject payload = QQMusicSupport.object(envelope, "data");
        QQMusicCredential credential = QQMusicCredential.fromLoginData(
                payload == null ? envelope : payload,
                previous
        );
        if (!credential.isComplete()) {
            throw new IOException(operation + " returned incomplete credentials");
        }
        return credential;
    }

    private List<QQMusicTrack> searchModern(String query) throws IOException {
        JsonObject param = new JsonObject();
        param.addProperty("searchid", searchId());
        param.addProperty("search_type", 100);
        param.addProperty("page_num", config.searchLimit());
        param.addProperty("query", query);
        param.addProperty("page_id", 1);
        param.addProperty("highlight", 0);
        param.addProperty("grp", 1);

        JsonObject data = callData(
                "music.adaptor.SearchAdaptor",
                "do_search_v2",
                param,
                config.credential(),
                null
        );
        JsonArray songs = modernSongArray(data);
        return parseTracks(songs);
    }

    private String requestModernVkey(QQMusicTrack track, QQMusicTrack.Quality quality) throws IOException {
        QQMusicCredential credential = config.credential();
        JsonObject param = vkeyParam(track, quality);
        CallResult result = call(
                "music.vkey.GetVkey",
                "UrlGetVkey",
                param,
                credential,
                credential.loginType
        );
        if (result.code != 0) {
            QQMusicSupport.logInfo("QQ Music modern vkey failed, code=" + result.code
                    + " quality=" + quality.configName);
            return null;
        }
        return parseVkeyUrl(result.data, track, quality);
    }

    private JsonObject vkeyParam(QQMusicTrack track, QQMusicTrack.Quality quality) {
        QQMusicCredential credential = config.credential();
        JsonObject param = new JsonObject();
        param.addProperty("uin", credential.stringMusicId);
        JsonArray filenames = new JsonArray();
        filenames.add(quality.filename(track));
        param.add("filename", filenames);
        param.addProperty("guid", guid());
        JsonArray songMids = new JsonArray();
        songMids.add(track.songMid);
        param.add("songmid", songMids);
        JsonArray songTypes = new JsonArray();
        songTypes.add(track.songType);
        param.add("songtype", songTypes);
        param.addProperty("ctx", 0);
        return param;
    }

    private String parseVkeyUrl(JsonObject data, QQMusicTrack track, QQMusicTrack.Quality quality) {
        JsonArray midUrlInfo = QQMusicSupport.array(data, "midurlinfo");
        if (midUrlInfo == null || midUrlInfo.size() == 0 || !midUrlInfo.get(0).isJsonObject()) {
            return null;
        }
        JsonObject info = midUrlInfo.get(0).getAsJsonObject();
        String purl = QQMusicSupport.string(info, "purl");
        if (QQMusicSupport.isBlank(purl)) {
            QQMusicSupport.logInfo("QQ Music purl empty for " + track.songMid
                    + " quality=" + quality.configName
                    + " result=" + QQMusicSupport.string(info, "result"));
            return null;
        }
        if (purl.startsWith("http://") || purl.startsWith("https://")) {
            return purl;
        }
        String domain = firstDomain(QQMusicSupport.array(data, "sip"));
        return (QQMusicSupport.isBlank(domain) ? FALLBACK_CDN : domain) + purl;
    }

    JsonObject callData(String module, String method, JsonObject param,
                        QQMusicCredential credential, Integer loginType) throws IOException {
        CallResult result = call(module, method, param, credential, loginType);
        result.requireSuccess(module + "/" + method);
        return result.data;
    }

    CallResult call(String module, String method, JsonObject param,
                    QQMusicCredential credential, Integer loginType) throws IOException {
        JsonObject request = buildRequest(module, method, param, credential, loginType);
        QQMusicHttp.Response response = http.postJson(MUSICU_URL, request, requestHeaders(credential));
        if (!response.isSuccess()) {
            throw new IOException("QQ Music API failed, HTTP " + response.status);
        }
        JsonObject root = response.json();
        JsonObject item = QQMusicSupport.object(root, "req_0");
        int code = item == null
                ? QQMusicSupport.integer(root, "code", -1)
                : QQMusicSupport.integer(item, "code", QQMusicSupport.integer(root, "code", -1));
        JsonObject data = item == null ? null : QQMusicSupport.object(item, "data");
        return new CallResult(code, data == null ? new JsonObject() : data, root);
    }

    JsonObject buildRequest(String module, String method, JsonObject param,
                            QQMusicCredential credential, Integer loginType) {
        JsonObject root = new JsonObject();
        root.add("comm", buildComm(credential, loginType));
        JsonObject request = new JsonObject();
        request.addProperty("module", module);
        request.addProperty("method", method);
        request.add("param", param == null ? new JsonObject() : param);
        root.add("req_0", request);
        return root;
    }

    JsonObject buildComm(QQMusicCredential credential, Integer loginType) {
        QQMusicCredential active = credential == null ? QQMusicCredential.EMPTY : credential;
        int gtk = active.isComplete() ? QQMusicSupport.hash33(active.musicKey, 5381) : 5381;
        JsonObject comm = new JsonObject();
        comm.addProperty("ct", 24);
        comm.addProperty("cv", 4747474);
        comm.addProperty("platform", "yqq.json");
        comm.addProperty("format", "json");
        comm.addProperty("inCharset", "utf-8");
        comm.addProperty("outCharset", "utf-8");
        comm.addProperty("notice", 0);
        comm.addProperty("needNewCode", 1);
        comm.addProperty("g_tk", gtk);
        comm.addProperty("g_tk_new_20200303", gtk);
        comm.addProperty("uin", active.isComplete() ? active.musicId : "0");
        if (active.isComplete()) {
            comm.addProperty("qq", active.musicId);
            comm.addProperty("authst", active.musicKey);
        }
        int finalLoginType = loginType == null ? active.loginType : loginType;
        if (finalLoginType > 0) {
            comm.addProperty("tmeLoginType", finalLoginType);
        }
        return comm;
    }

    private Map<String, String> requestHeaders(QQMusicCredential credential) {
        Map<String, String> headers = QQMusicHttp.headers(
                "Origin", "https://y.qq.com",
                "Referer", REFERER
        );
        if (credential != null && credential.isComplete()) {
            headers.put("Cookie", credential.cookieHeader());
        }
        return headers;
    }

    private List<QQMusicTrack> parseTracks(JsonArray values) {
        LinkedHashMap<String, QQMusicTrack> unique = new LinkedHashMap<>();
        if (values != null) {
            for (JsonElement value : values) {
                if (value != null && value.isJsonObject()) {
                    QQMusicTrack track = QQMusicTrack.fromJson(value.getAsJsonObject());
                    if (track != null && !QQMusicSupport.isBlank(track.songMid)) {
                        unique.put(track.songMid, track);
                        if (unique.size() >= config.searchLimit()) {
                            break;
                        }
                    }
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static JsonArray modernSongArray(JsonObject data) {
        JsonObject body = QQMusicSupport.object(data, "body");
        JsonObject itemSong = QQMusicSupport.object(body, "item_song");
        JsonArray items = QQMusicSupport.array(itemSong, "items");
        if (items != null) {
            return items;
        }
        JsonObject song = QQMusicSupport.object(data, "song");
        items = QQMusicSupport.array(song, "list");
        if (items != null) {
            return items;
        }
        return QQMusicSupport.array(data, "list");
    }

    private static String firstDomain(JsonArray domains) {
        if (domains == null) {
            return "";
        }
        String first = "";
        for (JsonElement value : domains) {
            if (value == null || value.isJsonNull()) {
                continue;
            }
            String domain = QQMusicSupport.trim(value.getAsString());
            if (QQMusicSupport.isBlank(domain)) {
                continue;
            }
            if (QQMusicSupport.isBlank(first)) {
                first = domain;
            }
            if (!domain.startsWith("http://ws")) {
                return domain;
            }
        }
        return first;
    }

    private String searchId() {
        long group = (1L + random.nextInt(20)) * 18014398509481984L;
        long bucket = (long) random.nextInt(4194305) * 4294967296L;
        long millisOfDay = System.currentTimeMillis() % 86400000L;
        return String.valueOf(group + bucket + millisOfDay);
    }

    private static String guid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static final class CallResult {
        final int code;
        final JsonObject data;
        final JsonObject raw;

        CallResult(int code, JsonObject data, JsonObject raw) {
            this.code = code;
            this.data = data;
            this.raw = raw;
        }

        void requireSuccess(String operation) throws IOException {
            if (code != 0) {
                throw new IOException(operation + " failed, code=" + code);
            }
        }
    }
}
