package com.byron.davmusic;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class DirectoryDownloadTaskTest {
    static WebDAVFile song(String owner, String path) {
        WebDAVFile f = new WebDAVFile(); f.setServerId(owner); f.setRelativePath(path);
        f.setDisplayName(path); return f;
    }
    static class Backend implements DirectoryDownloadTask.Backend {
        Set<String> cached = new HashSet<>(); List<String> calls = new ArrayList<>();
        DirectoryDownloadTask.Completion pending;
        public boolean isDownloaded(WebDAVFile f) { return cached.contains(f.getCacheKey()); }
        public void download(WebDAVFile f, DirectoryDownloadTask.Completion c) {
            calls.add(f.getCacheKey()); pending = c;
        }
        void finish(boolean ok) { DirectoryDownloadTask.Completion c=pending; pending=null; c.complete(ok, ok ? null : "HTTP 429"); }
    }
    @Test public void snapshotFiltersDeduplicatesAndSeparatesServers() {
        Backend b=new Backend(); WebDAVFile folder=song("a","folder.mp3"); folder.setCollection(true);
        List<WebDAVFile> files=new ArrayList<>(Arrays.asList(folder,song("a","x.txt"),song("a","1.mp3"),song("a","1.mp3"),song("b","1.mp3")));
        DirectoryDownloadTask t=new DirectoryDownloadTask(files,b); files.clear(); t.start();
        assertEquals(2,t.total); assertEquals(Arrays.asList("a:1.mp3"),b.calls);
        t.start(); assertEquals(1,b.calls.size()); b.finish(true);
        assertEquals(Arrays.asList("a:1.mp3","b:1.mp3"),b.calls); b.finish(true);
        assertTrue(t.finished); assertEquals(2,t.succeeded);
    }
    @Test public void skipsCacheAtTurnAndContinuesFailureWithoutRetry() {
        Backend b=new Backend(); b.cached.add("a:1.mp3");
        DirectoryDownloadTask t=new DirectoryDownloadTask(Arrays.asList(song("a","1.mp3"),song("a","2.mp3"),song("a","3.mp3")),b);
        t.start(); b.cached.add("a:3.mp3"); b.finish(false);
        assertTrue(t.finished); assertEquals(2,t.skipped); assertEquals(1,t.failed);
        assertEquals(Arrays.asList("a:2.mp3"),b.calls); assertTrue(t.lastError.contains("429"));
    }
    @Test public void cancelLetsCurrentFinishButNeverStartsRemaining() {
        Backend b=new Backend(); DirectoryDownloadTask t=new DirectoryDownloadTask(Arrays.asList(song("a","1.mp3"),song("a","2.mp3")),b);
        t.start(); t.cancelRemaining(); assertFalse(t.finished); b.finish(true);
        assertTrue(t.finished); assertEquals(1,t.succeeded); assertEquals(1,t.remaining()); assertEquals(1,b.calls.size());
    }
    @Test public void duplicateCompletionIgnored() {
        Backend b=new Backend(); DirectoryDownloadTask t=new DirectoryDownloadTask(Arrays.asList(song("a","1.mp3"),song("a","2.mp3")),b);
        t.start(); DirectoryDownloadTask.Completion old=b.pending; b.finish(true); old.complete(false,"late");
        assertEquals(1,t.succeeded); assertEquals(0,t.failed); b.finish(true); assertEquals(2,t.succeeded);
    }
    @Test public void synchronousErrorsAreCountedAndEmptyTaskFinishes() {
        DirectoryDownloadTask.Backend b=new DirectoryDownloadTask.Backend() {
            public boolean isDownloaded(WebDAVFile f) { return false; }
            public void download(WebDAVFile f,DirectoryDownloadTask.Completion c) { throw new IllegalStateException("removed server"); }
        };
        DirectoryDownloadTask t=new DirectoryDownloadTask(Arrays.asList(song("a","1.mp3")),b); t.start();
        assertTrue(t.finished); assertEquals(1,t.failed);
        DirectoryDownloadTask empty=new DirectoryDownloadTask(Collections.emptyList(),b); empty.start(); assertTrue(empty.finished);
    }
}
