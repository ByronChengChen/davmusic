package com.byron.davmusic;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class SearchSongHistoryStoreTest {
    private File dir, target;
    private SearchSongHistoryStore store;
    private ServerProfile a, b;
    @Before public void setup() throws Exception {
        dir = Files.createTempDirectory("history-store").toFile(); target = new File(dir, "history");
        store = new SearchSongHistoryStore(target);
        a = new ServerProfile("a", "A", "https://a.invalid/dav/", "user", "password");
        b = new ServerProfile("b", "B", "https://b.invalid/dav/", "user", "password");
    }
    @After public void cleanup() { for (File f : dir.listFiles()) f.delete(); dir.delete(); }
    private List<WebDAVFile> list() { return store.list(Arrays.asList(a,b)); }
    private void record(String id, String path) { assertTrue(store.record(SearchSongHistoryChecks.song(id,path), id.equals("a") ? a : b)); }
    @Test public void restartRetainsOnlySafeIdentity() throws Exception { SearchSongHistoryChecks.restart(dir); }
    @Test public void repeatedClickMovesOnlyThatIdentityToFront() {
        record("a","one.mp3"); record("a","two.mp3"); record("a","one.mp3");
        assertEquals(2,list().size()); assertEquals("a:one.mp3",list().get(0).getCacheKey());
    }
    @Test public void newestFiftySurviveRestart() {
        for(int i=0;i<55;i++) record("a",i+".mp3");
        store = new SearchSongHistoryStore(target);
        assertEquals(50,list().size()); assertEquals("54.mp3",list().get(0).getRelativePath());
        assertEquals("5.mp3",list().get(49).getRelativePath());
    }
    @Test public void samePathDifferentServersNeverCollapsesOrDeletesSibling() {
        record("a","one.mp3"); record("b","one.mp3"); assertEquals(2,list().size());
        assertTrue(store.remove(list().get(0))); assertEquals("a",list().get(0).getServerId());
    }
    @Test public void removedIncompleteOrChangedAccountIsFiltered() {
        record("a","one.mp3"); assertTrue(store.list(Collections.singletonList(b)).isEmpty());
        a.setPassword(""); assertTrue(list().isEmpty()); a.setPassword("new-password"); assertEquals(1,list().size());
        a.setName("renamed"); assertEquals("renamed",list().get(0).getServerName());
        a.setUsername("other"); assertTrue(list().isEmpty()); a.setUsername("user");
        a.setUrl("https://other.invalid/dav/"); assertTrue(list().isEmpty());
    }
    @Test public void staleInstanceCannotResurrectClearedEntries() {
        record("a","one.mp3"); SearchSongHistoryStore second = new SearchSongHistoryStore(target);
        assertTrue(second.clear()); record("a","two.mp3"); assertEquals(1,list().size());
        assertNull(second.resolve(SearchSongHistoryChecks.song("a","one.mp3"),Arrays.asList(a,b)));
    }
    @Test public void corruptAndOversizedFilesFailClosed() throws Exception {
        Files.write(target.toPath(),new byte[]{1,2,3}); assertTrue(list().isEmpty());
        Files.write(target.toPath(),new byte[524289]); assertTrue(list().isEmpty());
        record("a","new.mp3"); assertEquals(1,list().size());
    }
    @Test public void unsafePathsAndNonAudioAreNotRecorded() {
        for(String p:Arrays.asList("/one.mp3","../one.mp3","a/../one.mp3","a//one.mp3","a\\one.mp3","a\none.mp3","one.txt"))
            assertFalse(p,store.record(SearchSongHistoryChecks.song("a",p),a));
        assertTrue(list().isEmpty());
    }
    @Test public void failedAtomicPublicationPreservesExistingBytes() throws Exception {
        record("a","one.mp3"); byte[] before = Files.readAllBytes(target.toPath());
        try { AtomicSnapshotWriter.write(target,out->{out.write(1); throw new IOException("injected");}); fail(); }
        catch(IOException expected) { assertArrayEquals(before,Files.readAllBytes(target.toPath())); }
        assertEquals(1,list().size()); assertEquals(1,dir.listFiles().length);
    }
    @Test public void unwritableDestinationReturnsFailureWithoutThrowing() {
        SearchSongHistoryStore bad = new SearchSongHistoryStore(new File(new File(dir,"missing"),"history"));
        assertFalse(bad.record(SearchSongHistoryChecks.song("a","one.mp3"),a)); assertFalse(bad.clear());
    }
}
