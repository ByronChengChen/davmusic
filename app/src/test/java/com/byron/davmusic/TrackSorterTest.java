package com.byron.davmusic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 排序规则的用例表（纯 JVM）。
 *
 * 这些规则是 v1.42 的「唯一排序入口」：列表和播放队列共用同一个比较器。
 * 比较器写错了不会崩，只会让列表看起来乱、或「下一首」跳到屏幕上不相邻的歌，
 * 所以每条行为都钉住。
 */
public class TrackSorterTest {

    private static WebDAVFile item(String name, long time, String rel, boolean dir) {
        WebDAVFile f = new WebDAVFile();
        f.setDisplayName(name);
        f.setLastModified(time);
        f.setRelativePath(rel == null ? name : rel);
        f.setCollection(dir);
        return f;
    }

    private static List<String> names(List<WebDAVFile> files) {
        List<String> out = new ArrayList<>();
        for (WebDAVFile f : files) out.add(f.getDisplayName());
        return out;
    }

    // ---------- 名称比较：自然序 + 中文 ----------

    @Test
    public void naturalOrderComparesNumbersByValue() {
        assertTrue(TrackSorter.compareNames("Track 2", "Track 10") < 0);
        assertTrue(TrackSorter.compareNames("第2首", "第10首") < 0);
        assertTrue(TrackSorter.compareNames("Track 10", "Track 9") > 0);
    }

    @Test
    public void equalNumbersAreDecidedByFollowingTextNotDigitWidth() {
        // "01 - a" 与 "1 - b"：数值相同，必须由后面的 "- a" / "- b" 决定，
        // 不能让补零的位数决定先后
        assertTrue(TrackSorter.compareNames("01 - a", "1 - b") < 0);
        assertTrue(TrackSorter.compareNames("1 - b", "01 - a") > 0);
    }

    @Test
    public void compareNamesIsCaseInsensitiveAtPrimaryStrength() {
        assertTrue(TrackSorter.compareNames("abc", "ABC") == 0);
    }

    @Test
    public void compareNamesHandlesNullAsEmpty() {
        assertTrue(TrackSorter.compareNames(null, "a") < 0);
        assertEquals(0, TrackSorter.compareNames(null, null));
    }

    // ---------- 时间模式 ----------

    @Test
    public void timeModeSortsNewestFirst() {
        List<WebDAVFile> files = new ArrayList<>();
        files.add(item("old.mp3", 1000, "old.mp3", false));
        files.add(item("new.mp3", 3000, "new.mp3", false));
        files.add(item("mid.mp3", 2000, "mid.mp3", false));

        Collections.sort(files, TrackSorter.comparator(TrackSorter.MODE_TIME));

        assertEquals(java.util.Arrays.asList("new.mp3", "mid.mp3", "old.mp3"), names(files));
    }

    @Test
    public void unknownTimeSinksToBottomInsteadOfLookingOldest() {
        // 服务器不返回时间时是 0；若按 0 排，它会冒充「最旧」沉底，
        // 其实用户期望是「不知道排哪就放最后」，这两种在降序下恰好都是最后，
        // 但在部分升序场景会反；这里钉住实现：未知时间永远在已知时间之后
        List<WebDAVFile> files = new ArrayList<>();
        files.add(item("unknown.mp3", 0, "unknown.mp3", false));
        files.add(item("known.mp3", 1000, "known.mp3", false));

        Collections.sort(files, TrackSorter.comparator(TrackSorter.MODE_TIME));

        assertEquals(java.util.Arrays.asList("known.mp3", "unknown.mp3"), names(files));
    }

    @Test
    public void foldersStayPinnedFirstEvenWhenOlder() {
        List<WebDAVFile> files = new ArrayList<>();
        files.add(item("song.mp3", 9000, "song.mp3", false));
        files.add(item("folder", 1, "folder", true));

        Collections.sort(files, TrackSorter.comparator(TrackSorter.MODE_TIME));

        assertEquals(java.util.Arrays.asList("folder", "song.mp3"), names(files));
    }

    @Test
    public void foldersAreOrderedByTheActiveRuleInsideTheirGroup() {
        List<WebDAVFile> files = new ArrayList<>();
        files.add(item("a-folder", 1000, "a-folder", true));
        files.add(item("b-folder", 5000, "b-folder", true));

        Collections.sort(files, TrackSorter.comparator(TrackSorter.MODE_TIME));

        assertEquals(java.util.Arrays.asList("b-folder", "a-folder"), names(files));
    }

    @Test
    public void equalTimestampsFallBackToNameThenPathThenServer() {
        WebDAVFile a = item("same.mp3", 1000, "dir-b/same.mp3", false);
        WebDAVFile b = item("same.mp3", 1000, "dir-a/same.mp3", false);
        b.setServerId("s1");
        a.setServerId("s2");

        Comparator<WebDAVFile> c = TrackSorter.comparator(TrackSorter.MODE_TIME);
        // 同名同时间 → 按 relativePath，"dir-a" 在前
        assertTrue(c.compare(b, a) < 0);
        assertTrue(c.compare(a, b) > 0);

        // relativePath 也相同时按 serverId 定序，保证两次刷新顺序稳定
        WebDAVFile x = item("same.mp3", 1000, "p/same.mp3", false);
        WebDAVFile y = item("same.mp3", 1000, "p/same.mp3", false);
        x.setServerId("a");
        y.setServerId("b");
        assertTrue(c.compare(x, y) < 0);
    }

    // ---------- 名称模式 ----------

    @Test
    public void nameModeIgnoresTime() {
        List<WebDAVFile> files = new ArrayList<>();
        files.add(item("b.mp3", 9000, "b.mp3", false));
        files.add(item("a.mp3", 1000, "a.mp3", false));

        Collections.sort(files, TrackSorter.comparator(TrackSorter.MODE_NAME));

        assertEquals(java.util.Arrays.asList("a.mp3", "b.mp3"), names(files));
    }

    @Test
    public void nullElementsDoNotBreakTheComparator() {
        Comparator<WebDAVFile> c = TrackSorter.comparator(TrackSorter.MODE_TIME);
        WebDAVFile a = item("a.mp3", 1000, "a.mp3", false);
        assertTrue(c.compare(null, a) > 0);
        assertTrue(c.compare(a, null) < 0);
        assertEquals(0, c.compare(null, null));
    }
}
