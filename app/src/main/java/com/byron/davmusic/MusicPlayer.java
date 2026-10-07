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

    /**
     * 主动中断标记。
     *
     * 背景：stop()/切歌会强制中断正在播放的数据流，MediaPlayer 必然回调
     * onError(what=-38, MEDIA_ERROR_UNKNOWN)。这属于【预期内的中断】，
     * 不是真故障 —— 但若照常上报，用户每次切歌都会看到两条错误提示
     * （一条来自当前实例，一条来自被释放的预加载实例）。
     *
     * 用这个标记区分"我方主动中断"与"真实播放失败"。
     */
    private volatile boolean intentionalStop = false;
    
    private List<OnPlaybackListener> listeners = new ArrayList<>();
    /** 远程日志：只记录，不影响播放逻辑 */
    private RemoteLogger rlog;

    // ---- 播放模式（顺序 / 列表循环 / 单曲循环 / 随机） ----

    /** 顺序播放：播到列表末尾即停止 */
    public static final int MODE_SEQUENTIAL = 0;
    /** 列表循环：播到末尾回到第一首。默认值 —— 保持本次改动之前的既有行为 */
    public static final int MODE_LIST_LOOP = 1;
    /** 单曲循环：同一首反复播放 */
    public static final int MODE_SINGLE_LOOP = 2;
    /** 随机播放：每次挑一首与当前不同的 */
    public static final int MODE_SHUFFLE = 3;
    /** 模式总数，UI 按它循环切换 */
    public static final int MODE_COUNT = 4;

    private static final String PREF_NAME = "player_prefs";
    private static final String PREF_KEY_PLAY_MODE = "play_mode";

    /** 随机源。只被 nextIndexOf() 使用 */
    private static final java.util.Random RANDOM = new java.util.Random();

    private int playMode = MODE_LIST_LOOP;

    /**
     * 已挂载的预加载实例在 playlist 中的下标（-1 = 没有）。
     *
     * 为什么必须记下来：播完时不能再按 "currentPosition + 1" 推进 ——
     * 随机模式下预加载的是随机挑的那一首，+1 会让【状态与声音对不上】
     * （界面显示 A，耳朵里是 B），下一次切歌还会从错的位置继续。
     */
    private int preloadedPosition = -1;

    /**
     * 正在 prepareAsync 中的预加载目标下标（-1 = 没有在途的）。
     *
     * 和 preloadedPosition 的区别：一个是"已在途"，一个是"已挂上"。
     * 切换模式时要用它俩判断【新目标是否与原有目标相同】—— 相同就不动，
     * 避免白白重拉一路流（服务端对请求量很敏感，见 AGENTS.md 网络策略）。
     */
    private int preparingPosition = -1;

    /**
     * 预加载代号。
     *
     * prepareAsync() 是异步的：切换播放模式会取消旧目标并重挂新目标，
     * 但旧目标可能【正在 prepare】。若不加代号，旧目标的 onPrepared 会在
     * 新目标之后挂上去，把已经算好的下一首换回旧模式的曲目。
     * 每次重新计算目标（含取消）都自增，回调里对不上就丢弃自己。
     */
    private int preloadGeneration = 0;

    /**
     * 已取消、但暂时不宜立刻 release 的预加载实例。
     *
     * 为什么不当场释放：setNextMediaPlayer(null) 之后，native 层的切换
     * 有可能【刚刚开始】—— 此刻 release 会掐断刚开始播的那一首。
     * 因此先记为待回收，等下一次 stop()/切歌/释放时再真正回收。
     * 同一时刻最多只有一个，开销可忽略。
     */
    private android.media.MediaPlayer pendingReleasePlayer;

    public interface OnPlaybackListener {
        void onTrackChanged(WebDAVFile track);
        void onPlayStateChanged(boolean isPlaying);
        void onProgress(int position, int duration);
        /** 播放模式变化（UI 据此换图标；见 MODE_* 常量） */
        void onPlayModeChanged(int mode);
        void onError(String error);
    }
    
    private MusicPlayer(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.rlog = RemoteLogger.getInstance(this.context);
        rlog.i(TAG, "MusicPlayer 初始化");
        loadPlayMode();
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

        bindCurrentPlayerListeners();

        // 设置进度更新定时器
        handler.postDelayed(progressUpdater, 1000);
    }
    /**
     * 给当前的 mediaPlayer 绑定所有监听器。
     *
     * 抽成独立方法的原因：采用 setNextMediaPlayer 后，播完时 native 层会把
     * 预加载的那个实例"顶上"成为当前播放器（见 onCompletion）。新实例必须
     * 绑定同一套监听器，否则下一首播完就再也不会触发 onCompletion，
     * 自动切歌链条在第一首之后就断了。
     */
    private void bindCurrentPlayerListeners() {
        final android.media.MediaPlayer mpCur = mediaPlayer;
        if (mpCur == null) return;

        mpCur.setOnPreparedListener(mp -> {
            isPreparing = false;
            Log.d(TAG, "MediaPlayer prepared, starting playback");
            mp.start();
            // 播放模式：新建的 MediaPlayer 循环标记默认为关，
            // 每次准备完成都要按当前模式重设一遍（单曲循环靠它无缝重播）
            applyLoopingTo(mp);
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
        
        mpCur.setOnCompletionListener(mp -> {
            Log.d(TAG, "Playback completed");
            logState("onCompletion 触发");

            final boolean wasPreloaded = (preloadedPlayer != null);
            rlog.i(TAG, "onCompletion: currentPosition=" + currentPosition
                    + " playlistSize=" + (playlist == null ? -1 : playlist.size())
                    + " hasPreloaded=" + wasPreloaded);

            // 关于为什么要挪出回调：play() 会 stop() → reset() + 重建 MediaPlayer，
            // 在回调内部销毁回调的宿主会导致时序错乱。
            handler.post(() -> {
                rlog.i(TAG, "onCompletion handler 执行 (post 未被延迟丢弃)");

                // 【播放模式】单曲循环走 MediaPlayer 原生循环：循环期间
                // onCompletion 根本不会触发。这里是竞态兜底 —— 若刚好在
                // 末尾才切到单曲循环，重播当前这首，而不是继续换歌。
                if (playMode == MODE_SINGLE_LOOP) {
                    rlog.i(TAG, "单曲循环兜底：重播当前曲目");
                    if (playlist != null && currentPosition >= 0
                            && currentPosition < playlist.size()) {
                        playFile(playlist.get(currentPosition));
                    } else {
                        notifyPlayStateChanged(false);
                    }
                    return;
                }

                if (playlist == null || playlist.size() <= 1) {
                    // 注意：单曲循环已在上面处理，所以走到这里说明
                    // "列表只有一首且非单曲循环" —— 保持原有行为：播完停止。
                    // （列表循环下想反复听这一首，请选单曲循环）
                    rlog.i(TAG, "单曲或空列表，停止切歌");
                    notifyPlayStateChanged(false);
                    return;
                }

                if (wasPreloaded) {
                    // 已预加载：MediaPlayer 在 native 层已经完成了切换，
                    // 新的音频流正在播放。此时绝不能调用 playNext() ——
                    // 它会走 play() → stop() → releasePreloadedPlayer()，
                    // 把刚接手播放的那个实例销毁掉，导致"播完就静音"。
                    //
                    // 这里只做状态同步：推进索引、通知 UI、再预加载下下一首。
                    rlog.i(TAG, "预加载已接管播放，仅同步状态（不重建 MediaPlayer）");

                    // 推进到【预加载时按当时模式算出的那一首】，而不是 +1：
                    // 随机模式下 +1 会让界面显示的曲目与实际播放的声音对不上
                    if (preloadedPosition >= 0 && preloadedPosition < playlist.size()) {
                        currentPosition = preloadedPosition;
                    } else {
                        currentPosition = (currentPosition + 1) % playlist.size();
                    }
                    preloadedPosition = -1;

                    // 接手播放的实例已成为当前实例
                    mediaPlayer = preloadedPlayer;
                    preloadedPlayer = null;
                    bindCurrentPlayerListeners();
                    // 新实例的循环标记默认为关，按当前模式重设
                    applyLoopingToCurrentPlayer();

                    WebDAVFile now = playlist.get(currentPosition);
                    currentUrl = resolvePlayUrl(now);
                    notifyTrackChanged(now);
                    notifyPlayStateChanged(true);

                    // 为再下一首做预加载，保持流水线不断
                    handler.postDelayed(this::prepareNextTrack, 1500);
                    return;
                }

                // 没有预加载（首次播放、预加载失败、或模式使然）：按当前模式算下一首。
                // 注意这里【不能】调 playNext() —— 那是"用户主动切歌"的语义，
                // 它一定会换一首；而顺序播放到末尾时我们必须停下来。
                int nextPos = nextIndexOf(playMode, currentPosition, playlist.size(), false);
                if (nextPos < 0) {
                    rlog.i(TAG, "顺序播放已到末尾，停止切歌");
                    notifyPlayStateChanged(false);
                    return;
                }
                rlog.i(TAG, "无预加载，按模式切到下标 " + nextPos);
                currentPosition = nextPos;
                playFile(playlist.get(currentPosition));
            });
        });
        
        mpCur.setOnErrorListener((mp, what, extra) -> {
            isPreparing = false;

            // 区分"主动中断"与"真实失败"。
            //
            // stop()/切歌会强制中断数据流，MediaPlayer 必然回报
            // what=-38（MEDIA_ERROR_UNKNOWN）。以正常播放为前提，
            // 这种中断不应打扰用户 —— 只有非主动中断时才上报。
            if (intentionalStop) {
                Log.d(TAG, "忽略主动中断产生的 onError: what=" + what
                        + " extra=" + extra);
                return true;
            }

            String detail = describeMediaError(what, extra);
            String error = "播放错误: " + detail;
            Log.e(TAG, error + " | url=" + currentUrl);
            rlog.e(TAG, error + " | url=" + currentUrl);
            // 播放失败：切歌窗口结束，释放临时 WakeLock 防止泄漏
            releaseTransitionWakeLock();
            notifyError(error);
            return true;
        });

        mpCur.setOnInfoListener((mp, what, extra) -> {
            // 记录流媒体缓冲区信息，便于诊断弱网卡顿
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                Log.d(TAG, "缓冲开始");
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                Log.d(TAG, "缓冲结束");
            }
            return false;
        });
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

    /**
     * 当前这一首拉流使用的认证头。
     *
     * 【多服务器】播放队列可能跨服务器，因此这份凭据在每次 playFile 时
     * 由 applyAuthHeadersFor() 按「这一首所属的服务器」刷新；
     * authHeadersFor() 负责按条目计算，两者分工明确。
     */
    private Map<String, String> authHeaders;

    /**
     * 取某个条目所属服务器的认证头。
     *
     * 【多服务器】不能再用一个全局 authHeaders —— 播放队列可能跨服务器，
     * 每首歌的认证头必须来自它自己所属的那台服务器，否则拉流会 401
     * （或用错账号的凭据）。返回 null 表示无法确定（调用方走无认证头分支）。
     */
    private Map<String, String> authHeadersFor(WebDAVFile file) {
        if (file == null) return null;
        ServerProfile p = findServer(file.getServerId());
        if (p == null || !p.isComplete()) {
            // 条目没带服务器信息（旧快照）：退回当前正在播放那首的凭据
            return authHeaders;
        }
        String credentials = (p.getUsername() == null ? "" : p.getUsername())
                + ":" + (p.getPassword() == null ? "" : p.getPassword());
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Basic " + android.util.Base64.encodeToString(
                credentials.getBytes(), android.util.Base64.NO_WRAP));
        return h;
    }

    /**
     * 为即将播放的条目刷新 authHeaders。
     * @return 计算出的认证头
     */
    private Map<String, String> applyAuthHeadersFor(WebDAVFile file) {
        Map<String, String> h = authHeadersFor(file);
        if (h != null) {
            this.authHeaders = h;
        }
        return this.authHeaders;
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
                // 在线播放：带上「这一首所属服务器」的认证头。
                // authHeaders 已由 playFile → applyAuthHeadersFor() 按条目刷新。
                Uri uri = Uri.parse(url);
                if (authHeaders != null && authHeaders.get("Authorization") != null) {
                    mp.setDataSource(context, uri, authHeaders);
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
        if (playlist == null || playlist.isEmpty()) return;
        if (currentPosition < 0 || currentPosition >= playlist.size()) return;

        // 目标由【当前模式】决定：随机模式挑随机的一首；单曲循环与
        // "顺序播放的最后一首"返回 -1，即不预加载（前者交给 setLooping
        // 原生无缝重播，后者本来就该停）。
        final int nextPos = nextIndexOf(playMode, currentPosition, playlist.size(), false);
        if (nextPos < 0) {
            rlog.i(TAG, "按当前模式没有下一首，不预加载: mode=" + modeName(playMode));
            return;
        }

        // 本次预加载的代号：模式切换会取消旧目标并重挂，
        // 旧目标的 onPrepared 靠它识别出"我已经过期了"
        final int gen = ++preloadGeneration;
        preparingPosition = nextPos;

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
                // 【多服务器】预加载的下一首可能属于另一台服务器，
                // 必须用它自己的凭据，不能用「当前正在播放那首」的
                Map<String, String> nextAuth = authHeadersFor(nextTrack);
                if (nextAuth != null && !nextAuth.isEmpty()) {
                    next.setDataSource(context, uri, nextAuth);
                } else {
                    next.setDataSource(context, uri);
                }
            }

            final String nextName = nextTrack.getDisplayName();

            // 必须等 prepare 完成才能交给 setNextMediaPlayer
            next.setOnPreparedListener(prepared -> {
                try {
                    // 过期检查①：本次预加载是否已被取消/重挂（例如用户切了播放模式）。
                    // 注意这里要【先】判代号：过期说明已有更新的预加载接管了
                    // preparingPosition，此时不能去动它。
                    if (gen != preloadGeneration) {
                        Log.d(TAG, "预加载已过期（模式或目标已变），丢弃: " + nextName);
                        prepared.release();
                        return;
                    }
                    if (mediaPlayer == null) {
                        clearPreparingPosition(nextPos);
                        prepared.release();
                        return;
                    }
                    // 过期检查②：当前曲目仍未切换（避免用户已手动切歌后误挂）
                    String expect = resolvePlayUrl(
                            playlist != null && currentPosition >= 0
                                    && currentPosition < playlist.size()
                                    ? playlist.get(currentPosition) : null);
                    if (expect == null || !expect.equals(currentUrl)) {
                        Log.d(TAG, "当前曲目已变化，丢弃预加载结果: " + nextName);
                        clearPreparingPosition(nextPos);
                        prepared.release();
                        return;
                    }
                    mediaPlayer.setNextMediaPlayer(prepared);
                    preloadedPlayer = prepared;
                    // 记下"这一首在列表里的位置"：播完时按它推进索引，
                    // 随机模式下才能让界面与实际播放保持一致
                    preloadedPosition = nextPos;
                    clearPreparingPosition(nextPos);
                    Log.d(TAG, "已预加载下一首: " + nextName);
                    rlog.i(TAG, "预加载成功: " + nextName + " (下标 " + nextPos + ")");
                } catch (Exception ex) {
                    rlog.w(TAG, "挂载预加载失败: " + ex.getClass().getSimpleName()
                            + " / " + ex.getMessage());
                    clearPreparingPosition(nextPos);
                    try { prepared.release(); } catch (Exception ignored) {}
                    preloadedPlayer = null;
                }
            });

            next.setOnErrorListener((mp, what, extra) -> {
                // 预加载实例在切歌时会被主动 release，必然回报错误；
                // 这里只记调试日志，不上报 UI（用户不该看到预加载失败）
                Log.d(TAG, "预加载播放器 onError（多为主动释放所致）: what="
                        + what + " extra=" + extra + " (" + nextName + ")");
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
     *
     * 【多服务器】取流地址来自【条目自己记录的服务器】，而不是「当前浏览的
     * 服务器」。这是「服务器即根目录」能成立的关键：播放队列可以跨服务器
     * 共存，点下一首仍会去正确的那台取流。
     * 服务器已删除时返回 null，由调用方按「无法播放」处理。
     */
    private String resolvePlayUrl(WebDAVFile file) {
        if (file == null) return null;
        LocalCacheManager cm = LocalCacheManager.getInstance(context);

        // ① 本地副本优先。缓存键带服务器 id，天然不会串到别的服务器
        String key = file.getCacheKey();
        if (cm.isDownloaded(key)) {
            File local = cm.getLocalFile(key);
            if (local != null && local.exists()) {
                return "file://" + local.getAbsolutePath();
            }
        }

        // ② 远端流：用条目所属服务器的客户端
        WebDAVClient c = clientFor(file);
        if (c == null) {
            rlog.w(TAG, "无法解析播放地址：条目所属服务器已不存在 serverId=" + file.getServerId());
            return null;
        }
        return c.getDownloadUrl(file);
    }

    /**
     * 取「这个条目所属服务器」的客户端。
     * 条目没带服务器信息时退回旧的单例（兼容旧快照/旧播放列表）。
     */
    private WebDAVClient clientFor(WebDAVFile file) {
        if (file != null && file.getServerId() != null && !file.getServerId().isEmpty()) {
            ServerProfile p = findServer(file.getServerId());
            if (p != null && p.isComplete()) {
                return WebDAVClient.forServer(p);
            }
            return null;    // 服务器被删了，明确失败好过用错服务器
        }
        return WebDAVClient.getInstance();
    }

    /** 按 id 找服务器配置 */
    private ServerProfile findServer(String serverId) {
        if (serverId == null) return null;
        List<ServerProfile> all = ServerStore.list(context);
        for (ServerProfile p : all) {
            if (serverId.equals(p.getId())) return p;
        }
        return null;
    }

    /**
     * 把条目按【所属服务器】分包。
     *
     * setPlaylist 的入参是「当前目录的文件列表」，现在服务器作为根目录的
     * 第一层，同一个列表里理论上只含一台服务器的条目；但为稳妥起见，
     * 仍按 serverId 分组后分别取流（旧快照可能带空 serverId）。
     */
    private Map<String, WebDAVClient> clientCache = new HashMap<>();

    private WebDAVClient clientForCached(WebDAVFile file) {
        String sid = (file == null || file.getServerId() == null) ? "" : file.getServerId();
        WebDAVClient c = clientCache.get(sid);
        if (c == null) {
            c = clientFor(file);
            if (c != null) clientCache.put(sid, c);
        }
        return c;
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
                + " | server=" + file.getServerName());

        // 取流地址统一交给 resolvePlayUrl：它按【条目所属服务器】解析，
        // 本地副本与远端流的分支都在那里，避免两处逻辑走偏
        String url = resolvePlayUrl(file);
        if (url == null) {
            rlog.w(TAG, "无法解析播放地址，放弃播放: " + file.getDisplayName());
            notifyError("无法播放：该曲目所属的服务器已不存在");
            return;
        }
        // 【多服务器】认证头必须来自这一首所属的服务器：
        // 播放队列可能跨服务器，全局一组凭据会在切歌时 401
        applyAuthHeadersFor(file);
        Log.d(TAG, "播放地址: " + url);
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
        return isSameTrack(a, b);
    }

    /**
     * 判断两个条目是不是同一首歌（公开静态，供 UI 层复用）。
     *
     * 先比 href（服务器上的绝对路径），再退回 relativePath —— 两者任一相同
     * 即认为是同一首。href 可能因服务器返回的编码差异而不同，所以
     * 不能只靠 href。
     */
    public static boolean isSameTrack(WebDAVFile a, WebDAVFile b) {
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
            // 标记主动中断：期间产生的 onError 属于预期行为，不上报
            intentionalStop = true;
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

        // 中断动作已完成，恢复正常错误上报。
        // 延迟一点再清除：release/stop 触发的 onError 可能在稍后派发。
        handler.postDelayed(() -> intentionalStop = false, 300);
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
        preloadedPosition = -1;
        preparingPosition = -1;
        // 让仍在 prepareAsync 中的目标作废：此时 mediaPlayer 已被 reset/重建，
        // 旧目标再挂上来只会挂到一个 Idle 实例上
        preloadGeneration++;
        // 停止/切歌意味着切换窗口已经过去，之前"不敢当场释放"的实例现在可以回收
        recyclePendingRelease();
    }

    /** 清掉"在途"标记；只在确认这个下标仍归本次预加载所有时调用 */
    private void clearPreparingPosition(int pos) {
        if (preparingPosition == pos) preparingPosition = -1;
    }

    /**
     * 取消已挂载的预加载，但【不打断当前正在播的这一首】。
     *
     * 依据官方文档：setNextMediaPlayer(null) 表示"播完不接下一首"。
     * 切换播放模式时必须调用 —— 已挂载的下一首是按【旧模式】挑的：
     * 从列表循环切到单曲循环时若不清掉，播完仍会照样换歌，
     * 用户看到的就是"选了单曲循环却还是跳走了"。
     */
    private void cancelPreloadedTrack() {
        if (preloadedPlayer != null && mediaPlayer != null) {
            try {
                mediaPlayer.setNextMediaPlayer(null);
            } catch (Exception e) {
                Log.w(TAG, "取消预加载失败: " + e.getMessage());
                rlog.w(TAG, "取消预加载失败: " + e.getMessage());
            }
        }

        // 让仍在 prepareAsync 中的旧目标作废（见 preloadGeneration）
        preloadGeneration++;
        preloadedPosition = -1;
        preparingPosition = -1;

        // 当场 release 有风险：native 层的切换可能刚刚开始。
        // 先记为待回收，交给下一个安全点（stop/切歌/释放）处理。
        if (preloadedPlayer != null) {
            recyclePendingRelease();
            pendingReleasePlayer = preloadedPlayer;
            preloadedPlayer = null;
        }
    }

    /** 回收"待回收"的预加载实例（此刻它一定已经不在切换窗口内了） */
    private void recyclePendingRelease() {
        if (pendingReleasePlayer != null) {
            try {
                pendingReleasePlayer.release();
            } catch (Exception ignored) {
            }
            pendingReleasePlayer = null;
        }
    }

    // ---- 播放模式：查询与切换 ----

    public int getPlayMode() {
        return playMode;
    }

    /** 切到下一个模式（顺序 → 列表循环 → 单曲循环 → 随机 → 顺序），返回切换后的模式 */
    public int cyclePlayMode() {
        setPlayMode((playMode + 1) % MODE_COUNT);
        return playMode;
    }

    /**
     * 切换播放模式，并让它【立即】对正在播的这一首生效。
     *
     * 为什么必须立即生效：否则用户会看到"选了单曲循环却还是换歌了"、
     * "切回列表循环却还在单曲里打转"。做法两步：
     *   ① 单曲循环交给 MediaPlayer.setLooping(true)，native 层无缝重播，
     *      循环点不需要重新请求网络；离开单曲循环时关掉它，
     *      末尾的 onCompletion 才会照常触发（文档：循环中不会启动 next player）。
     *   ② 其余模式之间切换时，已挂载的下一首可能是按旧模式挑的，
     *      必须取消（setNextMediaPlayer(null)）再按新模式重挂 ——
     *      但仅在【新目标与原有目标不同】时才做，免得白白多拉一路流。
     */
    public void setPlayMode(int mode) {
        if (mode < 0 || mode >= MODE_COUNT || mode == playMode) return;

        int old = playMode;
        playMode = mode;
        persistPlayMode();
        rlog.i(TAG, "播放模式切换: " + modeName(old) + " → " + modeName(mode));

        // 新模式想要的下一首
        int size = (playlist == null) ? 0 : playlist.size();
        int desired = nextIndexOf(playMode, currentPosition, size, false);
        // 当前"已挂上或正在准备"的目标（两者最多只有一个有效）
        int currentTarget = (preloadedPosition >= 0) ? preloadedPosition : preparingPosition;

        if (desired != currentTarget) {
            // 旧目标作废，按新模式重挂
            cancelPreloadedTrack();
            if (desired >= 0) {
                prepareNextTrack();
            }
        } else {
            // 目标没变就别动预加载 —— 重挂会重新拉一路流，
            // 服务端对请求量敏感（见 AGENTS.md 的网络策略）
            rlog.i(TAG, "下一首目标未变(下标 " + desired + ")，沿用已有预加载");
        }

        // 循环标记每次都要重设：进单曲循环要开，离开要关
        applyLoopingToCurrentPlayer();
        notifyPlayModeChanged(playMode);
    }

    /**
     * 计算"下一首"的下标。
     *
     * 做成纯静态函数（可单测）而不是散在两个调用点里，是因为它必须同时
     * 服务于【预加载】与【播完切歌】两处：任何一处算法不一致，就会出现
     * "预加载的是 A、状态推进到 B"这类错位。
     *
     * @param mode          播放模式（MODE_* 常量）
     * @param currentPos    当前下标
     * @param size          播放列表长度
     * @param userInitiated true = 用户主动点「下一首」/ 蓝牙双击
     *                      false = 一首播完自动接续
     * @return 下一首下标；-1 表示按当前模式不应继续（顺序播放到底、单曲循环自动接续）
     */
    public static int nextIndexOf(int mode, int currentPos, int size, boolean userInitiated) {
        if (size <= 0) return -1;
        if (currentPos < 0 || currentPos >= size) return 0;

        // 单曲循环：自动接续由 setLooping 在 native 层完成，这里不作为。
        // 但用户主动点「下一首」应当真的换一首，否则按钮像坏的。
        if (mode == MODE_SINGLE_LOOP && !userInitiated) return -1;

        if (size == 1) {
            // 列表只有一首：手动切歌只能回到它自己；
            // 自动接续返回 -1，保持"单曲播完即停"的原有行为
            //（想反复听这一首，请选单曲循环）
            return userInitiated ? 0 : -1;
        }

        if (mode == MODE_SHUFFLE) {
            // 随机：避开当前这一首（列表 ≥2 首，一定能挑到别的）
            int idx;
            do {
                idx = RANDOM.nextInt(size);
            } while (idx == currentPos);
            return idx;
        }

        int next = currentPos + 1;
        if (next >= size) {
            // 到末尾：顺序播放（自动）停止，其余情况回到开头
            if (mode == MODE_SEQUENTIAL && !userInitiated) return -1;
            return 0;
        }
        return next;
    }

    /** 模式名（仅用于日志与远程诊断；界面文案见 strings.xml 的 play_mode_*） */
    public static String modeName(int mode) {
        switch (mode) {
            case MODE_SEQUENTIAL: return "顺序播放";
            case MODE_LIST_LOOP: return "列表循环";
            case MODE_SINGLE_LOOP: return "单曲循环";
            case MODE_SHUFFLE: return "随机播放";
            default: return "未知模式(" + mode + ")";
        }
    }

    /**
     * 把当前模式要求的循环标记写到指定实例上。
     *
     * 必须每个实例都重设：新建的 MediaPlayer 循环标记默认为关，
     * 而切歌/切模式都会重建或换用实例（含 setNextMediaPlayer 顶上来的那个）。
     */
    private void applyLoopingTo(android.media.MediaPlayer mp) {
        if (mp == null) return;
        boolean shouldLoop = (playMode == MODE_SINGLE_LOOP);
        try {
            mp.setLooping(shouldLoop);
        } catch (IllegalStateException e) {
            // Idle/Error 状态下不允许设置。不必补救 ——
            // 下次 onPrepared 会按当时的模式再设一遍。
            Log.d(TAG, "setLooping 当前状态不允许，稍后重设: " + e.getMessage());
        }
    }

    private void applyLoopingToCurrentPlayer() {
        applyLoopingTo(mediaPlayer);
    }

    private void persistPlayMode() {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_KEY_PLAY_MODE, playMode)
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "保存播放模式失败: " + e.getMessage());
        }
    }

    /** 读取上次选择的播放模式（默认列表循环 = 本功能之前的既有行为） */
    private void loadPlayMode() {
        int saved = MODE_LIST_LOOP;
        try {
            saved = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getInt(PREF_KEY_PLAY_MODE, MODE_LIST_LOOP);
        } catch (Exception e) {
            Log.w(TAG, "读取播放模式失败，用默认值: " + e.getMessage());
        }
        if (saved < 0 || saved >= MODE_COUNT) saved = MODE_LIST_LOOP;
        playMode = saved;
        rlog.i(TAG, "播放模式: " + modeName(playMode));
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
                + " size=" + (playlist == null ? -1 : playlist.size())
                + " mode=" + modeName(playMode));
        if (playlist == null || playlist.isEmpty()) return;

        // 用户主动切歌：任何模式下都要真的换一首（单曲循环下也是），
        // 否则按钮会像坏的。随机模式下这里就是"随机下一首"。
        int nextPos = nextIndexOf(playMode, currentPosition, playlist.size(), true);
        if (nextPos < 0) return;
        currentPosition = nextPos;
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

    private void notifyPlayModeChanged(int mode) {
        for (OnPlaybackListener listener : listeners) {
            listener.onPlayModeChanged(mode);
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

        // 当前实例已释放，预加载实例（含"待回收"那个）此时释放是安全的
        releasePreloadedPlayer();
        
        listeners.clear();
        instance = null;
    }
}
