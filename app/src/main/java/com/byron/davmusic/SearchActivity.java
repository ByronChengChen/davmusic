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
    private View loading;
    private volatile boolean destroyed;
    // May be invoked by a background snapshot publisher. Fence immediately, then schedule UI work.
    private final Runnable sourceChanged = () -> {
        sourceRevision.incrementAndGet();
        ui.post(() -> { if (!destroyed && controller != null) { controller.invalidate(); search(); } });
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener configChanged = (prefs, key) -> {
        if ("servers_json".equals(key)) sourceChanged.run();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_search);
        cache = LocalCacheManager.getInstance(this);
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
        adapter.files = Collections.emptyList(); adapter.notifyDataSetChanged();
        if (text.trim().isEmpty()) {
            controller.invalidate(); loading.setVisibility(View.GONE);
            status.setText(R.string.search_empty_query); return;
        }
        loading.setVisibility(View.VISIBLE); status.setText(R.string.search_loading);
        controller.search(text, TrackSorter.getMode(this), result -> {
            adapter.files = result; adapter.notifyDataSetChanged(); loading.setVisibility(View.GONE);
            status.setText(result.isEmpty() ? getString(R.string.search_no_results)
                    : getString(R.string.search_count, result.size()));
        });
    }

    private void play(WebDAVFile file) {
        boolean configured = false;
        for (ServerProfile server : ServerStore.list(this))
            if (server.getId().equals(file.getServerId())) { configured = true; break; }
        if (!configured) { toast(getString(R.string.search_removed_server)); sourceChanged.run(); return; }
        if (!NetworkUtils.isNetworkConnected(this) && !cache.isDownloaded(file.getCacheKey())) {
            toast(getString(R.string.search_offline)); return;
        }
        List<WebDAVFile> queue = new ArrayList<>(adapter.files);
        int index = -1;
        for (int i = 0; i < queue.size(); i++)
            if (queue.get(i).getCacheKey().equals(file.getCacheKey())) { index = i; break; }
        if (index < 0) return;
        musicPlayer.setPlaylist(queue, index);
        musicPlayer.playFile(file);
        MusicService.start(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (controller != null) { sourceChanged.run(); adapter.notifyDataSetChanged(); }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", query.getText().toString()); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed = true;
        cache.removeSnapshotListener(sourceChanged);
        ServerStore.prefs(this).unregisterOnSharedPreferenceChangeListener(configChanged);
        controller.close(); worker.shutdownNow(); ui.removeCallbacksAndMessages(null);
        playerBar.release(); musicPlayer.removePlaybackListener(this);
        super.onDestroy();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    public void onTrackChanged(WebDAVFile file) { adapter.notifyDataSetChanged(); }
    public void onPlayStateChanged(boolean playing) { adapter.notifyDataSetChanged(); }
    public void onProgress(int position, int duration) {
        // Refresh visible download indicators without rereading any JSON.
        RecyclerView list = findViewById(R.id.searchResults);
        LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition(), last = lm.findLastVisibleItemPosition();
        if (first >= 0 && last >= first) adapter.notifyItemRangeChanged(first, last - first + 1);
    }
    public void onPlayModeChanged(int mode) {}
    public void onError(String error) { if (!isFinishing() && hasWindowFocus()) toast(error); }

    private final class ResultAdapter extends RecyclerView.Adapter<ResultHolder> {
        private List<WebDAVFile> files = Collections.emptyList();
        @Override public ResultHolder onCreateViewHolder(ViewGroup parent, int type) {
            return new ResultHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_result, parent, false));
        }
        @Override public int getItemCount() { return files.size(); }
        @Override public void onBindViewHolder(ResultHolder holder, int position) {
            WebDAVFile file = files.get(position);
            WebDAVFile current = musicPlayer.getCurrentTrack();
            boolean playing = current != null && current.getCacheKey().equals(file.getCacheKey());
            holder.itemView.setActivated(playing);
            holder.title.setText(SnapshotSearchEngine.title(file));
            holder.title.setTextColor(getResources().getColor(playing ? R.color.item_playing_text : R.color.text_primary, getTheme()));
            String path = file.getRelativePath(); int slash = path.lastIndexOf('/');
            holder.location.setText(file.getServerName() + " · /" + (slash < 0 ? "" : path.substring(0, slash)));
            holder.download.setText(cache.isDownloaded(file.getCacheKey()) ? R.string.search_downloaded : R.string.search_not_downloaded);
            holder.itemView.setOnClickListener(v -> play(file));
        }
    }
    private static final class ResultHolder extends RecyclerView.ViewHolder {
        final TextView title, location, download;
        ResultHolder(View view) {
            super(view); title = view.findViewById(R.id.searchSongTitle);
            location = view.findViewById(R.id.searchSongLocation); download = view.findViewById(R.id.searchSongDownload);
        }
    }
}
