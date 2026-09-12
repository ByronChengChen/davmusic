package com.byron.davmusic;

import java.util.Date;

public class WebDAVFile {
    private String href;
    private String displayName;
    private String relativePath;   // 剥离 baseUrl 并 URL 解码后的相对路径，如 "cmcc/music/苏慧伦"
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

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
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
    /**
     * 是否为音频文件。
     *
     * 优先按扩展名判断（最可靠，不依赖服务器返回的 MIME），
     * 扩展名无法判定时才参考 contentType。
     */
    public boolean isAudio() {
        // 1) 扩展名优先 —— 服务器 MIME 可能不准（如 application/octet-stream）
        if (FileUtils.isAudioFile(displayName)) {
            return true;
        }
        // 2) 扩展名判断失败时，退回到 MIME 类型
        if (contentType != null) {
            return contentType.trim().toLowerCase().startsWith("audio/");
        }
        return false;
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
