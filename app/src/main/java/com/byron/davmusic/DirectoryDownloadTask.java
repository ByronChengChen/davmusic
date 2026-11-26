package com.byron.davmusic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Single-use serial task. All entry points/callbacks run on the owning (UI) thread. */
final class DirectoryDownloadTask {
    interface Completion { void complete(boolean success, String error); }
    interface Backend {
        boolean isDownloaded(WebDAVFile file);
        void download(WebDAVFile file, Completion completion);
    }
    private final List<WebDAVFile> files;
    private final Backend backend;
    final int total;
    int succeeded, skipped, failed, percent;
    boolean finished, cancelled;
    String lastError;
    WebDAVFile current;
    Runnable listener;
    private int next;
    private boolean started, advancing;

    DirectoryDownloadTask(List<WebDAVFile> visible, Backend backend) {
        LinkedHashMap<String,WebDAVFile> unique=new LinkedHashMap<>();
        for (WebDAVFile f:visible) if (!f.isCollection() && f.isAudio()) unique.putIfAbsent(f.getCacheKey(),f);
        files=new ArrayList<>(unique.values()); total=files.size(); this.backend=backend;
    }
    int remaining() { return total-succeeded-skipped-failed; }
    void start() { if (started) return; started=true; advance(); }
    void cancelRemaining() { cancelled=true; if (current==null) finished=true; changed(); }
    void progress(int value) { percent=value; changed(); }
    private void changed() { if (listener!=null) listener.run(); }
    private void advance() {
        if (advancing) return;
        advancing=true;
        try {
            while (current==null && !finished) {
                if (cancelled || next==files.size()) { finished=true; break; }
                WebDAVFile f=files.get(next++);
                if (backend.isDownloaded(f)) { skipped++; continue; }
                current=f; percent=0; changed();
                final boolean[] settled={false};
                Completion completion=(ok,error)-> {
                    if (settled[0]) return; settled[0]=true;
                    if (ok) succeeded++; else { failed++; lastError=f.getDisplayName()+": "+error; }
                    current=null; changed(); advance();
                };
                try { backend.download(f,completion); }
                catch (Exception e) { completion.complete(false,e.getMessage()); }
            }
        } finally { advancing=false; }
        changed();
    }
}
