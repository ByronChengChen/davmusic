package com.byron.davmusic;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 全屏歌词页：动态滚动显示当前播放歌曲的 LRC 歌词。
 *
 * 数据来源与限制（v1 设计决定，见 TODO.md B1）：
 *  - 只认「与歌曲同目录、同名、扩展名 .lrc」的歌词文件；命名不一致的当作没有。
 *  - 歌词文件在列表里是隐藏的（FileListAdapter 只显示音频），所以不查目录，
 *    直接按同名规则拼出路径再 GET：一个请求，404 即「暂无歌词」。
 *  - 下载过的歌词落到 filesDir/lyrics 缓存，同一首再次打开不再发请求。
 *
 * 滚动：MusicPlayer 的 onProgress 是 1 秒一次，拿它做滚动会一秒跳一行，
 * 因此这里用 80ms 轮询 getCurrentPosition()（纯内存读），只在行号变化时滚动。
 */
public class LyricsActivity extends AppCompatActivity {

    private static final String TAG = "LyricsActivity";
    private static final long TICK_MS = 80;
    private static final int ROW_HEIGHT_DP = 72;
    private static final String CACHE_DIR = "lyrics";

    private MusicPlayer player;
    private RecyclerView lyricsList;
    private LinearLayoutManager layoutManager;
    private LyricLineAdapter adapter;
    private TextView trackTitle;
    private TextView trackSubtitle;
    private TextView statusView;

    private List<LrcParser.Line> lines = new ArrayList<>();
    private String loadedKey;
    private boolean ticking;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            if (player != null) updateHighlight(player.getCurrentPosition());
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final MusicPlayer.OnPlaybackListener listener = new MusicPlayer.OnPlaybackListener() {
        @Override
        public void onTrackChanged(WebDAVFile track) {
            loadLyrics();
        }

        @Override
        public void onPlayStateChanged(boolean isPlaying) {
            // 暂停时不停止轮询：拖动进度/暂停后恢复都要立刻对齐
        }

        @Override
        public void onProgress(int position, int duration) {
            // 1 秒一次，精度不够；滚动由 ticker 负责
        }

        @Override
        public void onPlayModeChanged(int mode) {
        }

        @Override
        public void onError(String error) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics);

        player = MusicPlayer.getInstance(this);

        trackTitle = findViewById(R.id.trackTitle);
        trackSubtitle = findViewById(R.id.trackSubtitle);
        statusView = findViewById(R.id.statusView);
        lyricsList = findViewById(R.id.lyricsList);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        layoutManager = new LinearLayoutManager(this);
        lyricsList.setLayoutManager(layoutManager);
        adapter = new LyricLineAdapter();
        lyricsList.setAdapter(adapter);

        player.addPlaybackListener(listener);
        loadLyrics();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ticking = true;
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ticking = false;
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.removePlaybackListener(listener);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------- 歌词加载 ----------------

    private void loadLyrics() {
        WebDAVFile track = player == null ? null : player.getCurrentTrack();
        if (track == null) {
            loadedKey = null;
            lines = new ArrayList<>();
            adapter.setLines(lines);
            trackTitle.setText(R.string.title_lyrics);
            trackSubtitle.setText("");
            showStatus(getString(R.string.lyrics_no_track));
            return;
        }

        trackTitle.setText(track.getDisplayName());
        trackSubtitle.setText("");   // 歌手来自歌词的 [ar:] 标签，加载完再填

        final String key = track.getCacheKey();
        if (key.equals(loadedKey) && !lines.isEmpty()) return;   // 同一首，已展示中
        loadedKey = key;

        lines = new ArrayList<>();
        adapter.setLines(lines);

        byte[] cached = readCache(key);
        if (cached != null) {
            applyLyrics(LrcParser.decode(cached), key);
            return;
        }

        final String lrcPath = siblingLrcPath(track.getRelativePath());
        if (lrcPath == null) {
            showStatus(getString(R.string.lyrics_not_found));
            return;
        }

        WebDAVClient client = clientFor(track);
        if (client == null) {
            showStatus(getString(R.string.lyrics_load_failed));
            return;
        }

        showStatus(getString(R.string.lyrics_loading));
        client.fetchBytes(lrcPath, new WebDAVClient.WebDAVCallback<byte[]>() {
            @Override
            public void onSuccess(byte[] result) {
                saveCache(key, result);
                runOnUiThread(() -> {
                    // 期间可能已经切歌：请求回来只认当前这首
                    if (!key.equals(loadedKey)) return;
                    applyLyrics(LrcParser.decode(result), key);
                });
            }

            @Override
            public void onError(Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean notFound = msg.contains("404") || msg.contains("403");
                runOnUiThread(() -> {
                    if (!key.equals(loadedKey)) return;
                    showStatus(notFound
                            ? getString(R.string.lyrics_not_found)
                            : getString(R.string.lyrics_load_failed));
                });
            }
        });
    }

    private void applyLyrics(String text, String key) {
        if (!key.equals(loadedKey)) return;
        trackSubtitle.setText(LrcParser.artist(text));
        lines = LrcParser.parse(text);
        adapter.setLines(lines);
        if (lines.isEmpty()) {
            showStatus(getString(R.string.lyrics_not_found));
            return;
        }
        hideStatus();
        updateHighlight(player.getCurrentPosition());
        if (adapter.getCurrentIndex() < 0) {
            // 歌曲还没走到第一句（前奏）：把第一句放在中线，等时间追上
            scrollToLine(0, false);
        }
    }

    // ---------------- 高亮与滚动 ----------------

    private void updateHighlight(int positionMs) {
        if (lines.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs) index = i;
            else break;
        }
        if (adapter.setCurrentIndex(index) && index >= 0) {
            scrollToLine(index, true);
        }
    }

    private void scrollToLine(final int index, boolean smooth) {
        if (index < 0) return;
        int height = lyricsList.getHeight();
        if (height == 0) {
            // 首帧还没布局完：等一帧再定位
            lyricsList.post(() -> scrollToLine(index, false));
            return;
        }

        int padTop = lyricsList.getPaddingTop();
        int padBottom = lyricsList.getPaddingBottom();
        int usable = height - padTop - padBottom;
        int rowPx = Math.round(ROW_HEIGHT_DP * getResources().getDisplayMetrics().density);
        // 中线落点：内容区内居中，永远在顶栏下方（clipToPadding 也不会让内容画进 padding）
        final int targetTop = padTop + Math.max(0, (usable - rowPx) / 2);

        View row = layoutManager.findViewByPosition(index);
        if (row != null) {
            int dy = row.getTop() - targetTop;
            if (Math.abs(dy) <= 2) return;
            if (smooth) lyricsList.smoothScrollBy(0, dy);
            else lyricsList.scrollBy(0, dy);
            return;
        }

        // 目标行不在屏幕上：先把它贴到内容区顶部（避免停在顶栏下面），
        // 下一帧按实际 top 精修到中线
        layoutManager.scrollToPositionWithOffset(index, padTop);
        lyricsList.post(() -> {
            View v = layoutManager.findViewByPosition(index);
            if (v == null) return;
            int dy = v.getTop() - targetTop;
            if (Math.abs(dy) > 2) lyricsList.scrollBy(0, dy);
        });
    }

    // ---------------- 网络与缓存 ----------------

    /** 歌词路径规则在 LrcParser 里（纯逻辑，可纯 JVM 测） */
    private static String siblingLrcPath(String relativePath) {
        return LrcParser.siblingLrcPath(relativePath);
    }

    /** 歌词按条目所属服务器取，和播放取流用的是同一套凭据规则 */
    private WebDAVClient clientFor(WebDAVFile file) {
        String serverId = file.getServerId();
        if (serverId != null && !serverId.isEmpty()) {
            for (ServerProfile p : ServerStore.list(this)) {
                if (serverId.equals(p.getId())) {
                    return p.isComplete() ? WebDAVClient.forServer(p) : null;
                }
            }
            return null;
        }
        return WebDAVClient.getInstance();
    }

    private File cacheFile(String key) {
        File dir = new File(getFilesDir(), CACHE_DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, hash(key) + ".lrc");
    }

    private byte[] readCache(String key) {
        File f = cacheFile(key);
        if (f == null || !f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length(), 1 << 20));
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void saveCache(String key, byte[] data) {
        File f = cacheFile(key);
        if (f == null || data == null) return;
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    /** 缓存文件名用哈希：路径含中文和斜杠，直接做文件名不可靠 */
    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ---------------- 状态提示 ----------------

    private void showStatus(String text) {
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusView.setVisibility(View.GONE);
    }

    /** 供其它入口（如「更多」菜单）直接打开歌词页 */
    public static void start(Context context) {
        context.startActivity(new android.content.Intent(context, LyricsActivity.class));
    }
}
