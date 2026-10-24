package com.byron.davmusic;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 歌词搜索（LRCLIB，https://lrclib.net）。
 *
 * 选它的理由：公开 API、免密钥、允许客户端调用，中文曲库实测可用
 * （「爱因为在心中」「夜曲」「后来」都能搜到带时间轴的版本）。
 *
 * 约束（与 AGENTS.md §3 的网络克制一致）：
 *  - 只在用户主动点「搜索歌词」时发一次请求，不预取、不自动重试；
 *  - 429 直接如实提示，不轮询、不退避重试；
 *  - 请求必须带能识别客户端的 User-Agent（服务方明确要求）。
 *
 * ⛔ 不要换成网易云/QQ 音乐的非官方接口：没有公开授权，风控与失效风险由本 App 承担。
 */
public class LyricsSearchClient {

    private static final String TAG = "LyricsSearch";
    private static final String ENDPOINT = "https://lrclib.net/api/search";
    private static final String USER_AGENT =
            "DavMusic/1.46 (https://github.com/ByronChengChen/davmusic)";

    private static OkHttpClient client;

    public interface Callback {
        void onSuccess(List<Result> results);

        /** message 已可直接展示给用户 */
        void onError(String message);
    }

    /** 一条搜索结果（只保留展示与下载需要的字段） */
    public static final class Result {
        public final long id;
        public final String trackName;
        public final String artistName;
        public final String albumName;
        public final int durationSeconds;
        public final String syncedLyrics;

        Result(long id, String trackName, String artistName, String albumName,
               int durationSeconds, String syncedLyrics) {
            this.id = id;
            this.trackName = trackName == null ? "" : trackName;
            this.artistName = artistName == null ? "" : artistName;
            this.albumName = albumName == null ? "" : albumName;
            this.durationSeconds = durationSeconds;
            this.syncedLyrics = syncedLyrics == null ? "" : syncedLyrics;
        }

        /** 列表里的一行：歌名 — 歌手 · 专辑 (3:41) */
        public String displayLine() {
            StringBuilder sb = new StringBuilder(trackName);
            if (!artistName.isEmpty()) sb.append(" — ").append(artistName);
            if (!albumName.isEmpty()) sb.append(" · ").append(albumName);
            if (durationSeconds > 0) {
                sb.append(" (").append(durationSeconds / 60).append(':')
                        .append(String.format("%02d", durationSeconds % 60)).append(')');
            }
            return sb.toString();
        }
    }

    private LyricsSearchClient() {
    }

    private static synchronized OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    /**
     * 搜索歌词。回调在后台线程，调用方自行切主线程。
     *
     * @param currentDurationSec 当前歌曲时长，用于把时长接近的版本排前面（0 = 未知）
     */
    public static void search(final String query, final int currentDurationSec, final Callback callback) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            callback.onError("请输入搜索关键词");
            return;
        }

        String url = ENDPOINT + "?q=" + android.net.Uri.encode(q);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build();

        client().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("搜索失败，请检查网络");
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    int code = response.code();
                    if (code == 429) {
                        callback.onError("搜索太频繁，请稍后再试");
                        return;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("搜索失败（HTTP " + code + "）");
                        return;
                    }
                    String json = response.body().string();
                    List<Result> results = sortByDuration(parseResults(json), currentDurationSec);
                    callback.onSuccess(results);
                } catch (Exception e) {
                    Log.w(TAG, "解析搜索结果失败: " + e);
                    callback.onError("搜索结果解析失败");
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * 解析 LRCLIB 的 /api/search 响应。
     *
     * 只保留**带时间轴**（syncedLyrics 非空）且不是纯音乐的条目：
     * 没有时间轴的歌词无法滚动，硬塞进滚动歌词页只会让人以为功能坏了。
     */
    public static List<Result> parseResults(String json) throws Exception {
        List<Result> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;

        // 出错时服务端返回对象而不是数组，直接抛出让上层统一提示
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (o.optBoolean("instrumental", false)) continue;

            String synced = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
            if (synced == null || synced.trim().isEmpty()) continue;

            out.add(new Result(
                    o.optLong("id", 0),
                    o.isNull("trackName") ? "" : o.optString("trackName", ""),
                    o.isNull("artistName") ? "" : o.optString("artistName", ""),
                    o.isNull("albumName") ? "" : o.optString("albumName", ""),
                    (int) Math.round(o.optDouble("duration", 0)),
                    synced));
        }
        return out;
    }

    /** 时长越接近当前歌曲越靠前；未知时长的排最后。排序稳定，不改变同分条的相对顺序 */
    public static List<Result> sortByDuration(List<Result> results, final int currentDurationSec) {
        if (results == null || results.isEmpty() || currentDurationSec <= 0) return results;
        Collections.sort(results, (a, b) -> {
            int da = a.durationSeconds > 0 ? Math.abs(a.durationSeconds - currentDurationSec) : Integer.MAX_VALUE;
            int db = b.durationSeconds > 0 ? Math.abs(b.durationSeconds - currentDurationSec) : Integer.MAX_VALUE;
            return Integer.compare(da, db);
        });
        return results;
    }
}
