package com.byron.davmusic;

import java.util.Date;

public class WebDAVFile {
    private String href;
    private String displayName;
    private String relativePath;   // 剥离 baseUrl 并 URL 解码后的相对路径，如 "cmcc/music/苏慧伦"

    /**
     * 这个条目属于哪台服务器。
     *
     * 多服务器（服务器作为根目录第一层）的核心：每个条目自带来源，
     * 播放列表、缓存、快照都靠它区分，因此
     *   · 切换服务器不再是特殊操作，只是普通的一次目录导航
     *   · 播放队列可以跨服务器共存，点「下一首」仍取到正确的那台
     *   · 两台服务器存在同名路径也不会互相串内容
     */
    private String serverId;
    private String serverName;     // 冗余存一份别名，列表/播放器显示与日志排查用

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

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    /** 给条目打上服务器归属（解析 PROPFIND 结果时统一调用） */
    public void setOwner(ServerProfile profile) {
        if (profile == null) return;
        this.serverId = profile.getId();
        this.serverName = profile.getDisplayName();
    }

    /**
     * 缓存键：服务器 id + 相对路径。
     *
     * 本地下载与目录快照都以它为键，两台服务器即使路径完全同名也各自独立。
     * 没有归属信息时退回纯相对路径 —— 保持与旧版本缓存兼容（升级后
     * 已下载的歌曲仍能被认出来）。
     */
    public String getCacheKey() {
        String rel = relativePath == null ? "" : relativePath;
        return (serverId == null || serverId.isEmpty()) ? rel : serverId + ":" + rel;
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
