package com.byron.davmusic;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
    private LinearLayout statusContainer;
    private Button searchButton;
    private boolean searching;
    private RepeatRangeStore repeatStore;
    private final List<RepeatRange> repeatRanges = new ArrayList<>();
    private String repeatTrackKey;
    private int repeatStartMs = -1;
    private int repeatEndMs = -1;
    private RepeatRange selectedRepeatRange;
    private LinearLayout repeatIntervalsView;
    private TextView repeatTimeView;
    private Button repeatToggleButton;
    private android.app.AlertDialog repeatDialog;

    private PlayerBarController playerBar;
    private GestureDetector gestureDetector;
    /** 双击跳转后短暂屏蔽 ticker，避免 seek 尚未生效时高亮被拉回旧行 */
    private long suppressHighlightUntil;

    private List<LrcParser.Line> lines = new ArrayList<>();
    private String loadedKey;
    private boolean ticking;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            if (player != null && SystemClock.elapsedRealtime() >= suppressHighlightUntil) {
                updateHighlight(player.getCurrentPosition());
                if (repeatDialog != null && repeatDialog.isShowing()) updateRepeatDialogStatus();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final MusicPlayer.OnPlaybackListener listener = new MusicPlayer.OnPlaybackListener() {
        @Override
        public void onTrackChanged(WebDAVFile track) {
            repeatStartMs = repeatEndMs = -1;
            repeatTrackKey = RepeatRangeStore.key(track == null ? null : track.getServerId(), track == null ? null : track.getRelativePath());
            repeatRanges.clear();
            selectedRepeatRange = null;
            repeatRanges.addAll(repeatStore.load(repeatTrackKey));
            if (!repeatRanges.isEmpty()) selectedRepeatRange = repeatRanges.get(0);
            RepeatRange active = player.getRepeatController().getActive();
            if (active != null && player.getRepeatController().isActiveFor(repeatTrackKey)) selectedRepeatRange = active;
            if (repeatDialog != null && repeatDialog.isShowing()) {
                bindRepeatDialogViews();
                renderRepeatRanges();
                updateRepeatDialogStatus();
            }
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
        repeatStore = new RepeatRangeStore(this);
        findViewById(R.id.moreButton).setOnClickListener(this::showMoreMenu);

        trackTitle = findViewById(R.id.trackTitle);
        trackSubtitle = findViewById(R.id.trackSubtitle);
        statusView = findViewById(R.id.statusView);
        statusContainer = findViewById(R.id.statusContainer);
        searchButton = findViewById(R.id.searchLyricsButton);
        searchButton.setOnClickListener(v -> showSearchDialog());
        lyricsList = findViewById(R.id.lyricsList);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        layoutManager = new LinearLayoutManager(this);
        lyricsList.setLayoutManager(layoutManager);
        adapter = new LyricLineAdapter();
        lyricsList.setAdapter(adapter);

        player.addPlaybackListener(listener);
        listener.onTrackChanged(player.getCurrentTrack());
        playerBar = new PlayerBarController(this, findViewById(R.id.playerBar), player);
        setupDoubleTapToSeek();
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
        if (playerBar != null) playerBar.release();
        if (player != null) player.removePlaybackListener(listener);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void showMoreMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        menu.inflate(R.menu.lyrics_more);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_repeat) {
                showRepeatDialog();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showRepeatDialog() {
        if (repeatDialog != null && repeatDialog.isShowing()) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_ab_repeat, null, false);
        repeatDialog = new android.app.AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.Theme_DavMusic))
                .setTitle("复读设置")
                .setView(content)
                .create();
        repeatDialog.setOnShowListener(dialog -> {
            bindRepeatDialogViews();
            renderRepeatRanges();
            updateRepeatDialogStatus();
        });
        repeatDialog.setOnDismissListener(dialog -> {
            repeatTimeView = null;
            repeatToggleButton = null;
            repeatIntervalsView = null;
        });
        repeatDialog.show();
    }

    private void bindRepeatDialogViews() {
        if (repeatDialog == null || !repeatDialog.isShowing()) return;
        repeatIntervalsView = repeatDialog.findViewById(R.id.repeatIntervals);
        repeatTimeView = repeatDialog.findViewById(R.id.repeatTime);
        repeatToggleButton = repeatDialog.findViewById(R.id.repeatToggle);
        if (repeatTimeView == null || repeatToggleButton == null || repeatIntervalsView == null) return;
        View setStart = repeatDialog.findViewById(R.id.repeatSetStart);
        View setEnd = repeatDialog.findViewById(R.id.repeatSetEnd);
        View save = repeatDialog.findViewById(R.id.repeatSave);
        if (setStart == null || setEnd == null || save == null) return;
        setStart.setOnClickListener(v -> {
            repeatStartMs = player.getCurrentPosition();
            updateRepeatDialogStatus("A 点 " + formatTime(repeatStartMs));
        });
        repeatDialog.findViewById(R.id.repeatSetEnd).setOnClickListener(v -> {
            repeatEndMs = player.getCurrentPosition();
            updateRepeatDialogStatus("B 点 " + formatTime(repeatEndMs));
        });
        repeatDialog.findViewById(R.id.repeatSave).setOnClickListener(v -> saveRepeatRange());
        repeatToggleButton.setOnClickListener(v -> toggleRepeat());
    }

    private void updateRepeatDialogStatus() {
        if (repeatDialog == null || !repeatDialog.isShowing()
                || repeatTimeView == null || repeatToggleButton == null) return;
        RepeatRange active = player.getRepeatController().getActive();
        boolean activeHere = active != null && player.getRepeatController().isActiveFor(repeatTrackKey);
        if (activeHere) selectedRepeatRange = active;
        repeatToggleButton.setText(activeHere ? "停止" : "开始复读");
        if (activeHere) {
            updateRepeatDialogStatus("复读中  " + formatTime(active.getStartMs()) + "–" + formatTime(active.getEndMs()));
        } else {
            String draft = repeatStartMs >= 0 || repeatEndMs >= 0
                    ? "A " + (repeatStartMs < 0 ? "--:--" : formatTime(repeatStartMs))
                    + "  B " + (repeatEndMs < 0 ? "--:--" : formatTime(repeatEndMs))
                    : "复读区间";
            updateRepeatDialogStatus(draft + "  " + formatTime(player.getCurrentPosition())
                    + " / " + formatTime(player.getDuration()));
        }
    }

    private void updateRepeatDialogStatus(String text) {
        if (repeatTimeView != null) repeatTimeView.setText(text);
    }

    private void saveRepeatRange() {
        RepeatRange range = RepeatRange.create(repeatStartMs, repeatEndMs, player.getDuration());
        if (repeatTrackKey == null || range == null) { toast("请设置有效的 A/B 点"); return; }
        if (repeatRanges.contains(range)) { toast("该复读区间已保存"); return; }
        repeatRanges.add(range);
        repeatStore.save(repeatTrackKey, repeatRanges);
        repeatStartMs = repeatEndMs = -1;
        selectedRepeatRange = range;
        updateRepeatDialogStatus("已保存 " + formatTime(range.getStartMs()) + "–" + formatTime(range.getEndMs()));
        renderRepeatRanges();
    }

    private void toggleRepeat() {
        if (player.getRepeatController().isActiveFor(repeatTrackKey)) {
            player.clearRepeatRange();
            renderRepeatRanges();
            updateRepeatDialogStatus();
            return;
        }
        if (repeatRanges.isEmpty() || repeatTrackKey == null || player.getDuration() <= 0) { toast("请先保存有效区间"); return; }
        if (selectedRepeatRange == null) selectedRepeatRange = repeatRanges.get(0);
        if (player.isPreparing()) { toast("播放器正在准备，请稍后再试"); return; }
        int duration = player.getDuration();
        RepeatRange valid = RepeatRange.create(selectedRepeatRange.getStartMs(), selectedRepeatRange.getEndMs(), duration);
        if (valid == null) { toast("区间超出音频时长，请重新设定"); return; }
        selectedRepeatRange = valid;
        player.setRepeatRange(repeatTrackKey, valid);
        renderRepeatRanges();
    }

    private void renderRepeatRanges() {
        if (repeatDialog == null || !repeatDialog.isShowing() || repeatIntervalsView == null) return;
        repeatIntervalsView.removeAllViews();
        for (int i = 0; i < repeatRanges.size(); i++) {
            final int index = i;
            RepeatRange range = repeatRanges.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this);
            label.setText(formatTime(range.getStartMs()) + " – " + formatTime(range.getEndMs()));
            label.setTextColor(android.graphics.Color.WHITE);
            label.setPadding(8, 4, 8, 4);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            Button select = new Button(this);
            select.setText(selectedRepeatRange != null && selectedRepeatRange.equals(range) ? "已选择" : "选择");
            select.setOnClickListener(v -> { selectedRepeatRange = repeatRanges.get(index); renderRepeatRanges(); });
            row.addView(select);
            Button delete = new Button(this);
            delete.setText("×");
            delete.setContentDescription("删除复读区间");
            delete.setOnClickListener(v -> {
                boolean deletingActive = player.getRepeatController().isActiveRange(repeatTrackKey, repeatRanges.get(index));
                boolean deletingSelected = selectedRepeatRange != null && selectedRepeatRange.equals(repeatRanges.get(index));
                repeatRanges.remove(index);
                if (deletingActive) {
                    player.clearRepeatRange();
                    repeatToggleButton.setText("开始复读");
                    updateRepeatDialogStatus("复读区间已停止");
                }
                if (deletingSelected) selectedRepeatRange = null;
                repeatStore.save(repeatTrackKey, repeatRanges);
                renderRepeatRanges();
            });
            row.addView(delete);
            repeatIntervalsView.addView(row);
        }
    }

    private static String formatTime(int ms) {
        int seconds = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
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
            showStatus(getString(R.string.lyrics_no_track), false);
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
            showStatus(getString(R.string.lyrics_not_found), true);
            return;
        }

        WebDAVClient client = clientFor(track);
        if (client == null) {
            showStatus(getString(R.string.lyrics_load_failed), true);
            return;
        }

        showStatus(getString(R.string.lyrics_loading), false);
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
                                    : getString(R.string.lyrics_load_failed),
                            true);
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
            showStatus(getString(R.string.lyrics_not_found), true);
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
        int index = LrcParser.indexAt(lines, positionMs);
        if (adapter.setCurrentIndex(index) && index >= 0) {
            scrollToLine(index, true);
        }
    }

    // ---------------- 双击跳转 ----------------

    private void setupDoubleTapToSeek() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                seekToLyricAt(e.getX(), e.getY());
                return true;
            }
        });
        lyricsList.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                gestureDetector.onTouchEvent(e);
                return false;   // 不拦截：滚动与单击行为不变
            }
        });
    }

    /**
     * 双击某句 → 把播放位置移到这句的时间点。
     *
     * 按用户要求**只定位、不自动播放**：暂停时双击仍是暂停，但进度会移动。
     * seek 是异步的，所以这里立刻高亮目标行并短暂屏蔽 ticker，避免视觉上先跳回旧行。
     */
    private void seekToLyricAt(float x, float y) {
        if (player == null || lines.isEmpty()) return;

        View child = lyricsList.findChildViewUnder(x, y);
        if (child == null) return;
        int position = lyricsList.getChildAdapterPosition(child);
        if (position < 0 || position >= lines.size()) return;

        long target = lines.get(position).timeMs;
        if (player.isPreparing()) {
            toast("正在准备播放，稍后再试");
            return;
        }
        int duration = player.getDuration();
        if (duration <= 0) {
            toast("播放器还没准备好");
            return;
        }
        if (target >= duration - 300) {
            toast("这句超出音频时长");
            return;
        }

        player.seekTo((int) target);

        adapter.setCurrentIndex(position);
        scrollToLine(position, false);
        suppressHighlightUntil = SystemClock.elapsedRealtime() + 1000;
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

    // ---------------- 搜索歌词（LRCLIB） ----------------

    private void showSearchDialog() {
        WebDAVFile track = player == null ? null : player.getCurrentTrack();
        if (track == null) return;

        View content = getLayoutInflater().inflate(R.layout.dialog_lyrics_search, null);
        EditText input = content.findViewById(R.id.queryInput);
        input.setText(LyricsQuery.fromFileName(track.getDisplayName()));
        input.setSelection(input.getText().length());

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.lyrics_search_title)
                .setView(content)
                .setPositiveButton(R.string.lyrics_search_action,
                        (d, w) -> startSearch(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startSearch(String query) {
        if (searching) return;
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            toast(getString(R.string.lyrics_search_hint));
            return;
        }
        searching = true;
        toast(getString(R.string.lyrics_searching));
        int durationSec = player == null ? 0 : Math.max(0, player.getDuration() / 1000);
        LyricsSearchClient.search(q, durationSec, new LyricsSearchClient.Callback() {
            @Override
            public void onSuccess(List<LyricsSearchClient.Result> results) {
                runOnUiThread(() -> {
                    searching = false;
                    showSearchResults(results);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    searching = false;
                    toast(message);
                });
            }
        });
    }

    private void showSearchResults(List<LyricsSearchClient.Result> results) {
        if (results == null || results.isEmpty()) {
            toast(getString(R.string.lyrics_search_empty));
            return;
        }
        String[] items = new String[results.size()];
        for (int i = 0; i < results.size(); i++) items[i] = results.get(i).displayLine();

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.lyrics_search_pick)
                .setItems(items, (d, which) -> applySearchResult(results.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applySearchResult(LyricsSearchClient.Result result) {
        if (result == null || loadedKey == null) return;
        String text = result.syncedLyrics;
        if (text == null || text.trim().isEmpty()) return;

        // 先落本地缓存：上传失败、离线时都仍然可用
        saveCache(loadedKey, text.getBytes(StandardCharsets.UTF_8));
        applyLyrics(text, loadedKey);
        if (!result.artistName.isEmpty()) trackSubtitle.setText(result.artistName);
        toast(getString(R.string.lyrics_saved));
        uploadLyrics(text);
    }

    /** 写到歌曲同目录的同名 .lrc；服务器已有同名文件时不覆盖（If-None-Match） */
    private void uploadLyrics(String text) {
        WebDAVFile track = player == null ? null : player.getCurrentTrack();
        if (track == null || text == null || text.isEmpty()) return;
        final String path = LrcParser.siblingLrcPath(track.getRelativePath());
        WebDAVClient client = clientFor(track);
        if (path == null || client == null) return;

        client.putBytes(path, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8",
                new WebDAVClient.WebDAVCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean ok) {
                        runOnUiThread(() -> toast(getString(R.string.lyrics_uploaded)));
                    }

                    @Override
                    public void onError(Exception e) {
                        String msg = e.getMessage() == null ? "" : e.getMessage();
                        runOnUiThread(() -> toast(getString(R.string.lyrics_upload_failed, msg)));
                    }
                });
    }

    private void toast(String msg) {
        if (msg != null && !msg.isEmpty()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
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

    private void showStatus(String text, boolean allowSearch) {
        statusView.setText(text);
        searchButton.setVisibility(allowSearch ? View.VISIBLE : View.GONE);
        statusContainer.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusContainer.setVisibility(View.GONE);
    }

    /** 供其它入口（如「更多」菜单）直接打开歌词页 */
    public static void start(Context context) {
        context.startActivity(new android.content.Intent(context, LyricsActivity.class));
    }
}
