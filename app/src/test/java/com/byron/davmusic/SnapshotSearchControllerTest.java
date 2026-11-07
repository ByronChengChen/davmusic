package com.byron.davmusic;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.Executor;

public class SnapshotSearchControllerTest {
    static class Tasks implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        public void execute(Runnable r) { tasks.add(r); }
        void drain() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
    }
    static class Source implements SnapshotSearchController.Source {
        long revision; int reads;
        List<WebDAVFile> files = Arrays.asList(SnapshotSearchEngineTest.song("a", "Blue.mp3"));
        public long revision() { return revision; }
        public List<WebDAVFile> load() { reads++; return files; }
    }
    @Test public void newQueryWinsAndUnchangedSourceIsReadOnlyOnce() {
        Tasks worker = new Tasks(), ui = new Tasks(); Source source = new Source();
        SnapshotSearchController c = new SnapshotSearchController(source, worker, ui);
        List<String> delivered = new ArrayList<>();
        c.search("blue", 1, r -> delivered.add("old"));
        worker.drain(); // old delivery still waiting
        c.search("missing", 1, r -> delivered.add("new:" + r.size()));
        worker.drain(); ui.drain();
        assertEquals(Arrays.asList("new:0"), delivered);
        assertEquals(1, source.reads);
    }
    @Test public void invalidationRejectsPendingDeliveryAndReloadsEmptySource() {
        Tasks worker = new Tasks(), ui = new Tasks(); Source source = new Source();
        SnapshotSearchController c = new SnapshotSearchController(source, worker, ui);
        List<Integer> delivered = new ArrayList<>();
        c.search("blue", 1, r -> delivered.add(r.size())); worker.drain();
        source.revision++; source.files = Collections.emptyList();
        c.invalidate(); ui.drain();
        assertTrue(delivered.isEmpty());
        c.search("blue", 1, r -> delivered.add(r.size())); worker.drain(); ui.drain();
        assertEquals(Arrays.asList(0), delivered); assertEquals(2, source.reads);
        c.search("blue", 1, r -> delivered.add(999)); worker.drain();
        c.close(); ui.drain(); assertEquals(Arrays.asList(0), delivered);
    }
    @Test public void sourceChangeBetweenReadAndDeliveryCannotPublish() {
        Tasks worker = new Tasks(), ui = new Tasks(); Source source = new Source();
        SnapshotSearchController c = new SnapshotSearchController(source, worker, ui);
        List<Integer> delivered = new ArrayList<>();
        c.search("blue", 1, r -> delivered.add(r.size())); worker.drain();
        source.revision++; ui.drain(); assertTrue(delivered.isEmpty());
    }
}
