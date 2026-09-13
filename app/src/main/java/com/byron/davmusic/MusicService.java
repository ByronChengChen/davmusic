package com.byron.davmusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * 音乐播放前台服务。
 *
 * 存在的理由：应用切后台/锁屏后，普通后台进程随时可能被系统回收，
 * 表现为播放中断、Activity 被销毁重建（回到首页）。前台服务带常驻通知，
 * 进程优先级提升到等同"前台"，系统基本不会回收，从而实现稳定保活。
 *
 * 设计取舍（有意为之）：
 *   播放逻辑仍由 MusicPlayer 单例持有，本服务不复制一份播放器。
 *   原因：MusicPlayer 已实现预加载切歌、WakeLock、音频焦点、状态回调等
 *   完整逻辑（约 900 行）。把它整体搬进 Service 需要重写大量代码且易引入
 *   回归。这里改为"服务负责保活与通知，播放器负责播放"，两者通过
 *   MusicPlayer 的监听接口协作 —— 改动面最小，风险最低。
 *
 * 生命周期：startForegroundService() 启动，stopSelf() 结束。
 * 播放开始即进入前台；暂停时保留通知（方便继续播放），
 * 播放停止/队列结束才退出前台。
 */
public class MusicService extends Service
        implements MusicPlayer.OnPlaybackListener {

    private static final String TAG = "MusicService";

    public static final String CHANNEL_ID = "davmusic_playback";
    private static final int NOTIFICATION_ID = 1001;

    /** 通知栏操作 */
    public static final String ACTION_TOGGLE = "com.byron.davmusic.TOGGLE";
    public static final String ACTION_NEXT = "com.byron.davmusic.NEXT";
    public static final String ACTION_PREV = "com.byron.davmusic.PREV";
    public static final String ACTION_STOP = "com.byron.davmusic.STOP";

    private MusicPlayer player;
    private RemoteLogger rlog;
    private boolean isForeground = false;

    /**
     * 媒体会话。
     *
     * 作用：接收蓝牙耳机/线控/锁屏等外部媒体按键。
     * 必须【激活】（setActive(true)），系统才会把媒体按键路由给本应用。
     *
     * 关于按键语义：
     *   单击 → 播放/暂停，双击 → 下一首，均为 Android 标准，
     *   由系统或耳机固件转换成 MediaSession 回调。
     *   三击【没有统一标准】—— 是否上报、上报成什么，取决于耳机固件。
     *   这里把 KEYCODE_MEDIA_PREVIOUS / KEYCODE_MEDIA_PREVIOUS 一并注册，
     *   若耳机上报上一首键就能响应。
     */
    private android.media.session.MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        rlog = RemoteLogger.getInstance(this);
        rlog.i(TAG, "MusicService onCreate");

        createChannel();
        initMediaSession();

        player = MusicPlayer.getInstance(this);
        player.addPlaybackListener(this);

        // 服务创建即进入前台，避免被系统判定为后台服务而快速回收。
        // 即使此刻还没开始播放，也要先启动前台（Android 要求
        // startForegroundService 后 5 秒内必须调用 startForeground）。
        startForegroundCompat(buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        rlog.i(TAG, "onStartCommand action=" + action);

        if (action != null) {
            switch (action) {
                case ACTION_TOGGLE:
                    player.togglePlayPause();
                    break;
                case ACTION_NEXT:
                    player.playNext();
                    break;
                case ACTION_PREV:
                    player.playPrevious();
                    break;
                case ACTION_STOP:
                    player.stop();
                    stopSelfSafely();
                    return START_NOT_STICKY;
                default:
                    break;
            }
        }

        // START_STICKY：被系统杀掉后自动重建，配合前台通知减少中断
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 不使用绑定：Activity 通过 MusicPlayer 单例直接取状态，
        // 服务只负责保活与通知，避免绑定生命周期带来的复杂度。
        return null;
    }

    @Override
    public void onDestroy() {
        rlog.i(TAG, "MusicService onDestroy");
        if (player != null) {
            player.removePlaybackListener(this);
        }
        // 释放媒体会话，避免占用耳机按键路由
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Exception ignored) {
            }
            mediaSession = null;
        }
        stopForegroundCompat();
        super.onDestroy();
    }

    // ---------- 媒体会话（蓝牙耳机/线控/锁屏按键） ----------

    /**
     * 初始化并激活 MediaSession。
     *
     * 关键点：
     *  1. 必须 setActive(true)，系统才会把媒体按键路由到本应用。
     *     未激活时耳机按键会被别的应用（或系统默认播放器）抢走。
     *  2. 同时设置 PlaybackState 的 actions 位掩码 —— 系统根据它决定
     *     哪些按键事件该转发过来（例如不含 ACTION_SKIP_TO_NEXT 时，
     *     双击耳机不会触发下一首）。
     *  3. 回调里统一走 MusicPlayer，保证与界面按钮行为一致。
     */
    private void initMediaSession() {
        try {
            mediaSession = new android.media.session.MediaSession(this, "DavMusic");

            mediaSession.setCallback(new android.media.session.MediaSession.Callback() {

                @Override
                public void onPlay() {
                    rlog.i(TAG, "媒体按键: onPlay");
                    player.resume();
                }

                @Override
                public void onPause() {
                    rlog.i(TAG, "媒体按键: onPause");
                    player.pause();
                }

                /**
                 * 单击耳机键：系统会先给 onPlay/onPause；
                 * 部分设备/固件也可能直接送 TOGGLE_PLAYBACK，
                 * 因此这里也做完整处理。
                 */
                @Override
                public void onSkipToNext() {
                    rlog.i(TAG, "媒体按键: onSkipToNext（双击/下一首）");
                    player.playNext();
                }

                @Override
                public void onSkipToPrevious() {
                    rlog.i(TAG, "媒体按键: onSkipToPrevious（上一首）");
                    player.playPrevious();
                }

                @Override
                public void onStop() {
                    rlog.i(TAG, "媒体按键: onStop");
                    player.stop();
                }

                /**
                 * 原始按键兜底。
                 *
                 * 三击在不同耳机上可能上报为 KEYCODE_MEDIA_PREVIOUS，
                 * 也可能上报为其它码。这里直接检查 KeyEvent，
                 * 尽量把上一首/下一首识别出来。
                 */
                @Override
                public boolean onMediaButtonEvent(android.content.Intent mediaButtonIntent) {
                    try {
                        if (mediaButtonIntent == null) return super.onMediaButtonEvent(mediaButtonIntent);
                        android.view.KeyEvent ev = mediaButtonIntent.getParcelableExtra(
                                android.content.Intent.EXTRA_KEY_EVENT);
                        if (ev == null) return super.onMediaButtonEvent(mediaButtonIntent);

                        // 只处理按下，避免抬升造成重复触发
                        if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                            return true;
                        }

                        int code = ev.getKeyCode();
                        rlog.i(TAG, "媒体按键原始事件: keyCode=" + code);

                        switch (code) {
                            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            case android.view.KeyEvent.KEYCODE_HEADSETHOOK:
                                player.togglePlayPause();
                                return true;
                            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                                player.playNext();
                                return true;
                            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                                player.playPrevious();
                                return true;
                            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
                                player.resume();
                                return true;
                            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                                player.pause();
                                return true;
                            case android.view.KeyEvent.KEYCODE_MEDIA_STOP:
                                player.stop();
                                return true;
                            default:
                                return super.onMediaButtonEvent(mediaButtonIntent);
                        }
                    } catch (Exception e) {
                        rlog.w(TAG, "onMediaButtonEvent 异常: " + e.getMessage());
                        return super.onMediaButtonEvent(mediaButtonIntent);
                    }
                }
            });

            mediaSession.setActive(true);
            updateMediaSessionState();
            rlog.i(TAG, "MediaSession 已激活（蓝牙耳机按键可控制）");

        } catch (Exception e) {
            rlog.e(TAG, "MediaSession 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 同步播放状态到 MediaSession。
     *
     * 这一步是耳机按键能生效的前提：系统读取 PlaybackState 的 actions
     * 决定哪些媒体按键要转发给本应用。必须同时声明 PLAY、PAUSE、
     * SKIP_TO_NEXT、SKIP_TO_PREVIOUS、STOP，否则对应按键不会送达。
     */
    private void updateMediaSessionState() {
        if (mediaSession == null || player == null) return;
        try {
            long actions = android.media.session.PlaybackState.ACTION_PLAY
                    | android.media.session.PlaybackState.ACTION_PAUSE
                    | android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                    | android.media.session.PlaybackState.ACTION_STOP
                    | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                    | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS;

            boolean playing = player.isPlaying();
            int state = playing
                    ? android.media.session.PlaybackState.STATE_PLAYING
                    : android.media.session.PlaybackState.STATE_PAUSED;

            android.media.session.PlaybackState.Builder b =
                    new android.media.session.PlaybackState.Builder()
                            .setActions(actions)
                            .setState(state, player.getCurrentPosition(), playing ? 1f : 0f);

            // 打开耳机键支持：系统据此决定是否把按键转给本应用
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBoolean("android.media.session.extra.HEADSETHOOK_SUPPORTED", true);
            b.setExtras(extras);

            mediaSession.setPlaybackState(b.build());

            // 同步当前曲目信息（锁屏/车机显示用）
            WebDAVFile cur = player.getCurrentTrack();
            if (cur != null) {
                android.media.MediaMetadata.Builder mb =
                        new android.media.MediaMetadata.Builder()
                                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE,
                                        cur.getDisplayName())
                                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION,
                                        player.getDuration());
                mediaSession.setMetadata(mb.build());
            }
        } catch (Exception e) {
            rlog.w(TAG, "更新 MediaSession 状态失败: " + e.getMessage());
        }
    }


    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "音乐播放",
                        NotificationManager.IMPORTANCE_LOW);   // LOW：不响铃不震动
                ch.setDescription("显示当前播放的歌曲与播放控制");
                ch.setShowBadge(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        }
    }

    /** 构建播放通知（含曲名、播放/暂停、上一首、下一首） */
    private Notification buildNotification() {
        WebDAVFile current = player == null ? null : player.getCurrentTrack();
        boolean playing = player != null && player.isPlaying();

        String title = current == null
                ? "DavMusic" : current.getDisplayName();
        String text = current == null
                ? "准备就绪" : (playing ? "正在播放" : "已暂停");

        // 点击通知回到主界面
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, piFlags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_music)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(playing)          // 播放中不可滑动清除
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        // 播放/暂停
        b.addAction(new NotificationCompat.Action(
                playing ? R.drawable.ic_notification_pause
                        : R.drawable.ic_notification_play,
                playing ? "暂停" : "播放",
                servicePendingIntent(ACTION_TOGGLE, 1)));

        // 上一首 / 下一首
        boolean multi = player != null && player.getPlaylist() != null
                && player.getPlaylist().size() > 1;
        if (multi) {
            b.addAction(new NotificationCompat.Action(
                    R.drawable.ic_notification_prev, "上一首",
                    servicePendingIntent(ACTION_PREV, 2)));
            b.addAction(new NotificationCompat.Action(
                    R.drawable.ic_notification_next, "下一首",
                    servicePendingIntent(ACTION_NEXT, 3)));
        }

        // MediaStyle：让系统按媒体通知排版，并在锁屏/车机显示控制
        androidx.media.app.NotificationCompat.MediaStyle style =
                new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, multi ? 1 : 0)   // 折叠态显示前几个
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(servicePendingIntent(ACTION_STOP, 4));
        b.setStyle(style);

        return b.build();
    }

    private PendingIntent servicePendingIntent(String action, int reqCode) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, reqCode, i, flags);
    }

    /** 刷新通知内容（曲目/播放状态变化时调用） */
    private void refreshNotification() {
        if (!isForeground) return;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception e) {
            rlog.w(TAG, "刷新通知失败: " + e.getMessage());
        }
    }

    private void startForegroundCompat(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 需要显式声明服务类型
                ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, n,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                                : 0);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            isForeground = true;
            rlog.i(TAG, "已进入前台服务");
        } catch (Exception e) {
            rlog.e(TAG, "startForeground 失败: " + e.getMessage());
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
        }
        isForeground = false;
    }

    private void stopSelfSafely() {
        try {
            stopForegroundCompat();
            stopSelf();
        } catch (Exception e) {
            rlog.w(TAG, "stopSelf 失败: " + e.getMessage());
        }
    }

    // ---------- MusicPlayer 回调：驱动通知刷新 ----------

    @Override
    public void onTrackChanged(WebDAVFile track) {
        rlog.i(TAG, "onTrackChanged → 刷新通知: "
                + (track == null ? "-" : track.getDisplayName()));
        refreshNotification();
        // MediaSession 的曲目信息也要同步（锁屏/车机显示）
        updateMediaSessionState();
    }

    @Override
    public void onPlayStateChanged(boolean isPlaying) {
        rlog.i(TAG, "onPlayStateChanged → 刷新通知: playing=" + isPlaying);
        refreshNotification();
        // 必须同步 MediaSession 状态：系统依据 PlaybackState.actions
        // 决定是否把耳机按键转发给本应用
        updateMediaSessionState();
    }

    @Override
    public void onProgress(int position, int duration) {
        // 进度每秒回调，不做处理 —— 通知里不显示进度，
        // 频繁 refreshNotification 会带来不必要的开销
    }

    @Override
    public void onError(String error) {
        rlog.e(TAG, "播放错误: " + error);
        refreshNotification();
    }

    // ---------- 静态辅助：供 Activity 启动服务 ----------

    /** 启动并置于前台（播放开始时调用） */
    public static void start(Context context) {
        try {
            Intent i = new Intent(context, MusicService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "启动 MusicService 失败: " + e.getMessage());
        }
    }

    /** 停止服务 */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, MusicService.class));
        } catch (Exception ignored) {
        }
    }
}
