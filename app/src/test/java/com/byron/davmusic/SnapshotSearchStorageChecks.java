package com.byron.davmusic;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Shared by Robolectric and the SDK-free host runner (real filesystem). */
public final class SnapshotSearchStorageChecks {
    public static void verify(LocalCacheManager cache) throws Exception {
        cache.clearSnapshots();
        long before = cache.getSnapshotRevision();
        WebDAVFile a = SnapshotSearchEngineTest.song("a", "album/Song.mp3");
        cache.saveSnapshot("a:album", Arrays.asList(a));
        assertTrue("publication invalidates memory", cache.getSnapshotRevision() > before);
        assertEquals(1, cache.loadSearchSnapshots().size());
        cache.saveSnapshot("a:album", Collections.emptyList());
        assertTrue("empty refresh replaces old songs", cache.loadSearchSnapshots().isEmpty());
        cache.saveSnapshot("a:album", Arrays.asList(a));
        java.lang.reflect.Method method = LocalCacheManager.class.getDeclaredMethod("getSnapshotFile", String.class);
        method.setAccessible(true);
        File corrupt = (File) method.invoke(cache, "bad");
        Files.write(corrupt.toPath(), "broken JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("one corrupt snapshot must not abort all", 1, cache.loadSearchSnapshots().size());
        long epoch = cache.getSnapshotEpoch();
        cache.clearAllCache();
        cache.saveSnapshotIfCurrent("a:album", Arrays.asList(a), epoch);
        assertTrue("old network response must not resurrect cleared data", cache.loadSearchSnapshots().isEmpty());
        cache.saveSnapshotIfCurrent("a:album", Arrays.asList(a), cache.getSnapshotEpoch());
        assertEquals(1, cache.loadSearchSnapshots().size());
        cache.deleteSnapshot("a:album");
        concurrentClearAndPublication(cache, a);
        cache.clearSnapshots();
        assertTrue(cache.loadSearchSnapshots().isEmpty());
    }

    private static void concurrentClearAndPublication(LocalCacheManager cache, WebDAVFile song) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        long oldEpoch = cache.getSnapshotEpoch();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 80; i++) cache.saveSnapshotIfCurrent("a:album", Arrays.asList(song), oldEpoch);
            } catch (Throwable e) { failure.set(e); }
        });
        Thread clearer = new Thread(() -> {
            try { cache.clearSnapshots(); } catch (Throwable e) { failure.set(e); }
        });
        writer.start(); clearer.start(); writer.join(); clearer.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue("clear must dominate all writers from the prior epoch", cache.loadSearchSnapshots().isEmpty());
    }
}
