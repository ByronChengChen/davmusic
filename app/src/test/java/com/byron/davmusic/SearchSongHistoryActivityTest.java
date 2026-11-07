package com.byron.davmusic;

import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class SearchSongHistoryActivityTest {
    private SearchActivity activity;
    private MusicPlayer player;
    private ServerProfile server;
    private WebDAVFile a,b;
    private SearchSongHistoryStore store;
    private RecyclerView list;
    @Before public void setup() throws Exception {
        Field singleton=MusicPlayer.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null,null);
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider(source -> new org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(180000, 0));
        android.app.Application app = RuntimeEnvironment.getApplication();
        ServerStore.prefs(app).edit().clear().commit();
        server = new ServerProfile("history-owner","History","https://example.invalid/dav/","user","password");
        ServerStore.add(app,server);
        store = new SearchSongHistoryStore(new File(app.getFilesDir(),"search-song-history.bin")); store.clear();
        a=SearchSongHistoryChecks.song(server.getId(),"one.mp3"); b=SearchSongHistoryChecks.song(server.getId(),"two.mp3");
        store.record(a,server); store.record(b,server);
        player=MusicPlayer.getInstance(app);
        Field media=MusicPlayer.class.getDeclaredField("mediaPlayer"); media.setAccessible(true); media.set(player,null);
        player.setPlaylist(Arrays.asList(a,b),0);
        activity=Robolectric.buildActivity(SearchActivity.class).create().get();
        list=activity.findViewById(R.id.searchResults);
    }
    @After public void cleanup() { if(activity!=null) activity.onDestroy(); if(player!=null) player.release(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private View control(String name) { int id=activity.getResources().getIdentifier(name,"id",activity.getPackageName()); assertTrue("control "+name,id!=0); return activity.findViewById(id); }
    @SuppressWarnings({"rawtypes","unchecked"}) private RecyclerView.ViewHolder row(int position) {
        RecyclerView.Adapter adapter=list.getAdapter(); RecyclerView.ViewHolder h=adapter.createViewHolder(list,adapter.getItemViewType(position)); adapter.bindViewHolder(h,position); return h;
    }
    private void assertQueueUnchanged() { assertEquals(2,player.getPlaylist().size()); assertEquals(a.getCacheKey(),player.getCurrentTrack().getCacheKey()); }
    @Test public void emptyQueryShowsRecentSongWidgetsAndNotKeywordHistory() {
        assertEquals(2,list.getAdapter().getItemCount()); assertEquals(View.VISIBLE,control("searchHistoryClear").getVisibility());
        assertQueueUnchanged();
    }
    @Test public void deletingRowAndClearingHistoryNeverChangesQueueOrDownloads() throws Exception {
        File local=File.createTempFile("history-audio",".mp3",activity.getCacheDir()); LocalCacheManager cache=LocalCacheManager.getInstance(activity); cache.markFileDownloaded(b.getCacheKey(),local); idle();
        int remove=activity.getResources().getIdentifier("searchHistoryDelete","id",activity.getPackageName()); assertTrue(remove!=0);
        assertTrue(row(0).itemView.findViewById(remove).performClick()); assertEquals(1,list.getAdapter().getItemCount()); assertQueueUnchanged();
        assertTrue(control("searchHistoryClear").performClick()); assertEquals(0,list.getAdapter().getItemCount()); assertQueueUnchanged();
        assertTrue(local.exists()); assertTrue(cache.isDownloaded(b.getCacheKey()));
        assertTrue(new SearchSongHistoryStore(new File(activity.getFilesDir(),"search-song-history.bin")).list(Collections.singletonList(server)).isEmpty());
    }
    @Test public void typingAndClearingQueryDoesNotRecordOrMutateQueue() {
        ((EditText)activity.findViewById(R.id.searchQuery)).setText("never clicked");
        assertEquals(View.GONE,control("searchHistoryClear").getVisibility()); assertQueueUnchanged();
        activity.findViewById(R.id.searchClear).performClick(); idle();
        assertEquals(2,list.getAdapter().getItemCount()); assertEquals(2,store.list(Collections.singletonList(server)).size()); assertQueueUnchanged();
    }
    @Test public void historyClickUsesOnlyOneSongAndMovesItToFront() throws Exception {
        // Local copy avoids any remote request; unsupported platform decode stays on existing error path.
        File local=File.createTempFile("history-play",".mp3",activity.getCacheDir());
        LocalCacheManager.getInstance(activity).markFileDownloaded(a.getCacheKey(),local); idle();
        row(1).itemView.performClick();
        assertEquals(1,player.getPlaylist().size()); assertEquals(a.getCacheKey(),player.getCurrentTrack().getCacheKey());
        assertEquals(a.getCacheKey(),store.list(Collections.singletonList(server)).get(0).getCacheKey());
    }
    @Test public void deletedServerStaleRowCannotPlayAnotherServer() {
        RecyclerView.ViewHolder stale=row(0); ServerStore.remove(activity,server.getId());
        stale.itemView.performClick(); assertQueueUnchanged(); idle(); assertEquals(0,list.getAdapter().getItemCount());
    }
    @Test public void changedAccountStaleRowCannotUseOldIdentity() {
        RecyclerView.ViewHolder stale=row(0); server.setUsername("new-account"); ServerStore.update(activity,server);
        stale.itemView.performClick(); assertQueueUnchanged(); idle(); assertEquals(0,list.getAdapter().getItemCount());
    }
    @Test public void lateSearchDeliveryDoesNotReplaceHistory() throws Exception {
        Field field=SearchActivity.class.getDeclaredField("controller"); field.setAccessible(true);
        SnapshotSearchController controller=(SnapshotSearchController)field.get(activity);
        // Actual controller worker queues delivery; switching to empty invalidates its ticket.
        ((EditText)activity.findViewById(R.id.searchQuery)).setText("one");
        activity.findViewById(R.id.searchClear).performClick();
        Field wf=SearchActivity.class.getDeclaredField("worker"); wf.setAccessible(true);
        ((java.util.concurrent.ExecutorService)wf.get(activity)).submit(() -> {}).get(5,java.util.concurrent.TimeUnit.SECONDS);
        idle(); assertEquals(2,list.getAdapter().getItemCount()); assertQueueUnchanged();
    }
    @Test public void recreationRetainsHistoryWithoutChangingQueue() {
        android.os.Bundle state=new android.os.Bundle(); activity.onSaveInstanceState(state);
        activity.onDestroy();
        activity=Robolectric.buildActivity(SearchActivity.class).create(state).get();
        list=activity.findViewById(R.id.searchResults);
        assertEquals(2,list.getAdapter().getItemCount()); assertQueueUnchanged();
    }
    @Test public void deletingHistoryDoesNotResetActiveAudioInstancePositionOrPlayState() throws Exception {
        File local=File.createTempFile("history-active",".mp3",activity.getCacheDir());
        LocalCacheManager.getInstance(activity).markFileDownloaded(a.getCacheKey(),local); idle();
        row(1).itemView.performClick(); idle();
        Field mediaField=MusicPlayer.class.getDeclaredField("mediaPlayer"); mediaField.setAccessible(true);
        android.media.MediaPlayer media=(android.media.MediaPlayer)mediaField.get(player);
        assertNotNull(media); media.seekTo(15000); idle();
        int position=player.getCurrentPosition(); boolean playing=player.isPlaying();
        int delete=activity.getResources().getIdentifier("searchHistoryDelete","id",activity.getPackageName());
        row(0).itemView.findViewById(delete).performClick(); control("searchHistoryClear").performClick();
        assertSame(media,mediaField.get(player)); assertEquals(position,player.getCurrentPosition()); assertEquals(playing,player.isPlaying());
        assertEquals(1,player.getPlaylist().size()); assertEquals(a.getCacheKey(),player.getCurrentTrack().getCacheKey());
    }
    @Test public void searchResultClickStillCapturesFullDisplayedQueue() throws Exception {
        ((EditText)activity.findViewById(R.id.searchQuery)).setText("one");
        Field af=SearchActivity.class.getDeclaredField("adapter"); af.setAccessible(true); Object adapter=af.get(activity);
        Method set=adapter.getClass().getDeclaredMethod("setFiles",List.class); set.setAccessible(true); set.invoke(adapter,Arrays.asList(a,b));
        File local=File.createTempFile("search-play",".mp3",activity.getCacheDir()); LocalCacheManager.getInstance(activity).markFileDownloaded(b.getCacheKey(),local);
        row(1).itemView.performClick(); assertEquals(2,player.getPlaylist().size()); assertEquals(b.getCacheKey(),player.getCurrentTrack().getCacheKey());
        assertEquals(b.getCacheKey(),store.list(Collections.singletonList(server)).get(0).getCacheKey());
    }
}
