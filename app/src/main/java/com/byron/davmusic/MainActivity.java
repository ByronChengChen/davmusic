package com.byron.davmusic;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements 
        FileListAdapter.OnItemClickListener,
        MusicPlayer.OnPlaybackListener,
        SwipeRefreshLayout.OnRefreshListener {
    
    private static final String TAG = "MainActivity";
    
    private Toolbar toolbar;
    private TextView pathTextView;
    private TextView networkStatusTextView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private LinearLayout miniPlayerLayout;
    private TextView playerTitleTextView;
    private ImageButton playPauseButton;
    private ImageButton previousButton;
    private ImageButton nextButton;
    private ProgressBar playerProgressBar;
    
    private FileListAdapter fileListAdapter;
    private ExecutorService executorService;
    private Handler mainHandler;
    private ProgressDialog progressDialog;
    
    private WebDAVClient webDAVClient;
    private LocalCacheManager cacheManager;
    private MusicPlayer musicPlayer;
    
    private List<String> pathStack = new ArrayList<>();
    private String currentPath = "/";
    private boolean isOfflineMode = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 无需运行时权限：下载写入的是 App 私有目录（getExternalFilesDir），
        // 上传通过系统文件选择器（SAF）由系统代读，均不需要存储权限。
        initViews();
        initManagers();
        initPlayer();

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 加载当前路径
        loadCurrentPath();
    }
    
    private void initViews() {
        // 初始化 Toolbar
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        
        // 路径显示
        pathTextView = findViewById(R.id.pathTextView);
        networkStatusTextView = findViewById(R.id.networkStatusTextView);
        
        // 下拉刷新
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(this);
        swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light
        );
        
        // RecyclerView
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        fileListAdapter = new FileListAdapter(this);
        fileListAdapter.setOnItemClickListener(this);
        recyclerView.setAdapter(fileListAdapter);
        
        // 迷你播放器
        miniPlayerLayout = findViewById(R.id.miniPlayerLayout);
        playerTitleTextView = findViewById(R.id.playerTitleTextView);
        playPauseButton = findViewById(R.id.playPauseButton);
        previousButton = findViewById(R.id.previousButton);
        nextButton = findViewById(R.id.nextButton);
        playerProgressBar = findViewById(R.id.playerProgressBar);
        
        // 播放器按钮点击事件
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        previousButton.setOnClickListener(v -> playPrevious());
        nextButton.setOnClickListener(v -> playNext());
        
        miniPlayerLayout.setOnClickListener(v -> {
            // 可以在这里实现点击播放器展开详细播放界面的功能
            Toast.makeText(this, "点击了播放器", Toast.LENGTH_SHORT).show();
        });
        
        // 上传按钮
        findViewById(R.id.fabUpload).setOnClickListener(v -> showUploadDialog());
        
        // 返回按钮
        findViewById(R.id.backButton).setOnClickListener(v -> navigateUp());
    }
    
    private void initManagers() {
        // 获取 WebDAV 配置（未配置时返回空字符串，不会 NPE）
        String serverUrl = ServerConfigActivity.getServerUrl(this);
        String username = ServerConfigActivity.getUsername(this);
        String password = ServerConfigActivity.getPassword(this);

        // 保险：配置缺失时引导回配置页，避免后续请求静默失败
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            Toast.makeText(this, "请先配置 WebDAV 服务器", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, ServerConfigActivity.class));
            finish();
            return;
        }

        // 配置 WebDAV 客户端
        webDAVClient = WebDAVClient.getInstance();
        webDAVClient.configure(serverUrl, username, password);
        
        // 初始化缓存管理器
        cacheManager = LocalCacheManager.getInstance(this);
        
        // 设置认证头给播放器
        Map<String, String> authHeaders = new HashMap<>();
        String credentials = username + ":" + password;
        authHeaders.put("Authorization", "Basic " + android.util.Base64.encodeToString(
                credentials.getBytes(), android.util.Base64.NO_WRAP));
        
        // 初始化音乐播放器
        musicPlayer = MusicPlayer.getInstance(this);
        musicPlayer.setAuthHeaders(authHeaders);
        musicPlayer.addPlaybackListener(this);
    }
    
    private void initPlayer() {
        updatePlayerUI();
    }
    
    private void loadCurrentPath() {
        if (!webDAVClient.isConfigured()) {
            // 如果未配置，跳转到配置界面
            startActivity(new Intent(this, ServerConfigActivity.class));
            finish();
            return;
        }
        
        updateNetworkStatus();
        updatePathDisplay();
        
        if (isOfflineMode || !NetworkUtils.isNetworkConnected(this)) {
            // 离线模式：从快照加载
            loadFromSnapshot();
        } else {
            // 在线模式：从服务器加载
            loadFromServer();
        }
    }
    
    private void loadFromServer() {
        showLoading("正在加载...");
        
        executorService.execute(() -> {
            webDAVClient.listFolder(currentPath, new WebDAVClient.WebDAVCallback<List<WebDAVFile>>() {
                @Override
                public void onSuccess(List<WebDAVFile> files) {
                    // 更新文件的下载状态
                    updateFileDownloadStates(files);
                    
                    // 保存快照
                    cacheManager.saveSnapshot(currentPath, files);
                    
                    // 更新 UI
                    mainHandler.post(() -> {
                        dismissLoading();
                        fileListAdapter.setFiles(files);
                        fileListAdapter.setOfflineMode(false);
                        swipeRefreshLayout.setRefreshing(false);
                    });
                }
                
                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "加载失败: " + e.getMessage(), e);
                    
                    mainHandler.post(() -> {
                        dismissLoading();
                        swipeRefreshLayout.setRefreshing(false);
                        
                        // 如果在线加载失败，尝试从快照加载
                        if (cacheManager.hasSnapshot(currentPath)) {
                            Toast.makeText(MainActivity.this, "网络连接失败，使用离线数据", Toast.LENGTH_SHORT).show();
                            isOfflineMode = true;
                            updateNetworkStatus();
                            loadFromSnapshot();
                        } else {
                            Toast.makeText(MainActivity.this, "加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        });
    }
    
    private void loadFromSnapshot() {
        showLoading("正在加载离线数据...");
        
        executorService.execute(() -> {
            List<WebDAVFile> files = cacheManager.loadSnapshot(currentPath);
            
            mainHandler.post(() -> {
                dismissLoading();
                
                if (files != null) {
                    updateFileDownloadStates(files);
                    fileListAdapter.setFiles(files);
                    fileListAdapter.setOfflineMode(true);
                    Toast.makeText(MainActivity.this, "离线模式", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "没有离线数据可用", Toast.LENGTH_SHORT).show();
                    fileListAdapter.setFiles(new ArrayList<>());
                }
                
                swipeRefreshLayout.setRefreshing(false);
            });
        });
    }
    
    private void updateFileDownloadStates(List<WebDAVFile> files) {
        for (WebDAVFile file : files) {
            if (!file.isCollection()) {
                if (cacheManager.isDownloaded(file.getHref())) {
                    file.setDownloadState(WebDAVFile.DownloadState.DOWNLOADED);
                    file.setLocalPath(cacheManager.getLocalFile(file.getHref()).getAbsolutePath());
                } else {
                    file.setDownloadState(WebDAVFile.DownloadState.NOT_DOWNLOADED);
                }
            }
        }
    }
    
    private void updateNetworkStatus() {
        boolean isConnected = NetworkUtils.isNetworkConnected(this);
        isOfflineMode = !isConnected || isOfflineMode;
        
        if (isOfflineMode) {
            networkStatusTextView.setText("离线");
            networkStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
        } else {
            networkStatusTextView.setText(NetworkUtils.getNetworkStatusString(this));
            networkStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
    }
    
    private void updatePathDisplay() {
        pathTextView.setText(currentPath);
        
        // 更新返回按钮状态
        View backButton = findViewById(R.id.backButton);
        backButton.setVisibility(currentPath.equals("/") ? View.GONE : View.VISIBLE);
    }
    
    @Override
    public void onRefresh() {
        // 下拉刷新
        if (isOfflineMode) {
            // 离线模式下，尝试重新连接
            isOfflineMode = false;
            updateNetworkStatus();
        }
        
        loadCurrentPath();
    }
    
    @Override
    public void onItemClick(WebDAVFile file) {
        if (file.isCollection()) {
            // 进入文件夹
            navigateToFolder(file);
        } else if (file.isAudio()) {
            // 播放音频文件
            playAudioFile(file);
        } else {
            Toast.makeText(this, "暂不支持预览此类文件: " + file.getDisplayName(), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    public void onItemLongClick(WebDAVFile file, View view) {
        showFileContextMenu(file, view);
    }
    
    @Override
    public void onDownloadClick(WebDAVFile file) {
        downloadFile(file);
    }
    
    @Override
    public void onDeleteClick(WebDAVFile file) {
        deleteLocalFile(file);
    }
    
    private void navigateToFolder(WebDAVFile folder) {
        // 保存当前路径到栈，供返回时恢复
        pathStack.add(currentPath);

        // 直接使用解析阶段得到的相对路径（已剥离 baseUrl 前缀并 URL 解码），
        // 避免用 displayName 拼接导致路径重复、中文编码错误等问题。
        String rel = folder.getRelativePath();
        if (rel != null && !rel.isEmpty()) {
            currentPath = "/" + rel;
        } else {
            // 兜底：relativePath 缺失时用 displayName 拼接
            String name = folder.getDisplayName();
            if (name == null) name = "";
            currentPath = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
        }

        // 加载新路径
        loadCurrentPath();
    }
    
    private void navigateUp() {
        if (pathStack.isEmpty()) {
            if (!currentPath.equals("/")) {
                // 回到根目录
                currentPath = "/";
                loadCurrentPath();
            }
        } else {
            // 从栈中取出上一个路径
            currentPath = pathStack.remove(pathStack.size() - 1);
            loadCurrentPath();
        }
    }
    
    private void playAudioFile(WebDAVFile file) {
        // 获取当前文件夹的所有音频文件
        List<WebDAVFile> audioFiles = new ArrayList<>();
        List<WebDAVFile> allFiles = fileListAdapter.getFiles();
        
        for (WebDAVFile f : allFiles) {
            if (f.isAudio()) {
                audioFiles.add(f);
            }
        }
        
        // 设置播放列表
        int startIndex = -1;
        for (int i = 0; i < audioFiles.size(); i++) {
            if (audioFiles.get(i).getHref().equals(file.getHref())) {
                startIndex = i;
                break;
            }
        }
        
        if (startIndex != -1) {
            musicPlayer.setPlaylist(audioFiles, startIndex);
            musicPlayer.playFile(file);
            
            // 显示播放器
            miniPlayerLayout.setVisibility(View.VISIBLE);
        }
    }
    
    private void downloadFile(WebDAVFile file) {
        if (!NetworkUtils.isNetworkConnected(this)) {
            Toast.makeText(this, "网络不可用，无法下载", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 更新下载状态
        file.setDownloadState(WebDAVFile.DownloadState.DOWNLOADING);
        fileListAdapter.updateFile(file);
        
        // 获取本地存储路径
        File destFile = cacheManager.getDownloadDestination(file.getHref());
        
        showLoading("正在下载: " + file.getDisplayName());
        
        executorService.execute(() -> {
            webDAVClient.download(file.getHref(), destFile, new WebDAVClient.ProgressCallback() {
                @Override
                public void onProgress(int progress) {
                    // 可以在这里更新进度条
                    Log.d(TAG, "下���进度: " + progress + "%");
                }
                
                @Override
                public void onSuccess(Object result) {
                    mainHandler.post(() -> {
                        dismissLoading();
                        
                        // 标记文件已下载
                        cacheManager.markFileDownloaded(file.getHref(), destFile);
                        file.setDownloadState(WebDAVFile.DownloadState.DOWNLOADED);
                        file.setLocalPath(destFile.getAbsolutePath());
                        fileListAdapter.updateFile(file);
                        
                        Toast.makeText(MainActivity.this, "下载完成: " + file.getDisplayName(), Toast.LENGTH_SHORT).show();
                    });
                }
                
                @Override
                public void onError(Exception e) {
                    mainHandler.post(() -> {
                        dismissLoading();
                        
                        file.setDownloadState(WebDAVFile.DownloadState.NOT_DOWNLOADED);
                        fileListAdapter.updateFile(file);
                        
                        Toast.makeText(MainActivity.this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
    }
    
    private void deleteLocalFile(WebDAVFile file) {
        new AlertDialog.Builder(this)
                .setTitle("删除本地文件")
                .setMessage("确定要删除本地文件吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    cacheManager.removeDownloadedFile(file.getHref());
                    file.setDownloadState(WebDAVFile.DownloadState.NOT_DOWNLOADED);
                    file.setLocalPath(null);
                    fileListAdapter.updateFile(file);
                    
                    Toast.makeText(this, "已删除本地文件: " + file.getDisplayName(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void showFileContextMenu(WebDAVFile file, View view) {
        new AlertDialog.Builder(this)
                .setTitle(file.getDisplayName())
                .setItems(new String[]{"详细信息", "重命名", "分享", "取消"}, (dialog, which) -> {
                    switch (which) {
                        case 0: // 详细信息
                            showFileDetails(file);
                            break;
                        case 1: // 重命名
                            showRenameDialog(file);
                            break;
                        case 2: // 分享
                            shareFile(file);
                            break;
                    }
                })
                .show();
    }
    
    private void showFileDetails(WebDAVFile file) {
        String details = "文件名: " + file.getDisplayName() + "\n"
                + "大小: " + FileUtils.formatFileSize(file.getContentLength()) + "\n"
                + "类型: " + (file.isCollection() ? "文件夹" : file.getContentType()) + "\n"
                + "下载状态: " + file.getDownloadState() + "\n"
                + "路径: " + file.getHref();
        
        new AlertDialog.Builder(this)
                .setTitle("文件详细信息")
                .setMessage(details)
                .setPositiveButton("确定", null)
                .show();
    }
    
    private void showRenameDialog(WebDAVFile file) {
        Toast.makeText(this, "重命名功能待实现", Toast.LENGTH_SHORT).show();
    }
    
    private void shareFile(WebDAVFile file) {
        if (file.getDownloadState() == WebDAVFile.DownloadState.DOWNLOADED && file.getLocalPath() != null) {
            File localFile = new File(file.getLocalPath());
            if (localFile.exists()) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(file.getContentType());
                shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(localFile));
                startActivity(Intent.createChooser(shareIntent, "分享文件"));
            } else {
                Toast.makeText(this, "本地文件不存在", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "请先下载文件", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showUploadDialog() {
        Toast.makeText(this, "上传功能待实现", Toast.LENGTH_SHORT).show();
    }
    
    private void togglePlayPause() {
        if (musicPlayer.isPlaying()) {
            musicPlayer.pause();
        } else {
            musicPlayer.resume();
        }
    }
    
    private void playPrevious() {
        musicPlayer.playPrevious();
    }
    
    private void playNext() {
        musicPlayer.playNext();
    }
    
    private void updatePlayerUI() {
        WebDAVFile currentTrack = musicPlayer.getCurrentTrack();
        
        if (currentTrack != null) {
            playerTitleTextView.setText(currentTrack.getDisplayName());
            playerProgressBar.setMax(musicPlayer.getDuration());
            playerProgressBar.setProgress(musicPlayer.getCurrentPosition());
            
            if (musicPlayer.isPlaying()) {
                playPauseButton.setImageResource(R.drawable.ic_pause);
            } else {
                playPauseButton.setImageResource(R.drawable.ic_play);
            }
            
            // 更新播放列表按钮状态
            previousButton.setEnabled(musicPlayer.getPlaylist().size() > 1);
            nextButton.setEnabled(musicPlayer.getPlaylist().size() > 1);
            
            // 显示播放器
            miniPlayerLayout.setVisibility(View.VISIBLE);
        } else {
            // 隐藏播放器
            miniPlayerLayout.setVisibility(View.GONE);
        }
    }
    
    // MusicPlayer.OnPlaybackListener 实现
    @Override
    public void onTrackChanged(WebDAVFile track) {
        mainHandler.post(() -> {
            playerTitleTextView.setText(track.getDisplayName());
        });
    }
    
    @Override
    public void onPlayStateChanged(boolean isPlaying) {
        mainHandler.post(() -> {
            if (isPlaying) {
                playPauseButton.setImageResource(R.drawable.ic_pause);
            } else {
                playPauseButton.setImageResource(R.drawable.ic_play);
            }
        });
    }
    
    @Override
    public void onProgress(int position, int duration) {
        mainHandler.post(() -> {
            playerProgressBar.setMax(duration);
            playerProgressBar.setProgress(position);
        });
    }
    
    @Override
    public void onError(String error) {
        mainHandler.post(() -> {
            Toast.makeText(this, "播放错误: " + error, Toast.LENGTH_LONG).show();
        });
    }
    
    // 菜单
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.menu_settings) {
            startActivity(new Intent(this, ServerConfigActivity.class));
            return true;
        } else if (id == R.id.menu_refresh) {
            onRefresh();
            return true;
        } else if (id == R.id.menu_clear_cache) {
            clearCache();
            return true;
        } else if (id == R.id.menu_about) {
            showAboutDialog();
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }
    
    private void clearCache() {
        new AlertDialog.Builder(this)
                .setTitle("清空缓存")
                .setMessage("确定要清空所有缓存数据吗？")
                .setPositiveButton("清空", (dialog, which) -> {
                    cacheManager.clearAllCache();
                    fileListAdapter.setFiles(new ArrayList<>());
                    Toast.makeText(this, "缓存已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("关于 DavMusic")
                .setMessage("DavMusic v1.0\nWebDAV 音乐播放器")
                .setPositiveButton("确定", null)
                .show();
    }
    
    private void showLoading(String message) {
        mainHandler.post(() -> {
            if (progressDialog == null) {
                progressDialog = new ProgressDialog(this);
                progressDialog.setCancelable(false);
            }
            progressDialog.setMessage(message);
            progressDialog.show();
        });
    }
    
    private void dismissLoading() {
        mainHandler.post(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
        });
    }
    
    @Override
    public void onBackPressed() {
        if (!pathStack.isEmpty()) {
            navigateUp();
        } else {
            super.onBackPressed();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        
        if (musicPlayer != null) {
            musicPlayer.removePlaybackListener(this);
        }
    }
}
