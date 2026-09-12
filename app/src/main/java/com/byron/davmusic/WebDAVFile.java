package com.byron.davmusic;

import java.util.Date;

public class WebDAVFile {
    private String href;
    private String displayName;
    private long contentLength;
    private String contentType;
    private long lastModified;
    private String etag;
    private boolean isCollection;
    private DownloadState downloadState;
    private String localPath;

    public enum DownloadState {
        NOT_DOWNLOADED,
        DOWNLOADING,
        DOWNLOADED
    }

    public WebDAVFile() {
        this.downloadState = DownloadState.NOT_DOWNLOADED;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public long getContentLength() {
        return contentLength;
    }

    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public boolean isCollection() {
        return isCollection;
    }

    public void setCollection(boolean collection) {
        isCollection = collection;
    }

    public DownloadState getDownloadState() {
        return downloadState;
    }

    public void setDownloadState(DownloadState downloadState) {
        this.downloadState = downloadState;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    // 辅助方法
    public boolean isAudio() {
        if (contentType == null) return false;
        return contentType.startsWith("audio/") || FileUtils.isAudioFile(displayName);
    }

    public boolean isImage() {
        if (contentType == null) return false;
        return contentType.startsWith("image/") || FileUtils.isImageFile(displayName);
    }

    public boolean isLyrics() {
        return FileUtils.isLyricsFile(displayName);
    }

    public Date getLastModifiedDate() {
        return new Date(lastModified);
    }

    @Override
    public String toString() {
        return "WebDAVFile{" +
                "displayName='" + displayName + '\'' +
                ", isCollection=" + isCollection +
                ", downloadState=" + downloadState +
                '}';
    }
}
