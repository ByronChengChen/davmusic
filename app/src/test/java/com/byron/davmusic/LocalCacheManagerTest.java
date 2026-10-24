package com.byron.davmusic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录快照的读写（Robolectric：需要 Context 与文件目录）。
 *
 * 快照是离线列表与「扫完再浏览零请求」的唯一数据来源，丢失或字段缺失
 * 表现为「离线进来目录空了」「中文路径点进去 404」，所以往返保真必须钉住。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalCacheManagerTest {

    private static final String FOLDER = "/cmcc/music/欧美";

    private LocalCacheManager cache;

    @Before
    public void setUp() {
        Context ctx = RuntimeEnvironment.getApplication();
        cache = LocalCacheManager.getInstance(ctx);
        cache.clearSnapshots();
    }

    private static WebDAVFile audio(String name, long size) {
        WebDAVFile f = new WebDAVFile();
        f.setDisplayName(name);
        f.setRelativePath("cmcc/music/欧美/" + name);
        f.setContentLength(size);
        f.setContentType("audio/mpeg");
        f.setLastModified(1767261600000L);
        f.setEtag("\"etag-1\"");
        f.setCollection(false);
        f.setServerId("srv-1");
        f.setServerName("我的云盘");
        return f;
    }

    private static WebDAVFile folder(String name) {
        WebDAVFile f = new WebDAVFile();
        f.setDisplayName(name);
        f.setRelativePath("cmcc/music/欧美/" + name);
        f.setCollection(true);
        f.setServerId("srv-1");
        f.setServerName("我的云盘");
        return f;
    }

    @Test
    public void roundTripKeepsEveryField() {
        List<WebDAVFile> saved = new ArrayList<>();
        saved.add(folder("子目录"));
        saved.add(audio("Track 2.mp3", 5242880L));

        cache.saveSnapshot(FOLDER, saved);

        assertTrue(cache.hasSnapshot(FOLDER));
        List<WebDAVFile> loaded = cache.loadSnapshot(FOLDER);
        assertNotNull(loaded);
        assertEquals(2, loaded.size());

        WebDAVFile dir = loaded.get(0);
        assertEquals("子目录", dir.getDisplayName());
        assertEquals("cmcc/music/欧美/子目录", dir.getRelativePath());
        assertTrue(dir.isCollection());
        assertEquals("srv-1", dir.getServerId());
        assertEquals("我的云盘", dir.getServerName());

        WebDAVFile song = loaded.get(1);
        assertEquals("Track 2.mp3", song.getDisplayName());
        assertEquals("cmcc/music/欧美/Track 2.mp3", song.getRelativePath());
        assertEquals(5242880L, song.getContentLength());
        assertEquals("audio/mpeg", song.getContentType());
        assertEquals(1767261600000L, song.getLastModified());
        assertEquals("\"etag-1\"", song.getEtag());
        assertFalse(song.isCollection());
        // 多服务器下快照恢复后仍必须知道自己属于哪台服务器
        assertEquals("srv-1:cmcc/music/欧美/Track 2.mp3", song.getCacheKey());
    }

    @Test
    public void missingSnapshotReturnsNull() {
        assertFalse(cache.hasSnapshot("/没有这个目录"));
        assertNull(cache.loadSnapshot("/没有这个目录"));
    }

    @Test
    public void savingAgainReplacesPreviousSnapshot() {
        List<WebDAVFile> first = new ArrayList<>();
        first.add(audio("a.mp3", 1));
        first.add(audio("b.mp3", 2));
        cache.saveSnapshot(FOLDER, first);

        List<WebDAVFile> second = new ArrayList<>();
        second.add(audio("c.mp3", 3));
        cache.saveSnapshot(FOLDER, second);

        List<WebDAVFile> loaded = cache.loadSnapshot(FOLDER);
        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        assertEquals("c.mp3", loaded.get(0).getDisplayName());
    }

    @Test
    public void emptyFolderIsPersistedAndDistinguishableFromMissing() {
        // 空目录也必须留快照：否则「进入过的空目录」每次都要重新请求
        cache.saveSnapshot(FOLDER, new ArrayList<>());

        assertTrue(cache.hasSnapshot(FOLDER));
        List<WebDAVFile> loaded = cache.loadSnapshot(FOLDER);
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void deleteSnapshotRemovesFile() {
        List<WebDAVFile> files = new ArrayList<>();
        files.add(audio("a.mp3", 1));
        cache.saveSnapshot(FOLDER, files);

        cache.deleteSnapshot(FOLDER);

        assertFalse(cache.hasSnapshot(FOLDER));
        assertNull(cache.loadSnapshot(FOLDER));
    }

    @Test
    public void corruptedSnapshotReturnsNullInsteadOfCrashing() {
        // 扫描期间被系统杀死/断电可能留下半截 JSON；读取必须降级为「无快照」
        List<WebDAVFile> files = new ArrayList<>();
        files.add(audio("a.mp3", 1));
        cache.saveSnapshot(FOLDER, files);

        // 用被测类自己的路径解析，而不是手拼 filesDir/snapshots：
        // LocalCacheManager 是单例，可能持有更早测试方法的 Application 上下文，
        // 两者目录不一定相同
        File snapshotFile;
        try {
            java.lang.reflect.Method m =
                    LocalCacheManager.class.getDeclaredMethod("getSnapshotFile", String.class);
            m.setAccessible(true);
            snapshotFile = (File) m.invoke(cache, FOLDER);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(snapshotFile.exists());

        try (FileOutputStream fos = new FileOutputStream(snapshotFile)) {
            fos.write("{\"files\":[{\"displayName\":\"半截".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertNull(cache.loadSnapshot(FOLDER));
    }

    @Test
    public void differentFoldersHaveIndependentSnapshots() {
        List<WebDAVFile> a = new ArrayList<>();
        a.add(audio("a.mp3", 1));
        List<WebDAVFile> b = new ArrayList<>();
        b.add(audio("b.mp3", 2));

        cache.saveSnapshot("/cmcc/music/欧美", a);
        cache.saveSnapshot("/cmcc/music/国语", b);

        assertEquals("a.mp3", cache.loadSnapshot("/cmcc/music/欧美").get(0).getDisplayName());
        assertEquals("b.mp3", cache.loadSnapshot("/cmcc/music/国语").get(0).getDisplayName());

        cache.deleteSnapshot("/cmcc/music/欧美");
        assertTrue(cache.hasSnapshot("/cmcc/music/国语"));
    }
}
