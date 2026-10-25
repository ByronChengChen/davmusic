package com.byron.davmusic;

import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 播放控制条（`view_player_bar.xml`）的绑定逻辑。
 *
 * 主界面沿用自己原有的绑定代码；歌词页用这个类，避免把「进度条拖动 / 切歌 / 模式切换」
 * 这套容易出错的逻辑复制一遍。
 *
 * 关键点：
 *  - 拖动进度条期间（tracking）不接受 onProgress 的刷新，否则手指还没松就会被回写；
 *  - 松手才 seekTo，且只在用户确实拖动过时执行一次；
 *  - 所有状态都从 MusicPlayer 单例读取，不做本地缓存 —— 两个入口（主界面/歌词页）因此
 *    天然同步。
 */
public class PlayerBarController implements MusicPlayer.OnPlaybackListener {

    private final AppCompatActivity activity;
    private final MusicPlayer player;

    private final ImageButton playPauseButton;
    private final ImageButton previousButton;
    private final ImageButton nextButton;
    private final ImageButton playModeButton;
    private final SeekBar progressBar;
    private final TextView currentTimeText;
    private final TextView totalTimeText;
    private final TextView titleText;

    private boolean tracking;

    public PlayerBarController(AppCompatActivity activity, View root, MusicPlayer player) {
        this.activity = activity;
        this.player = player;

        playPauseButton = root.findViewById(R.id.playPauseButton);
        previousButton = root.findViewById(R.id.previousButton);
        nextButton = root.findViewById(R.id.nextButton);
        playModeButton = root.findViewById(R.id.playModeButton);
        progressBar = root.findViewById(R.id.playerProgressBar);
        currentTimeText = root.findViewById(R.id.playerCurrentTimeText);
        totalTimeText = root.findViewById(R.id.playerTotalTimeText);
        titleText = root.findViewById(R.id.playerTitleTextView);

        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) currentTimeText.setText(FileUtils.formatDuration(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                tracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                tracking = false;
                player.seekTo(seekBar.getProgress());
            }
        });

        playPauseButton.setOnClickListener(v -> player.togglePlayPause());
        previousButton.setOnClickListener(v -> player.playPrevious());
        nextButton.setOnClickListener(v -> player.playNext());
        playModeButton.setOnClickListener(v -> {
            int mode = player.cyclePlayMode();
            Toast.makeText(activity, "播放模式：" + MusicPlayer.modeName(mode), Toast.LENGTH_SHORT).show();
        });

        player.addPlaybackListener(this);
        refresh();
    }

    /** 页面销毁时必须调用，否则监听器会持有已销毁的 Activity */
    public void release() {
        player.removePlaybackListener(this);
    }

    // ---------------- OnPlaybackListener ----------------

    @Override
    public void onTrackChanged(WebDAVFile track) {
        refresh();
    }

    @Override
    public void onPlayStateChanged(boolean isPlaying) {
        updatePlayIcon(isPlaying);
    }

    @Override
    public void onProgress(int position, int duration) {
        if (tracking) return;
        progressBar.setMax(Math.max(duration, 0));
        progressBar.setProgress(Math.max(position, 0));
        currentTimeText.setText(FileUtils.formatDuration(position));
        totalTimeText.setText(FileUtils.formatDuration(duration));
    }

    @Override
    public void onPlayModeChanged(int mode) {
        updateModeIcon(mode);
    }

    @Override
    public void onError(String error) {
        // 错误提示由播放器所在页面负责，这里只维持控件状态
    }

    // ---------------- 刷新 ----------------

    private void refresh() {
        WebDAVFile track = player.getCurrentTrack();
        boolean hasTrack = track != null;

        titleText.setText(hasTrack ? track.getDisplayName() : activity.getString(R.string.label_no_track));
        playPauseButton.setEnabled(hasTrack);
        previousButton.setEnabled(hasTrack && player.getPlaylist().size() > 1);
        nextButton.setEnabled(hasTrack && player.getPlaylist().size() > 1);
        playModeButton.setEnabled(hasTrack);
        progressBar.setEnabled(hasTrack);

        if (!hasTrack) {
            progressBar.setProgress(0);
            progressBar.setMax(0);
            currentTimeText.setText(activity.getString(R.string.time_zero));
            totalTimeText.setText(activity.getString(R.string.time_zero));
            return;
        }

        int duration = player.getDuration();
        int position = player.getCurrentPosition();
        progressBar.setMax(Math.max(duration, 0));
        progressBar.setProgress(Math.max(position, 0));
        currentTimeText.setText(FileUtils.formatDuration(position));
        totalTimeText.setText(FileUtils.formatDuration(duration));

        updatePlayIcon(player.isPlaying());
        updateModeIcon(player.getPlayMode());
    }

    private void updatePlayIcon(boolean isPlaying) {
        playPauseButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        playPauseButton.setContentDescription(
                activity.getString(isPlaying ? R.string.btn_pause : R.string.btn_play));
    }

    private void updateModeIcon(int mode) {
        int icon;
        switch (mode) {
            case MusicPlayer.MODE_SEQUENTIAL:
                icon = R.drawable.ic_sequential;
                break;
            case MusicPlayer.MODE_SINGLE_LOOP:
                icon = R.drawable.ic_repeat_one;
                break;
            case MusicPlayer.MODE_SHUFFLE:
                icon = R.drawable.ic_shuffle;
                break;
            case MusicPlayer.MODE_LIST_LOOP:
            default:
                icon = R.drawable.ic_repeat;
                break;
        }
        playModeButton.setImageResource(icon);
        playModeButton.setContentDescription(
                activity.getString(R.string.btn_play_mode) + "：" + MusicPlayer.modeName(mode));
    }
}
