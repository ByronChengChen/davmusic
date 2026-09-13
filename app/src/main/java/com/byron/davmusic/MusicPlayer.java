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

    // ---- 后台播放支持 ----
    /**
     * 切歌期间持有的 WakeLock。
     *
     * 为什么需要：在线播放时切下一首要发新的 HTTP 请求。锁屏后 CPU 可能
     * 进入休眠，而 MediaPlayer 的 onCompletion 回调依赖主线程消息队列，
     * 网络请求又依赖 CPU 唤醒 —— 两者都可能被延迟到用户解锁后才发生，
     * 表现为"锁屏播完一首后不自动播下一首"。
     * 在切换曲目的窗口内短暂持有 PARTIAL_WAKE_LOCK 即可解决。
     */
    private android.os.PowerManager.WakeLock transitionWakeLock;
    /** 音频焦点：播放时申请，失去时暂停（来电/其他 App 抢占） */
    private android.media.AudioManager audioManager;
    private android.media.AudioManager.OnAudioFocusChangeListener audioFocusListener;
    private boolean hasAudioFocus = false;
    /** 预加载的下一首 MediaPlayer（由 setNextMediaPlayer 接管，需跟踪以避免泄漏） */
    private android.media.MediaPlayer preloadedPlayer;
    
    private List<OnPlaybackListener> listeners = new ArrayList<>();
    /** 远程日志：只记录，不影响播放逻辑 */
    private RemoteLogger rlog;
    
    public interface OnPlaybackListener {
        void onTrackChanged(WebDAVFile track);
        void onPlayStateChanged(boolean isPlaying);
        void onProgress(int position, int duration);
        void onError(String error);
    }
    
    private MusicPlayer(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.rlog = RemoteLogger.getInstance(this.context);
        rlog.i(TAG, "MusicPlayer 初始化");
        initWakeLock();
        initAudioFocus();
        initMediaPlayer();
    }

    /**
     * 初始化切歌用的 WakeLock。
     * PARTIAL_WAKE_LOCK 只保持 CPU 运行、不点亮屏幕，是后台音频场景的标准做法。
     */
    private void initWakeLock() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                transitionWakeLock = pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "davmusic:track-transition");
                // 引用计数由我们手动管理，避免超时后锁状态与预期不符
                transitionWakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock 初始化失败: " + e.getMessage());
        }
    }

    /** 短暂持有 WakeLock（最多 3 分钟，防泄漏） */
    private void acquireTransitionWakeLock() {
        try {
            if (transitionWakeLock != null && !transitionWakeLock.isHeld()) {
                transitionWakeLock.acquire(3 * 60 * 1000L);
                Log.d(TAG, "已获取 WakeLock（后台切歌）");
            }
        } catch (Exception e) {
            Log.w(TAG, "获取 WakeLock 失败: " + e.getMessage());
        }
    }

    /** 释放 WakeLock */
    private void releaseTransitionWakeLock() {
        try {
            if (transitionWakeLock != null && transitionWakeLock.isHeld()) {
                transitionWakeLock.release();
                Log.d(TAG, "已释放 WakeLock");
            }
        } catch (Exception e) {
            Log.w(TAG, "释放 WakeLock 失败: " + e.getMessage());
        }
    }

    /** 初始化音频焦点监听 */
    private void initAudioFocus() {
        audioManager = (android.media.AudioManager)
                context.getSystemService(Context.AUDIO_SERVICE);
        audioFocusListener = focusChange -> {
            switch (focusChange) {
                case android.media.AudioManager.AUDIOFOCUS_LOSS:
                    // 永久失去（其他 App 开始播放）：暂停
                    hasAudioFocus = false;
                    pause();
                    break;
                case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // 暂时失去（来电）：暂停，之后用户手动恢复
                    pause();
                    break;
                case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // 可以降低音量（导航播报）：这里简单处理为保持播放
                    break;
                case android.media.AudioManager.AUDIOFOCUS_GAIN:
                    hasAudioFocus = true;
                    break;
                default:
                    break;
            }
        };
    }

    /** 申请音频焦点；成功返回 true */
    private boolean requestAudioFocus() {
        if (audioManager == null || audioFocusListener == null) return true;
        try {
            int r = audioManager.requestAudioFocus(
                    audioFocusListener,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN);
            hasAudioFocus = (r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            return hasAudioFocus;
        } catch (Exception e) {
            Log.w(TAG, "申请音频焦点失败: " + e.getMessage());
            return true;   // 失败不阻塞播放
        }
    }

    /** 释放音频焦点 */
    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusListener != null) {
            try {
                audioManager.abandonAudioFocus(audioFocusListener);
            } catch (Exception ignored) {
            }
        }
        hasAudioFocus = false;
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
        rlog.i(TAG, "initMediaPlayer: 新建实例 hash=" + mediaPlayer.hashCode());

        // 让 MediaPlayer 自己做 CPU 唤醒管理（内部持有 MediaPlayer 级别的
        // WakeLock），配合下面的 transitionWakeLock 覆盖切歌窗口。
        try {
            mediaPlayer.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK);
        } catch (Exception e) {
            Log.w(TAG, "setWakeMode 失败: " + e.getMessage());
        }

        // 声明音频用途，系统据此做音量/焦点决策
        try {
            mediaPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
        } catch (Exception e) {
            Log.w(TAG, "setAudioAttributes 失败: " + e.getMessage());
        }

        mediaPlayer.setOnPreparedListener(mp -> {
            isPreparing = false;
            Log.d(TAG, "MediaPlayer prepared, starting playback");
            mp.start();
            notifyPlayStateChanged(true);
            rlog.i(TAG, "onPrepared 触发: mp=" + mp.hashCode()
                    + " | 当前 mediaPlayer=" + (mediaPlayer == null ? "null" : mediaPlayer.hashCode()));
            logState("onPrepared");
            // 已开始播放，WakeLock 使命完成（MediaPlayer 自身会保持唤醒）
            releaseTransitionWakeLock();
            // 关键：在后台把下一首准备好。
            // 这样播完时由 MediaPlayer 在 native 层直接接续，
            // 不依赖主线程被调度，也不需要在切歌瞬间发网络请求 ——
            // 这正是锁屏后"播完一首就不动了"的解法。
            prepareNextTrack();
        });
        
        mediaPlayer.setOnCompletionListener(mp -> {
            Log.d(TAG, "Playback completed");
            logState("onCompletion 触发");
            rlog.i(TAG, "onCompletion: currentPosition=" + currentPosition
                    + " playlistSize=" + (playlist == null ? -1 : playlist.size())
                    + " hasPreloaded=" + (preloadedPlayer != null));
            // 不能在 onCompletion 回调里直接切换曲目：
            // play() 会调用 stop() → MediaPlayer.reset() + 重建实例，
            // 等于在回调内部销毁回调的宿主，时序错乱会导致切歌失败或卡死。
            // 用 handler 把切换动作挪到当前消息循环之外执行。
            handler.post(() -> {
                rlog.i(TAG, "onCompletion handler 执行 (post 未被延迟丢弃)");
                if (playlist == null || playlist.size() <= 1) {
                    // 单曲或空列表：播完就停在当前曲目，不循环重播
                    rlog.i(TAG, "单曲或空列表，停止切歌");
                    notifyPlayStateChanged(false);
                    return;
                }
                // 走正常切歌流程：推进索引 → playFile → play()。
                // 即使 MediaPlayer 原生已预加载下一首，这里仍重新走一遍，
                // 因为它同时负责更新 currentPosition、通知 UI、以及
                // 为"新的下一首"再次预加载 —— 保持状态单一可信。
                playNext();
            });
        });
        
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            isPreparing = false;
            String detail = describeMediaError(what, extra);
            String error = "播放错误: " + detail;
            Log.e(TAG, error + " | url=" + currentUrl);
            rlog.e(TAG, error + " | url=" + currentUrl);
            // 播放失败：切歌窗口结束，释放临时 WakeLock 防止泄漏
            releaseTransitionWakeLock();
            notifyError(error);
            return true;
        });

        mediaPlayer.setOnInfoListener((mp, what, extra) -> {
            // 记录流媒体缓冲区信息，便于诊断弱网卡顿
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                Log.d(TAG, "缓冲开始");
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                Log.d(TAG, "缓冲结束");
            }
            return false;
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
        String ext = getFileExtension(currentUrl);

        if (what == -38) {
            // 常见于数据源无效 / 格式不支持 / 网络请求失败
            return "无法读取音频源(what=-38) — 可能是网络、地址或格式问题";
        }
        if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN) {
            // Android 原生 MediaPlayer 对 wma/ape 等格式支持不完整，
            // 编解码器缺失时即报 what=1
            if ("wma".equals(ext) || "ape".equals(ext)) {
                return "系统播放器不支持 ." + ext + " 格式 — 建议先下载后用第三方播放器打开";
            }
            return "未知错误(what=1, extra=" + extra + ")"
                    + (ext.isEmpty() ? "" : " — ." + ext + " 格式可能不被系统支持");
        }
        if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            return "媒体服务已终止(extra=" + extra + ")";
        }
        return "what=" + what + ", extra=" + extra;
    }

    /** 从 URL 中取出扩展名（小写，不含点） */
    private String getFileExtension(String url) {
        if (url == null) return "";
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int dot = u.lastIndexOf('.');
        if (dot < 0 || dot == u.length() - 1) return "";
        return u.substring(dot + 1).toLowerCase();
    }

    public void play(String url, String displayName) {
        if (url == null || url.isEmpty()) {
            notifyError("播放 URL 为空");
            return;
        }
        currentUrl = url;
        rlog.i(TAG, "play(): " + displayName + " | url=" + url);

        // 切歌窗口内保持 CPU 唤醒：从发起请求到 onPrepared 之间，
        // 锁屏状态下若 CPU 休眠，网络请求与回调都可能被挂起，
        // 表现为"锁屏播完一首后不自动播下一首"。
        acquireTransitionWakeLock();

        // 申请音频焦点（来电/其他播放器场景下由系统协调）
        if (!hasAudioFocus) {
            requestAudioFocus();
        }

        stop();
        
        try {
            isPreparing = true;

            // 注意：必须重新读取字段而非使用局部变量 —— stop() 内部的
            // initMediaPlayer() 会 release 旧实例并 new 一个新实例，
            // 若这里持有旧的引用，setDataSource/prepareAsync 会作用在
            // 已 release 的对象上，表现为 prepareAsync 后 onPrepared 永不触发
            // （日志表现为 play() 打印后就没有下文了）。
            android.media.MediaPlayer mp = mediaPlayer;
            if (mp == null) {
                isPreparing = false;
                releaseTransitionWakeLock();
                notifyError("MediaPlayer 未就绪");
                return;
            }
            rlog.i(TAG, "play 使用的是重建后的实例: mp=" + mp.hashCode());

            if (url.startsWith("file://") || url.startsWith("/")) {
                // 本地文件播放
                File file = new File(url.startsWith("file://") ? url.substring(7) : url);
                if (!file.exists()) {
                    isPreparing = false;
                    releaseTransitionWakeLock();
                    notifyError("本地文件不存在: " + url);
                    return;
                }
                mp.setDataSource(file.getAbsolutePath());
            } else {
                // 在线播放
                Uri uri = Uri.parse(url);
                if (authHeaders != null && !authHeaders.isEmpty()) {
                    // 有认证头的情况
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Authorization", authHeaders.get("Authorization"));
                    mp.setDataSource(context, uri, headers);
                } else {
                    // 无认证头的情况
                    mp.setDataSource(context, uri);
                }
            }
            
            rlog.i(TAG, "准备 prepareAsync: mp=" + mp.hashCode());
            mp.prepareAsync();
            rlog.i(TAG, "已调用 prepareAsync: mp=" + mp.hashCode()
                    + " | " + displayName);
            Log.d(TAG, "开始准备播放: " + displayName);
            
        } catch (IOException e) {
            isPreparing = false;
            releaseTransitionWakeLock();
            Log.e(TAG, "播放失败: " + e.getMessage(), e);
            notifyError("播放失败: " + e.getMessage());
        }
    }

    // ---- 预加载下一首（后台无缝切歌的关键） ----

    /**
     * 预加载下一首到 MediaPlayer 的 "next" 槽位。
     *
     * 为什么这是后台切歌的关键：
     *   仅靠 onCompletion + handler 切歌，在锁屏状态下依赖"回调能准时执行"
     *   和"切歌时能立刻发网络请求"两件事同时成立，任何一件被系统延迟，
     *   用户看到的就是"播完不动了"。
     *   setNextMediaPlayer() 把准备动作提前到【当前曲目还在播放时】完成，
     *   播完由 MediaPlayer 在 native 层直接切换到已缓冲好的流 ——
     *   不依赖 App 主线程被调度，也不需要切歌瞬间的网络请求。
     *
     * ⚠️ 关键约束：setNextMediaPlayer() 要求 next player 必须【已经 prepare
     * 完成】，否则抛 IllegalStateException（getMessage() 为 null，极难排查）。
     * 因此这里必须先 prepareAsync()，并在 onPrepared 回调里才挂上去。
     * 这正是上一版"预加载失败: null"的原因。
     */
    private void prepareNextTrack() {
        if (mediaPlayer == null) return;
        if (playlist == null || playlist.size() <= 1) return;
        if (currentPosition < 0 || currentPosition >= playlist.size()) return;

        int nextPos = (currentPosition + 1) % playlist.size();
        WebDAVFile nextTrack = playlist.get(nextPos);
        if (nextTrack == null) return;

        String url = resolvePlayUrl(nextTrack);
        if (url == null || url.isEmpty()) return;

        android.media.MediaPlayer next = null;
        try {
            next = new android.media.MediaPlayer();
            next.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            next.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK);

            if (url.startsWith("file://") || url.startsWith("/")) {
                File f = new File(url.startsWith("file://") ? url.substring(7) : url);
                if (!f.exists()) {
                    next.release();
                    return;
                }
                next.setDataSource(f.getAbsolutePath());
            } else {
                Uri uri = Uri.parse(url);
                if (authHeaders != null && !authHeaders.isEmpty()) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Authorization", authHeaders.get("Authorization"));
                    next.setDataSource(context, uri, headers);
                } else {
                    next.setDataSource(context, uri);
                }
            }

            final String nextName = nextTrack.getDisplayName();

            // 必须等 prepare 完成才能交给 setNextMediaPlayer
            next.setOnPreparedListener(prepared -> {
                try {
                    if (mediaPlayer == null) {
                        prepared.release();
                        return;
                    }
                    // 二次确认：当前曲目仍未切换（避免用户已手动切歌后误挂）
                    String expect = resolvePlayUrl(
                            playlist != null && currentPosition >= 0
                                    && currentPosition < playlist.size()
                                    ? playlist.get(currentPosition) : null);
                    if (expect == null || !expect.equals(currentUrl)) {
                        Log.d(TAG, "当前曲目已变化，丢弃预加载结果: " + nextName);
                        prepared.release();
                        return;
                    }
                    mediaPlayer.setNextMediaPlayer(prepared);
                    preloadedPlayer = prepared;
                    Log.d(TAG, "已预加载下一首: " + nextName);
                    rlog.i(TAG, "预加载成功: " + nextName);
                } catch (Exception ex) {
                    rlog.w(TAG, "挂载预加载失败: " + ex.getClass().getSimpleName()
                            + " / " + ex.getMessage());
                    try { prepared.release(); } catch (Exception ignored) {}
                    preloadedPlayer = null;
                }
            });

            next.setOnErrorListener((mp, what, extra) -> {
                rlog.w(TAG, "预加载播放器出错: what=" + what + " extra=" + extra
                        + " (" + nextName + ")");
                return true;
            });

            next.prepareAsync();   // ← 上一版漏掉的关键调用
            Log.d(TAG, "预加载中: " + nextName);

        } catch (Exception e) {
            Log.w(TAG, "预加载下一首失败（不影响当前播放）: " + e);
            rlog.w(TAG, "预加载失败: " + e.getClass().getSimpleName()
                    + " / " + e.getMessage());
            if (next != null) {
                try { next.release(); } catch (Exception ignored) {}
            }
            preloadedPlayer = null;
        }
    }

    /**
     * 解析某曲目的实际播放地址：已下载用本地文件，否则用远端 URL。
     * 与 playFile 中的判定逻辑保持一致。
     */
    private String resolvePlayUrl(WebDAVFile file) {
        if (file == null) return null;
        LocalCacheManager cm = LocalCacheManager.getInstance(context);
        if (cm.isDownloaded(file.getHref())) {
            File local = cm.getLocalFile(file.getHref());
            if (local != null && local.exists()) {
                return "file://" + local.getAbsolutePath();
            }
        }
        return WebDAVClient.getInstance().getDownloadUrl(file);
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
        rlog.i(TAG, "playFile: " + file.getDisplayName()
                + " | pos=" + currentPosition
                + " | downloaded=" + LocalCacheManager.getInstance(context)
                        .isDownloaded(file.getHref()));
        
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

        // 同步 currentPosition：只有当列表中的位置与当前记录不一致时才纠正。
        // 注意不要无条件覆盖 —— playNext/playPrevious 已经推进过索引，
        // 若这里再遍历一遍按 href 匹配，一旦匹配失败（href 编码差异、
        // 列表已被刷新替换）就会把索引留在错误位置，
        // 下次切歌就会跳错曲目。
        if (playlist != null && !playlist.isEmpty()) {
            boolean inSync = currentPosition >= 0
                    && currentPosition < playlist.size()
                    && sameFile(playlist.get(currentPosition), file);
            if (!inSync) {
                int idx = indexOfFile(file);
                if (idx >= 0) {
                    currentPosition = idx;
                } else {
                    // 不在列表中（例如刷新后列表已换）：把当前曲目插到列表首位，
                    // 保证后续切歌仍有可播的目标，而不是从错误位置继续。
                    playlist.add(0, file);
                    currentPosition = 0;
                }
            }
            notifyTrackChanged(playlist.get(currentPosition));
        }
    }

    /** 判断两个文件是否同一首：优先 href，回退到 relativePath（href 可能编码不一致）*/
    private boolean sameFile(WebDAVFile a, WebDAVFile b) {
        if (a == null || b == null) return false;
        String ha = a.getHref(), hb = b.getHref();
        if (ha != null && ha.equals(hb)) return true;
        String ra = a.getRelativePath(), rb = b.getRelativePath();
        return ra != null && ra.equals(rb);
    }

    /** 在播放列表中定位文件；找不到返回 -1 */
    private int indexOfFile(WebDAVFile file) {
        if (playlist == null) return -1;
        for (int i = 0; i < playlist.size(); i++) {
            if (sameFile(playlist.get(i), file)) return i;
        }
        return -1;
    }

    /**
     * 输出完整播放状态快照到远端日志。
     *
     * 这是定位"锁屏播完不切歌"的核心手段：需要在事件发生时
     * 同时看到 MediaPlayer 状态、播放列表状态、预加载状态、
     * 以及 CPU 唤醒锁的持有情况 —— 缺任何一项都无法区分
     * "回调没触发" / "回调触发了但切歌被拦下" / "切歌了但播不出来"。
     */
    private void logState(String where) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(where).append("] ");
            sb.append("pos=").append(currentPosition);
            sb.append("/").append(playlist == null ? -1 : playlist.size());

            if (mediaPlayer != null) {
                try {
                    sb.append(" | playing=").append(mediaPlayer.isPlaying());
                } catch (Exception ex) {
                    sb.append(" | playing=?(").append(ex.getClass().getSimpleName()).append(")");
                }
                try {
                    sb.append(" | dur=").append(mediaPlayer.getDuration());
                    sb.append(" | cur=").append(mediaPlayer.getCurrentPosition());
                } catch (Exception ex) {
                    sb.append(" | dur/cur=?(illegal state)");
                }
            } else {
                sb.append(" | mp=null");
            }

            sb.append(" | preparing=").append(isPreparing);
            sb.append(" | preloaded=").append(preloadedPlayer != null);
            sb.append(" | wakeLock=")
              .append(transitionWakeLock != null && transitionWakeLock.isHeld());
            sb.append(" | focus=").append(hasAudioFocus);
            sb.append(" | url=").append(currentUrl == null ? "-" : currentUrl);

            String track = "-";
            if (playlist != null && currentPosition >= 0
                    && currentPosition < playlist.size()) {
                track = playlist.get(currentPosition).getDisplayName();
            }
            sb.append(" | track=").append(track);

            rlog.i(TAG, sb.toString());
        } catch (Exception e) {
            // 诊断代码本身绝不能影响播放
            rlog.w(TAG, "logState 异常: " + e.getMessage());
        }
    }
    
    /**
     * 暂停。
     *
     * 只在真正处于播放状态时才 pause —— 缓冲中（Preparing）调用 pause()
     * 会抛 IllegalStateException。
     */
    public void pause() {
        if (mediaPlayer == null) return;
        if (isPreparing) {
            // 缓冲阶段没有可暂停的内容，忽略
            Log.d(TAG, "pause() 忽略：当前正在缓冲");
            return;
        }
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                notifyPlayStateChanged(false);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "pause() 状态非法，已忽略: " + e.getMessage());
        }
    }

    /**
     * 恢复播放。
     *
     * 缓冲中或未准备时调用 start() 会抛 IllegalStateException
     * （user 表现为播放按钮点击后报 what=-38），因此必须先判断状态。
     */
    public void resume() {
        if (mediaPlayer == null) return;
        if (isPreparing) {
            Log.d(TAG, "resume() 忽略：当前正在缓冲，准备完成后会自动播放");
            return;
        }
        try {
            if (!mediaPlayer.isPlaying()) {
                mediaPlayer.start();
                notifyPlayStateChanged(true);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "resume() 状态非法，已忽略: " + e.getMessage());
        }
    }

    /** 切换播放/暂停。缓冲期间忽略，避免 IllegalStateException */
    public void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (isPreparing) {
            Log.d(TAG, "togglePlayPause() 忽略：当前正在缓冲");
            return;
        }
        if (isPlaying()) {
            pause();
        } else {
            resume();
        }
    }

    public void stop() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "stop() 状态非法，已忽略: " + e.getMessage());
            }
            try {
                mediaPlayer.reset();
            } catch (IllegalStateException e) {
                Log.w(TAG, "reset() 状态非法，已忽略: " + e.getMessage());
            }
            initMediaPlayer(); // 重新初始化 MediaPlayer
            rlog.i(TAG, "stop(): 已重建 MediaPlayer, hashCode="
                    + (mediaPlayer == null ? "null" : mediaPlayer.hashCode()));
            notifyPlayStateChanged(false);
        }
        // 释放预加载的下一首，避免重建 MediaPlayer 后旧实例残留。
        // setNextMediaPlayer 的接收方由当前 mediaPlayer 持有，
        // reset() 后不再引用，必须手动 release。
        releasePreloadedPlayer();
    }

    /** 释放预加载实例（幂等） */
    private void releasePreloadedPlayer() {
        if (preloadedPlayer != null) {
            try {
                preloadedPlayer.release();
            } catch (Exception ignored) {
            }
            preloadedPlayer = null;
        }
    }
    
    /**
     * 播放下一首。
     *
     * 与 playFile 的分工：本方法负责推进 currentPosition，然后调用
     * playFile 播放。playFile 内部只在【位置不符】时才纠正 currentPosition，
     * 避免两者互相覆盖导致索引漂移。
     */
    public void playNext() {
        rlog.i(TAG, "playNext 进入: pos=" + currentPosition
                + " size=" + (playlist == null ? -1 : playlist.size()));
        if (playlist == null || playlist.isEmpty()) return;
        if (currentPosition < 0 || currentPosition >= playlist.size()) {
            currentPosition = 0;
        } else {
            currentPosition = (currentPosition + 1) % playlist.size();
        }
        playFile(playlist.get(currentPosition));
    }

    /** 播放上一首 */
    public void playPrevious() {
        if (playlist == null || playlist.isEmpty()) return;
        if (currentPosition < 0 || currentPosition >= playlist.size()) {
            currentPosition = 0;
        } else {
            currentPosition = (currentPosition - 1 + playlist.size()) % playlist.size();
        }
        playFile(playlist.get(currentPosition));
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
        releaseTransitionWakeLock();
        abandonAudioFocus();

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
