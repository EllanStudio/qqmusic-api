package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.IMusicApi;
import com.coloryr.allmusic.server.core.music.LyricSave;
import com.coloryr.allmusic.server.core.objs.SearchMusicObj;
import com.coloryr.allmusic.server.core.objs.music.SearchPageObj;
import com.coloryr.allmusic.server.core.objs.music.SongInfoObj;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AllMusic external API adapter backed by QQ Music's current musicu protocol. */
public final class QQMusicApi implements IMusicApi {
    public static final String API_ID = "qqmusic";

    private static final Pattern MID = Pattern.compile("^[A-Za-z0-9]{8,32}$");
    private static final Pattern SONG_MID_QUERY = Pattern.compile(
            "[?&]songmid=([A-Za-z0-9]{8,32})(?:[&#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SONG_MID_PATH = Pattern.compile(
            "/songDetail/([A-Za-z0-9]{8,32})(?:[/?#]|$)", Pattern.CASE_INSENSITIVE);

    private final QQMusicConfig config;
    private final QQMusicClient client;
    private final QQMusicLogin login;
    private final QQMusicRequestTracker requests = new QQMusicRequestTracker();

    public QQMusicApi() {
        this(QQMusicConfig.load());
    }

    QQMusicApi(QQMusicConfig config) {
        this.config = config;
        this.client = new QQMusicClient(config);
        this.login = new QQMusicLogin(config, client);
        QQMusicSupport.logInfo("QQ Music API loaded, credential="
                + config.credential().isComplete()
                + ", qualities=" + config.qualities()
                + ", searchLimit=" + config.searchLimit()
                + ", qrLogin=" + config.qrLogin());
        login.start();
    }

    @Override
    public String getId() {
        return API_ID;
    }

    @Override
    public SongInfoObj getMusic(String id, final String player, final boolean isList) {
        final String songMid = normalizeSongMid(id);
        if (songMid == null) {
            return null;
        }
        return withBusy(new Operation<SongInfoObj>() {
            @Override
            public SongInfoObj run() throws Exception {
                QQMusicTrack track = client.getTrack(songMid);
                if (track == null) {
                    return null;
                }
                return new SongInfoObj(
                        defaultText(track.singer, "QQ Music"),
                        defaultText(track.title, track.songMid),
                        track.songMid,
                        "",
                        player,
                        defaultText(track.album, "QQ Music"),
                        isList,
                        track.durationMillis,
                        track.coverUrl(),
                        false,
                        null,
                        API_ID
                );
            }
        });
    }

    @Override
    public SearchPageObj search(String[] args, boolean isDefault) {
        final String query = resolveSearchQuery(args, isDefault);
        if (QQMusicSupport.isBlank(query)) {
            return null;
        }
        return withBusy(new Operation<SearchPageObj>() {
            @Override
            public SearchPageObj run() throws Exception {
                List<QQMusicTrack> tracks = client.search(query);
                if (tracks.isEmpty()) {
                    return null;
                }
                List<SearchMusicObj> results = new ArrayList<>();
                for (QQMusicTrack track : tracks) {
                    results.add(new SearchMusicObj(
                            track.songMid,
                            defaultText(track.title, track.songMid),
                            defaultText(track.singer, "QQ Music"),
                            defaultText(track.album, "QQ Music")
                    ));
                }
                return new SearchPageObj(results, Math.max(1, (results.size() + 9) / 10), API_ID);
            }
        });
    }

    @Override
    public void setList(String id, Object sender) {
        // Playlist import is outside this provider's song search/playback contract.
    }

    @Override
    public LyricSave getLyric(String id) {
        final String songMid = normalizeSongMid(id);
        if (songMid == null) {
            return new LyricSave();
        }
        LyricSave result = withBusy(new Operation<LyricSave>() {
            @Override
            public LyricSave run() throws Exception {
                return QQMusicLyricParser.parse(client.getLyrics(songMid));
            }
        });
        if (result == null) {
            return new LyricSave();
        }
        if (!result.isHaveLyric()) {
            QQMusicSupport.logInfo("QQ Music returned no timed lyrics for " + songMid);
        }
        return result;
    }

    @Override
    public String getPlayUrl(String id) {
        final String songMid = normalizeSongMid(id);
        if (songMid == null) {
            return null;
        }
        return withBusy(new Operation<String>() {
            @Override
            public String run() throws Exception {
                QQMusicTrack track = client.getTrack(songMid);
                return track == null ? null : client.getPlayUrl(track);
            }
        });
    }

    @Override
    public boolean isBusy() {
        return requests.isBusy();
    }

    @Override
    public String getMusicId(String arg) {
        return normalizeSongMid(arg);
    }

    @Override
    public boolean checkId(String id) {
        return normalizeSongMid(id) != null;
    }

    static String normalizeSongMid(String input) {
        if (QQMusicSupport.isBlank(input)) {
            return null;
        }
        String value = input.trim();
        if (value.regionMatches(true, 0, "song:", 0, 5)) {
            value = value.substring(5).trim();
        }
        Matcher query = SONG_MID_QUERY.matcher(value);
        if (query.find()) {
            return query.group(1);
        }
        Matcher path = SONG_MID_PATH.matcher(value);
        if (path.find()) {
            return path.group(1);
        }
        return MID.matcher(value).matches() ? value : null;
    }

    static String resolveSearchQuery(String[] args, boolean isDefault) {
        if (args == null || args.length == 0) {
            return null;
        }
        int start = isDefault ? 0 : 1;
        if (start >= args.length) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (int index = start; index < args.length; index++) {
            if (!QQMusicSupport.isBlank(args[index])) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(args[index].trim());
            }
        }
        return result.length() == 0 ? null : result.toString();
    }

    private <T> T withBusy(final Operation<T> operation) {
        try {
            return requests.execute(new Callable<T>() {
                @Override
                public T call() throws Exception {
                    config.reloadIfChanged();
                    login.ensureFresh();
                    return operation.run();
                }
            });
        } catch (Exception e) {
            QQMusicSupport.logError("QQ Music request failed: " + e.getMessage());
            return null;
        }
    }

    private static String defaultText(String value, String fallback) {
        return QQMusicSupport.isBlank(value) ? fallback : value;
    }

    private interface Operation<T> {
        T run() throws Exception;
    }
}
