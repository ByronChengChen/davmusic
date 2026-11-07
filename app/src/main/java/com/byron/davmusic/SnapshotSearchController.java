package com.byron.davmusic;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Worker-owned corpus, UI deliveries fenced by query generation and source revision. */
public final class SnapshotSearchController {
    public interface Source { long revision(); List<WebDAVFile> load(); }
    private final Source source;
    private final Executor worker, ui;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;
    // Accessed only by the serial worker.
    private long loadedRevision = Long.MIN_VALUE;
    private List<WebDAVFile> corpus = Collections.emptyList();

    public SnapshotSearchController(Source source, Executor worker, Executor ui) {
        this.source = source; this.worker = worker; this.ui = ui;
    }

    public void search(String query, int mode, Consumer<List<WebDAVFile>> callback) {
        final long ticket = generation.incrementAndGet();
        if (closed) return;
        worker.execute(() -> {
            if (closed || ticket != generation.get()) return;
            long revision = source.revision();
            if (loadedRevision != revision) {
                List<WebDAVFile> fresh = source.load();
                if (source.revision() != revision) return;
                corpus = fresh;
                loadedRevision = revision;
            }
            List<WebDAVFile> result = SnapshotSearchEngine.search(corpus, query, mode);
            ui.execute(() -> {
                if (!closed && ticket == generation.get() && revision == source.revision())
                    callback.accept(result);
            });
        });
    }

    public void invalidate() { generation.incrementAndGet(); }
    public void close() { closed = true; invalidate(); }
}
