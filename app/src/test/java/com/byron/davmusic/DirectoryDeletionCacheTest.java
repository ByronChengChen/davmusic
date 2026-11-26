package com.byron.davmusic;

import android.content.Context;
import java.io.File;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class DirectoryDeletionCacheTest {
    private LocalCacheManager cache;
    private Context context;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("davmusic_cache",0).edit().clear().commit();
        Field f=LocalCacheManager.class.getDeclaredField("instance"); f.setAccessible(true); f.set(null,null);
        cache=LocalCacheManager.getInstance(context);
    }
    private File download(String key) throws Exception {
        File f=cache.getDownloadDestination(key);
        java.nio.file.Files.write(f.toPath(),new byte[]{1,2,3});
        cache.markFileDownloaded(key,f); cache.indexOfflineFile(key,"song.mp3",3); return f;
    }
    @Test public void actualFileMapIndexAndOtherOwnersRemainConsistent() throws Exception {
        File a=download("a:album/a.mp3"), b=download("b:album/a.mp3"), child=download("a:album/sub/a.mp3");
        assertEquals(java.util.Arrays.asList("a:album/a.mp3"),cache.downloadedKeysInDirectory("a","/album"));
        assertEquals(DirectoryDeletion.Outcome.DELETED,cache.deleteDownloadedCopy("a:album/a.mp3"));
        assertFalse(a.exists()); assertNull(cache.getLocalFile("a:album/a.mp3"));
        assertFalse(context.getSharedPreferences("davmusic_cache",0).getString("offline_index","").contains("a:album/a.mp3"));
        assertTrue(b.exists()); assertTrue(child.exists());
        Field f=LocalCacheManager.class.getDeclaredField("instance"); f.setAccessible(true); f.set(null,null);
        assertFalse(LocalCacheManager.getInstance(context).isDownloaded("a:album/a.mp3"));
    }
    @Test public void failedDeletePreservesMapAndIndex() throws Exception {
        File target=cache.getDownloadDestination("a:bad.mp3"); target.delete(); assertTrue(target.mkdir());
        cache.markFileDownloaded("a:bad.mp3",target); cache.indexOfflineFile("a:bad.mp3","bad.mp3",0);
        assertEquals(DirectoryDeletion.Outcome.FAILED,cache.deleteDownloadedCopy("a:bad.mp3"));
        assertEquals(target,cache.getLocalFile("a:bad.mp3"));
        assertTrue(context.getSharedPreferences("davmusic_cache",0).getString("offline_index","").contains("a:bad.mp3"));
        target.delete();
    }
    @Test public void missingFileCleansStaleRecordsAndNotifies() throws Exception {
        File a=download("a:missing.mp3"); a.delete(); final int[] events={0}; cache.addDownloadListener(()->events[0]++);
        assertEquals(DirectoryDeletion.Outcome.MISSING,cache.deleteDownloadedCopy("a:missing.mp3"));
        assertNull(cache.getLocalFile("a:missing.mp3")); assertEquals(1,events[0]);
    }
    @Test public void unexpectedTargetCannotDeleteOtherFile() throws Exception {
        File a=download("a:other.mp3"); cache.markFileDownloaded("b:other.mp3",a);
        assertEquals(DirectoryDeletion.Outcome.FAILED,cache.deleteDownloadedCopy("b:other.mp3")); assertTrue(a.exists());
    }
}
