package com.byron.davmusic;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Searches only persisted directory metadata; never owns navigation or the active queue. */
public class SearchActivity extends AppCompatActivity implements MusicPlayer.OnPlaybackListener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong sourceRevision = new AtomicLong();
    private LocalCacheManager cache;
    private MusicPlayer musicPlayer;
    private PlayerBarController playerBar;
    private SnapshotSearchController controller;
    private ResultAdapter adapter;
    private EditText query;
    private TextView status;
    private View loading, historyClear;
    private SearchSongHistoryStore history;
    private boolean historyMode;
    private volatile boolean destroyed;
    // May be invoked by a background snapshot publisher. Fence immediately, then schedule UI work.
    private final Runnable sourceChanged = () -> {
        sourceRevision.incrementAndGet();
        ui.post(() -> { if (!destroyed && controller != null) { controller.invalidate(); search(); } });
    };
    private final Runnable downloadChanged = () -> ui.post(() -> {
        if (!destroyed && adapter != null) adapter.refreshDownloadState();
    });
    private final SharedPreferences.OnSharedPreferenceChangeListener sortChanged = (prefs, key) -> {
        if ("file_sort_mode".equals(key)) ui.post(() -> {
            if (!destroyed && controller != null) search();
        });
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener configChanged = (prefs, key) -> {
        if ("servers_json".equals(key)) sourceChanged.run();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_search);
        cache = LocalCacheManager.getInstance(this);
        history = new SearchSongHistoryStore(new java.io.File(getFilesDir(), "search-song-history.bin"));
        historyClear = findViewById(R.id.searchHistoryClear);
        historyClear.setOnClickListener(v -> {
            if (!history.clear()) toast(getString(R.string.search_history_save_error));
            if (historyMode) search();
        });
        musicPlayer = MusicPlayer.getInstance(this);
        query = findViewById(R.id.searchQuery);
        status = findViewById(R.id.searchStatus);
        loading = findViewById(R.id.searchLoading);
        adapter = new ResultAdapter();
        RecyclerView list = findViewById(R.id.searchResults);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        playerBar = new PlayerBarController(this, findViewById(R.id.searchPlayerBar), musicPlayer);
        findViewById(R.id.searchPlayerBar).setOnClickListener(v -> startActivity(new Intent(this, LyricsActivity.class)));
        musicPlayer.addPlaybackListener(this);
        controller = new SnapshotSearchController(new SnapshotSearchController.Source() {
            public long revision() { return sourceRevision.get(); }
            public List<WebDAVFile> load() {
                // Do not nest ServerStore and LocalCacheManager locks.
                Map<String,String> owners = new HashMap<>();
                for (ServerProfile server : ServerStore.list(getApplicationContext()))
                    owners.put(server.getId(), server.getDisplayName());
                return SnapshotSearchEngine.collect(cache.loadSearchSnapshots(), owners);
            }
        }, worker, command -> ui.post(command));
        cache.addSnapshotListener(sourceChanged);
        cache.addDownloadListener(downloadChanged);
        getSharedPreferences("player_prefs", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(sortChanged);
        ServerStore.prefs(this).registerOnSharedPreferenceChangeListener(configChanged);
        findViewById(R.id.searchBack).setOnClickListener(v -> finish());
        findViewById(R.id.searchClear).setOnClickListener(v -> query.setText(""));
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { search(); }
            public void afterTextChanged(Editable s) {}
        });
        if (state != null) query.setText(state.getString("query", ""));
        search();
    }

    private void search() {
        String text = query.getText().toString();
        // Disable stale rows immediately; clicking must capture exactly the displayed query result.
        adapter.setFiles(Collections.emptyList());
        historyMode = text.trim().isEmpty();
        historyClear.setVisibility(historyMode ? View.VISIBLE : View.GONE);
        if (historyMode) {
            controller.invalidate(); loading.setVisibility(View.GONE);
            adapter.setFiles(history.list(ServerStore.list(this)));
            historyClear.setEnabled(!adapter.files.isEmpty());
            status.setText(adapter.files.isEmpty() ? R.string.search_history_empty : R.string.search_history_title);
            return;
        }
        loading.setVisibility(View.VISIBLE); status.setText(R.string.search_loading);
        controller.search(text, TrackSorter.getMode(this), result -> {
            if (destroyed || historyMode || query.getText().toString().trim().isEmpty()) return;
            adapter.setFiles(result); loading.setVisibility(View.GONE);
            status.setText(result.isEmpty() ? getString(R.string.search_no_results)
                    : getString(R.string.search_count, result.size()));
        });
    }

    private void play(WebDAVFile file) {
        List<ServerProfile> servers = ServerStore.list(this);
        ServerProfile owner = null;
        for (ServerProfile server : servers)
            if (server.getId().equals(file.getServerId()) && server.isComplete()) { owner = server; break; }
        if (historyMode) file = history.resolve(file, servers);
        if (owner == null || file == null) { toast(getString(R.string.search_removed_server)); sourceChanged.run(); return; }
        if (!NetworkUtils.isNetworkConnected(this) && !cache.isDownloaded(file.getCacheKey())) {
            toast(getString(R.string.search_offline)); return;
        }
        List<WebDAVFile> queue = new ArrayList<>(adapter.files);
        int index = -1;
        for (int i = 0; i < queue.size(); i++)
            if (queue.get(i).getCacheKey().equals(file.getCacheKey())) { index = i; break; }
        if (index < 0) return;
        if (!history.record(file, owner)) toast(getString(R.string.search_history_save_error));
        musicPlayer.setPlaylist(queue, index);
        musicPlayer.playFile(file);
        MusicService.start(this);
        if (historyMode) search();
    }

    @Override protected void onResume() {
        super.onResume();
        // Snapshot/config/sort listeners already invalidate their actual source changes.
        if (adapter != null) {
            if (historyMode) search();
            adapter.refreshPlayingTrack(musicPlayer.getCurrentTrack());
            adapter.refreshDownloadState();
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", query.getText().toString()); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed = true;
        cache.removeSnapshotListener(sourceChanged);
        cache.removeDownloadListener(downloadChanged);
        getSharedPreferences("player_prefs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(sortChanged);
        ServerStore.prefs(this).unregisterOnSharedPreferenceChangeListener(configChanged);
        controller.close(); worker.shutdownNow(); ui.removeCallbacksAndMessages(null);
        playerBar.release(); musicPlayer.removePlaybackListener(this);
        super.onDestroy();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    public void onTrackChanged(WebDAVFile file) { adapter.refreshPlayingTrack(file); }
    public void onPlayStateChanged(boolean playing) {}
    // Progress belongs exclusively to PlayerBarController, never to result rows.
    public void onProgress(int position, int duration) {}
    public void onPlayModeChanged(int mode) {}
    public void onError(String error) { if (!isFinishing() && hasWindowFocus()) toast(error); }

    private final class ResultAdapter extends RecyclerView.Adapter<ResultHolder> {
        private List<WebDAVFile> files = Collections.emptyList();
        private String playingKey;
        private final Map<String, Boolean> downloaded = new HashMap<>();
        private final Object statePayload = new Object();

        private void setFiles(List<WebDAVFile> result) {
            files = result;
            WebDAVFile current = musicPlayer.getCurrentTrack();
            playingKey = current == null ? null : current.getCacheKey();
            downloaded.clear();
            for (WebDAVFile file : files) downloaded.put(file.getCacheKey(), cache.isDownloaded(file.getCacheKey()));
            notifyDataSetChanged();
        }

        private void refreshPlayingTrack(WebDAVFile current) {
            String nextKey = current == null ? null : current.getCacheKey();
            if (Objects.equals(playingKey, nextKey)) return;
            String previousKey = playingKey;
            playingKey = nextKey;
            for (int i = 0; i < files.size(); i++) {
                String key = files.get(i).getCacheKey();
                if (Objects.equals(key, previousKey) || Objects.equals(key, nextKey))
                    notifyItemChanged(i, statePayload);
            }
        }

        private void refreshDownloadState() {
            for (int i = 0; i < files.size(); i++) {
                String key = files.get(i).getCacheKey();
                boolean value = cache.isDownloaded(key);
                Boolean previous = downloaded.put(key, value);
                if (previous != null && previous != value) notifyItemChanged(i, statePayload);
                else if (previous == null && value) notifyItemChanged(i, statePayload);
            }
        }

        @Override public void onBindViewHolder(ResultHolder holder, int position, List<Object> payloads) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position);
            else bindState(holder, files.get(position));
        }

        private void bindState(ResultHolder holder, WebDAVFile file) {
            boolean playing = Objects.equals(playingKey, file.getCacheKey());
            holder.itemView.setActivated(playing);
            holder.title.setTextColor(getResources().getColor(playing ? R.color.item_playing_text : R.color.text_primary, getTheme()));
            holder.download.setText(cache.isDownloaded(file.getCacheKey()) ? R.string.search_downloaded : R.string.search_not_downloaded);
        }
        @Override public ResultHolder onCreateViewHolder(ViewGroup parent, int type) {
            return new ResultHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_result, parent, false));
        }
        @Override public int getItemCount() { return files.size(); }
        @Override public void onBindViewHolder(ResultHolder holder, int position) {
            WebDAVFile file = files.get(position);
            bindState(holder, file);
            holder.title.setText(SnapshotSearchEngine.title(file));
            String path = file.getRelativePath(); int slash = path.lastIndexOf('/');
            holder.location.setText(file.getServerName() + " · /" + (slash < 0 ? "" : path.substring(0, slash)));
            final boolean boundHistory = historyMode;
            holder.itemView.setOnClickListener(v -> {
                if (boundHistory == historyMode && adapter.files.contains(file)) play(file);
            });
            holder.remove.setVisibility(View.GONE);
            holder.expand.setVisibility(boundHistory ? View.VISIBLE : View.GONE);
            holder.actions.setVisibility(View.GONE);
            holder.actionsExpanded = false;
            holder.expand.setOnClickListener(v -> {
                if (!boundHistory || !historyMode) return;
                holder.actionsExpanded = !holder.actionsExpanded;
                holder.remove.setVisibility(holder.actionsExpanded ? View.VISIBLE : View.GONE);
                holder.enterDirectory.setVisibility(holder.actionsExpanded ? View.VISIBLE : View.GONE);
                animateHistoryActions(holder, holder.actionsExpanded);
            });
            holder.enterDirectory.setOnClickListener(v -> {
                if (!boundHistory || !historyMode || !adapter.files.contains(file)) return;
                ServerProfile target = null;
                for (ServerProfile server : ServerStore.list(SearchActivity.this))
                    if (server.getId().equals(file.getServerId()) && server.isComplete()) { target = server; break; }
                if (target == null) { toast(getString(R.string.search_removed_server)); sourceChanged.run(); return; }
                Intent intent = new Intent(SearchActivity.this, MainActivity.class);
                intent.putExtra("search_history_server_id", target.getId());
                intent.putExtra("search_history_path", file.getRelativePath());
                startActivity(intent);
            });
            holder.remove.setOnClickListener(v -> {
                if (!historyMode || !boundHistory) return;
                if (!history.remove(file)) toast(getString(R.string.search_history_save_error));
                search();
            });
        }
    }
    private void animateHistoryActions(ResultHolder holder, boolean expanded) {
        holder.actions.setVisibility(View.VISIBLE);
        holder.actions.setAlpha(expanded ? 0f : 1f);
        holder.actions.animate().alpha(expanded ? 1f : 0f).setDuration(180).withEndAction(() -> {
            holder.actions.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }).start();
        View text = holder.title.getParent() instanceof View ? (View) holder.title.getParent() : holder.title;
        View arrow = holder.expand;
        text.animate().translationX(expanded ? -arrow.getWidth() : 0f).setDuration(180).start();
        holder.expand.setRotation(expanded ? 90f : 0f);
    }

    private static final class ResultHolder extends RecyclerView.ViewHolder {
        final TextView title, location, download;
        final View remove, expand, actions, enterDirectory;
        boolean actionsExpanded;
        ResultHolder(View view) {
            super(view); title = view.findViewById(R.id.searchSongTitle);
            location = view.findViewById(R.id.searchSongLocation); download = view.findViewById(R.id.searchSongDownload);
            remove = view.findViewById(R.id.searchHistoryDelete);
            expand = view.findViewById(R.id.searchHistoryExpand);
            actions = view.findViewById(R.id.searchHistoryActions);
            enterDirectory = view.findViewById(R.id.searchHistoryEnterDirectory);
        }
    }
}
