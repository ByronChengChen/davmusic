package com.byron.davmusic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * LRCLIB 响应解析（Robolectric：需要 org.json 的真实实现）。
 *
 * 过滤规则是产品决定：只接受**带时间轴**的版本 —— 纯文本歌词塞进滚动页
 * 会让人以为功能坏了，不如在搜索阶段就不给选。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LyricsSearchClientTest {

    private static final String SYNCED = "[00:01.00]第一句\n[00:05.00]第二句";

    @Test
    public void parsesSyncedResults() throws Exception {
        String json = "[{\"id\":1,\"trackName\":\"夜曲\",\"artistName\":\"周杰伦\","
                + "\"albumName\":\"十一月的萧邦\",\"duration\":229.4,\"instrumental\":false,"
                + "\"plainLyrics\":\"第一句\",\"syncedLyrics\":" + quote(SYNCED) + "}]";

        List<LyricsSearchClient.Result> out = LyricsSearchClient.parseResults(json);

        assertEquals(1, out.size());
        LyricsSearchClient.Result r = out.get(0);
        assertEquals(1, r.id);
        assertEquals("夜曲", r.trackName);
        assertEquals("周杰伦", r.artistName);
        assertEquals("十一月的萧邦", r.albumName);
        assertEquals(229, r.durationSeconds);      // 229.4 四舍五入
        assertEquals(SYNCED, r.syncedLyrics);
    }

    @Test
    public void skipsResultsWithoutSyncedLyrics() throws Exception {
        // 只有纯文本 → 不能滚动，直接过滤
        String json = "[{\"id\":1,\"trackName\":\"A\",\"artistName\":\"B\","
                + "\"instrumental\":false,\"plainLyrics\":\"没有时间轴\",\"syncedLyrics\":null}]";

        assertTrue(LyricsSearchClient.parseResults(json).isEmpty());
    }

    @Test
    public void skipsInstrumentalTracks() throws Exception {
        String json = "[{\"id\":1,\"trackName\":\"A\",\"instrumental\":true,"
                + "\"syncedLyrics\":" + quote(SYNCED) + "}]";

        assertTrue(LyricsSearchClient.parseResults(json).isEmpty());
    }

    @Test
    public void emptyArrayYieldsEmptyList() throws Exception {
        assertTrue(LyricsSearchClient.parseResults("[]").isEmpty());
        assertTrue(LyricsSearchClient.parseResults("").isEmpty());
        assertTrue(LyricsSearchClient.parseResults(null).isEmpty());
    }

    @Test(expected = Exception.class)
    public void errorObjectIsRejected() throws Exception {
        // 限流/出错时服务端返回对象而不是数组，调用方统一按失败提示
        LyricsSearchClient.parseResults("{\"message\":\"Too many requests\"}");
    }

    @Test
    public void displayLineShowsNameArtistAlbumAndDuration() throws Exception {
        String json = "[{\"id\":1,\"trackName\":\"夜曲\",\"artistName\":\"周杰伦\","
                + "\"albumName\":\"十一月的萧邦\",\"duration\":229,\"instrumental\":false,"
                + "\"syncedLyrics\":" + quote(SYNCED) + "}]";

        assertEquals("夜曲 — 周杰伦 · 十一月的萧邦 (3:49)",
                LyricsSearchClient.parseResults(json).get(0).displayLine());
    }

    @Test
    public void sortsByDurationClosestFirstAndUnknownLast() {
        List<LyricsSearchClient.Result> in = new ArrayList<>();
        in.add(result(300));
        in.add(result(0));
        in.add(result(240));

        LyricsSearchClient.sortByDuration(in, 241);

        assertEquals(240, in.get(0).durationSeconds);
        assertEquals(300, in.get(1).durationSeconds);
        assertEquals(0, in.get(2).durationSeconds);
    }

    @Test
    public void durationSortIsNoOpWhenCurrentDurationUnknown() {
        List<LyricsSearchClient.Result> in = new ArrayList<>();
        in.add(result(300));
        in.add(result(240));

        LyricsSearchClient.sortByDuration(in, 0);

        assertEquals(300, in.get(0).durationSeconds);
    }

    /** 构造 Result 的小工具（构造器包内可见，测试同包） */
    private static LyricsSearchClient.Result result(int duration) {
        return new LyricsSearchClient.Result(0, "t", "a", "b", duration, "[00:01.00]x");
    }

    private static String quote(String s) {
        return org.json.JSONObject.quote(s);
    }
}
