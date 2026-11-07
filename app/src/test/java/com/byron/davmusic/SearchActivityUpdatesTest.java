package com.byron.davmusic;

import android.os.Looper;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SearchActivityUpdatesTest {
    private SearchActivity activity;
    private RecyclerView.Adapter<?> adapter;
    private final List<Integer> changed = new ArrayList<>();
    private int blanket;
    private WebDAVFile a, b, other;
    private static WebDAVFile file(String owner, String path) {
        WebDAVFile f = new WebDAVFile(); f.setServerId(owner); f.setRelativePath(path); f.setDisplayName("song.mp3"); return f;
    }
    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(SearchActivity.class).create().get();
        Field af = SearchActivity.class.getDeclaredField("adapter"); af.setAccessible(true);
        adapter = (RecyclerView.Adapter<?>) af.get(activity);
        a = file("a", "song.mp3"); b = file("b", "song.mp3"); other = file("c", "other.mp3");
        Field files = adapter.getClass().getDeclaredField("files"); files.setAccessible(true);
        files.set(adapter, Arrays.asList(a, b, other));
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            public void onChanged() { blanket++; }
            public void onItemRangeChanged(int start, int count, Object payload) {
                assertNotNull("state updates must use non-animating payloads", payload);
                for (int i = start; i < start + count; i++) changed.add(i);
            }
            public void onItemRangeChanged(int start, int count) { blanket++; }
        });
    }
    @After public void cleanup() { activity.onDestroy(); }
    @Test public void progressNeverInvalidatesResults() {
        for (int i = 0; i < 120; i++) activity.onProgress(i, 180);
        assertEquals(0, blanket); assertTrue(changed.isEmpty());
    }
    @Test public void pauseResumeNeverInvalidatesResults() {
        activity.onPlayStateChanged(true); activity.onPlayStateChanged(false);
        assertEquals(0, blanket); assertTrue(changed.isEmpty());
    }
    @Test public void trackChangesOnlyUpdateOldAndNewServerOwnedRows() {
        activity.onTrackChanged(a); changed.clear();
        activity.onTrackChanged(b);
        assertEquals(Arrays.asList(0, 1), changed); assertEquals(0, blanket);
        changed.clear(); activity.onTrackChanged(b); assertTrue(changed.isEmpty());
        activity.onTrackChanged(null); assertEquals(Collections.singletonList(1), changed);
    }
    @Test public void downloadCompletionAndRemovalOnlyUpdateChangedRow() throws Exception {
        LocalCacheManager cache = LocalCacheManager.getInstance(activity);
        File local = File.createTempFile("search-download", ".mp3", activity.getCacheDir());
        cache.markFileDownloaded(b.getCacheKey(), local);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList(1), changed); assertEquals(0, blanket);
        changed.clear(); cache.markFileDownloaded(b.getCacheKey(), local);
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertTrue(changed.isEmpty());
        cache.removeDownloadedFile(b.getCacheKey());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList(1), changed); assertEquals(0, blanket);
    }
}
