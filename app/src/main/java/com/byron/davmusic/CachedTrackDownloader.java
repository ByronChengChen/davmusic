package com.byron.davmusic;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.function.IntConsumer;

/** Application-owned transfer: no Activity capture, and no fallback to a different server. */
final class CachedTrackDownloader implements DirectoryDownloadTask.Backend {
    private static final java.util.Set<String> activeKeys = new java.util.HashSet<>();
    static boolean hasActiveDownloads() {
        synchronized (activeKeys) { return !activeKeys.isEmpty(); }
    }
    // Main-thread callers only; retain the same transfer fence for the complete local batch.
    static DirectoryDeletion.Result deleteIfIdle(java.util.List<String> keys,
            java.util.Set<String> protectedKeys, LocalCacheManager cache) {
        synchronized (activeKeys) {
            if (!activeKeys.isEmpty()) return null;
            return DirectoryDeletion.run(keys, protectedKeys, cache::deleteDownloadedCopy);
        }
    }
    private final Context context;
    private final LocalCacheManager cache;
    private final Handler main=new Handler(Looper.getMainLooper());
    IntConsumer progress;
    CachedTrackDownloader(Context context) {
        this.context=context.getApplicationContext(); cache=LocalCacheManager.getInstance(this.context);
    }
    public boolean isDownloaded(WebDAVFile file) { return cache.isDownloaded(file.getCacheKey()); }
    public void download(WebDAVFile file, DirectoryDownloadTask.Completion completion) {
        ServerProfile owner=null;
        for (ServerProfile s:ServerStore.list(context)) if (s.getId().equals(file.getServerId())) { owner=s; break; }
        if (owner==null) { completion.complete(false,"服务器不存在"); return; }
        File destination=cache.getDownloadDestination(file.getCacheKey());
        final File partial;
        try { partial=File.createTempFile("download-",".part",destination.getParentFile()); }
        catch (Exception e) { completion.complete(false,e.getMessage()); return; }
        synchronized (activeKeys) {
            if (!activeKeys.add(file.getCacheKey())) {
                partial.delete(); completion.complete(false,"该歌曲仍在下载，请稍后手动重下"); return;
            }
        }
        file.setDownloadState(WebDAVFile.DownloadState.DOWNLOADING);
        try {
            WebDAVClient.forServer(owner).download(file,partial,new WebDAVClient.ProgressCallback() {
                int lastPercent=-1;
                public void onProgress(int value) {
                    if (value==lastPercent) return; lastPercent=value;
                    main.post(()-> { if (progress!=null) progress.accept(value); });
                }
                public void onSuccess(Object result) {
                    main.post(()-> {
                        if (partial.length()==0 || (file.getContentLength()>0 && partial.length()!=file.getContentLength())
                                || !partial.renameTo(destination)) {
                            failed("文件不完整或无法保存"); return;
                        }
                        synchronized (activeKeys) { activeKeys.remove(file.getCacheKey()); }
                        cache.markFileDownloaded(file.getCacheKey(),destination);
                        file.setDownloadState(WebDAVFile.DownloadState.DOWNLOADED);
                        file.setLocalPath(destination.getAbsolutePath()); completion.complete(true,null);
                    });
                }
                public void onError(Exception e) { main.post(()->failed(e.getMessage())); }
                private void failed(String error) {
                    synchronized (activeKeys) { activeKeys.remove(file.getCacheKey()); }
                    partial.delete(); file.setDownloadState(WebDAVFile.DownloadState.NOT_DOWNLOADED);
                    file.setLocalPath(null); completion.complete(false,error);
                }
            });
        } catch (Exception e) {
            synchronized (activeKeys) { activeKeys.remove(file.getCacheKey()); }
            partial.delete(); file.setDownloadState(WebDAVFile.DownloadState.NOT_DOWNLOADED);
            completion.complete(false,e.getMessage());
        }
    }
}
