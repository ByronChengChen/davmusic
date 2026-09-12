package com.byron.davmusic;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MusicPlayer {
    private static final String TAG = "MusicPlayer";
    private static MusicPlayer instance;
    
    private Context context;
    private MediaPlayer mediaPlayer;
    private Handler handler;
    private List<WebDAVFile> playlist;
    private int currentPosition = -1;
    private boolean isPreparing = false;
    private Map<String, String> authHeaders;
    private String currentUrl;     // 当前播放的 URL，便于错误诊断
    
    private List<OnPlaybackListener> listeners = new ArrayList<>();
    
    public interface OnPlaybackListener {
        void onTrackChanged(WebDAVFile track);
        void onPlayStateChanged(boolean isPlaying);
        void onProgress(int position, int duration);
        void onError(String error);
    }
    
    private MusicPlayer(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        initMediaPlayer();
    }
    
    public static synchronized MusicPlayer getInstance(Context context) {
        if (instance == null) {
            instance = new MusicPlayer(context);
        }
        return instance;
    }
    
    private void initMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnPreparedListener(mp -> {
            isPreparing = false;
            Log.d(TAG, "MediaPlayer prepared, starting playback");
            mp.start();
            notifyPlayStateChanged(true);
        });
        
        mediaPlayer.setOnCompletionListener(mp -> {
            Log.d(TAG, "Playback completed");
            playNext();
        });
        
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            isPreparing = false;
            String detail = describeMediaError(what, extra);
            String error = "播放错误: " + detail;
            Log.e(TAG, error + " | url=" + currentUrl);
            notifyError(error);
            return true;
        });
        
        // 设置进度更新定时器
        handler.postDelayed(progressUpdater, 1000);
    }
    
    private Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                int currentPos = mediaPlayer.getCurrentPosition();
                int duration = mediaPlayer.getDuration();
                notifyProgress(currentPos, duration);
            }
            handler.postDelayed(this, 1000);
        }
    };
    
    public void setAuthHeaders(Map<String, String> headers) {
        this.authHeaders = headers;
    }
    
    /**
     * 把 MediaPlayer 的 what/extra 错误码翻译成可读信息，
     * 便于用户反馈与排查。
     */
    private String describeMediaError(int what, int extra) {
        if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN) {
            return "未知错误(what=" + what + ", extra=" + extra + ")";
        }
        if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            return "媒体服务已终止(extra=" + extra + ")";
        }
        if (what == -38) {
            // 常见于数据源无效 / 格式不支持 / 网络请求失败
            return "无法读取音频源(what=-38) — 可能是网络、地址或格式问题";
        }
        return "what=" + what + ", extra=" + extra;
    }

    public void play(String url, String displayName) {
        if (url == null || url.isEmpty()) {
            notifyError("播放 URL 为空");
            return;
        }
        currentUrl = url;
        
        stop();
        
        try {
            isPreparing = true;
            
            if (url.startsWith("file://") || url.startsWith("/")) {
                // 本地文件播放
                File file = new File(url.startsWith("file://") ? url.substring(7) : url);
                if (!file.exists()) {
                    notifyError("本地文件不存在: " + url);
                    return;
                }
                mediaPlayer.setDataSource(file.getAbsolutePath());
            } else {
                // 在线播放
                Uri uri = Uri.parse(url);
                if (authHeaders != null && !authHeaders.isEmpty()) {
                    // 有认证头的情况
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Authorization", authHeaders.get("Authorization"));
                    mediaPlayer.setDataSource(context, uri, headers);
                } else {
                    // 无认证头的情况
                    mediaPlayer.setDataSource(context, uri);
                }
            }
            
            mediaPlayer.prepareAsync();
            Log.d(TAG, "开始准备播放: " + displayName);
            
        } catch (IOException e) {
            isPreparing = false;
            Log.e(TAG, "播放失败: " + e.getMessage(), e);
            notifyError("播放失败: " + e.getMessage());
        }
    }
    
    public void setPlaylist(List<WebDAVFile> playlist, int startIndex) {
        this.playlist = playlist != null ? new ArrayList<>(playlist) : new ArrayList<>();
        this.currentPosition = startIndex >= 0 && startIndex < this.playlist.size() ? startIndex : -1;
        
        if (currentPosition >= 0 && currentPosition < this.playlist.size()) {
            WebDAVFile currentTrack = this.playlist.get(currentPosition);
            notifyTrackChanged(currentTrack);
        }
    }
    
    public void playFile(WebDAVFile file) {
        if (file == null) return;
        
        // 确定播放 URL
        String url;
        LocalCacheManager cacheManager = LocalCacheManager.getInstance(context);
        
        if (cacheManager.isDownloaded(file.getHref())) {
            // 播放本地文件
            File localFile = cacheManager.getLocalFile(file.getHref());
            if (localFile != null && localFile.exists()) {
                url = "file://" + localFile.getAbsolutePath();
                Log.d(TAG, "播放本地文件: " + localFile.getAbsolutePath());
            } else {
                // 本地文件不存在，播放在线文件
                url = WebDAVClient.getInstance().getDownloadUrl(file);
                Log.d(TAG, "本地文件不存在，播放在线文件: " + url);
            }
        } else {
            // 播放在线文件
            url = WebDAVClient.getInstance().getDownloadUrl(file);
            Log.d(TAG, "播放在线文件: " + url);
        }
        
        play(url, file.getDisplayName());
        
        // 如果这个文件在播放列表中，更新当前位置
        if (playlist != null) {
            for (int i = 0; i < playlist.size(); i++) {
                if (playlist.get(i).getHref().equals(file.getHref())) {
                    currentPosition = i;
                    notifyTrackChanged(file);
                    break;
                }
            }
        }
    }
    
    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            notifyPlayStateChanged(false);
        }
    }
    
    public void resume() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            notifyPlayStateChanged(true);
        }
    }
    
    public void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.reset();
            initMediaPlayer(); // 重新初始化 MediaPlayer
            notifyPlayStateChanged(false);
        }
    }
    
    public void playNext() {
        if (playlist == null || playlist.isEmpty() || currentPosition == -1) {
            return;
        }
        
        int nextPosition = (currentPosition + 1) % playlist.size();
        if (nextPosition == currentPosition) {
            return; // 列表��有一个项目
        }
        
        currentPosition = nextPosition;
        WebDAVFile nextTrack = playlist.get(currentPosition);
        playFile(nextTrack);
    }
    
    public void playPrevious() {
        if (playlist == null || playlist.isEmpty() || currentPosition == -1) {
            return;
        }
        
        int prevPosition = currentPosition - 1;
        if (prevPosition < 0) {
            prevPosition = playlist.size() - 1;
        }
        
        if (prevPosition == currentPosition) {
            return; // 列表只有一个项目
        }
        
        currentPosition = prevPosition;
        WebDAVFile prevTrack = playlist.get(currentPosition);
        playFile(prevTrack);
    }
    
    public void seekTo(int position) {
        if (mediaPlayer != null && position >= 0 && position <= mediaPlayer.getDuration()) {
            mediaPlayer.seekTo(position);
        }
    }
    
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }
    
    public boolean isPreparing() {
        return isPreparing;
    }
    
    public int getCurrentPosition() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
    }
    
    public int getDuration() {
        return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
    }
    
    public WebDAVFile getCurrentTrack() {
        if (playlist != null && currentPosition >= 0 && currentPosition < playlist.size()) {
            return playlist.get(currentPosition);
        }
        return null;
    }
    
    public int getCurrentTrackIndex() {
        return currentPosition;
    }
    
    public List<WebDAVFile> getPlaylist() {
        return playlist != null ? new ArrayList<>(playlist) : new ArrayList<>();
    }
    
    // 监听器管理
    public void addPlaybackListener(OnPlaybackListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removePlaybackListener(OnPlaybackListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyTrackChanged(WebDAVFile track) {
        for (OnPlaybackListener listener : listeners) {
            listener.onTrackChanged(track);
        }
    }
    
    private void notifyPlayStateChanged(boolean isPlaying) {
        for (OnPlaybackListener listener : listeners) {
            listener.onPlayStateChanged(isPlaying);
        }
    }
    
    private void notifyProgress(int position, int duration) {
        for (OnPlaybackListener listener : listeners) {
            listener.onProgress(position, duration);
        }
    }
    
    private void notifyError(String error) {
        for (OnPlaybackListener listener : listeners) {
            listener.onError(error);
        }
    }
    
    public void release() {
        if (handler != null) {
            handler.removeCallbacks(progressUpdater);
        }
        
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        
        listeners.clear();
        instance = null;
    }
}
