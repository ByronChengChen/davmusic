package com.byron.davmusic;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class DirectoryDeletionPlaybackTest {
    @Test public void protectsCurrentPreparingMountedAndPendingWithoutMutation() throws Exception {
        MusicPlayer player=MusicPlayer.getInstance(RuntimeEnvironment.getApplication());
        Field playlist=MusicPlayer.class.getDeclaredField("playlist"); playlist.setAccessible(true);
        Object previous=playlist.get(player);
        List<WebDAVFile> tracks=new ArrayList<>();
        for(int i=0;i<3;i++) tracks.add(DirectoryDownloadTaskTest.song("owner",i+".mp3"));
        Map<String,Object> saved=new HashMap<>();
        String[] names={"currentPosition","preloadedPosition","preparingPosition","pendingReleaseCacheKey"};
        try {
            playlist.set(player,tracks);
            Object[] values={0,1,2,"other:pending.mp3"};
            for(int i=0;i<names.length;i++) { Field f=MusicPlayer.class.getDeclaredField(names[i]); f.setAccessible(true); saved.put(names[i],f.get(player)); f.set(player,values[i]); }
            Set<String> keys=player.localDeletionProtectedKeys();
            for(WebDAVFile f:tracks) assertTrue(keys.contains(f.getCacheKey()));
            assertTrue(keys.contains("other:pending.mp3")); assertFalse(keys.contains("another:0.mp3"));
            Field inflight=MusicPlayer.class.getDeclaredField("preparingCacheKeys");inflight.setAccessible(true);
            @SuppressWarnings("unchecked") Map<Integer,String> preparing=(Map<Integer,String>)inflight.get(player);
            preparing.put(-999,"cancelled:still-preparing.mp3");
            try { assertTrue(player.localDeletionProtectedKeys().contains("cancelled:still-preparing.mp3")); }
            finally { preparing.remove(-999); }
            assertSame(tracks,playlist.get(player));
        } finally {
            playlist.set(player,previous);
            for(String name:saved.keySet()) { Field f=MusicPlayer.class.getDeclaredField(name);f.setAccessible(true); f.set(player,saved.get(name)); }
        }
    }
    @Test public void activeTransferFenceSurvivesTaskCancellation() throws Exception {
        Field f=CachedTrackDownloader.class.getDeclaredField("activeKeys");f.setAccessible(true);
        @SuppressWarnings("unchecked") Set<String> active=(Set<String>)f.get(null);
        try { active.add("owner:a.mp3"); assertTrue(CachedTrackDownloader.hasActiveDownloads()); }
        finally { active.remove("owner:a.mp3"); }
        assertFalse(CachedTrackDownloader.hasActiveDownloads());
    }
}
