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
import android.widget.SeekBar;
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
    private SeekBar playerProgressBar;
    
    private FileListAdapter fileListAdapter;
    private ExecutorService executorService;
    private Handler mainHandler;
    private ProgressDialog progressDialog;
    
    private RemoteLogger rlog;
    private WebDAVClient webDAVClient;
    private LocalCacheManager cacheManager;
    private MusicPlayer musicPlayer;
    
    private List<String> pathStack = new ArrayList<>();
    private String currentPath = "/";
    private boolean isOfflineMode = false;
    private boolean isSeeking = false;   // 用户正在拖动进度条时，暂停自动刷新

    // ---- 上传相关 ----
    /** 系统文件选择器（SAF），可多选；无需存储权限 */
    private androidx.activity.result.ActivityResultLauncher<String[]> uploadPicker;
    /** Android 13+ 通知权限申请 */
    private androidx.activity.result.ActivityResultLauncher<String> notificationPermLauncher;
    /** 多选上传的队列，逐个串行上传 */
    private final java.util.Deque<Uri> uploadQueue = new java.util.ArrayDeque<>();
    private int uploadTotal = 0;
    private int uploadDone = 0;
    /** 上传进度对话框（带百分比，不可取消误触） */
    private AlertDialog uploadDialog;
    private TextView uploadProgressText;
    private android.widget.ProgressBar uploadProgressBar;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 注册系统文件选择器（必须在 onCreate 内、onStart 之前完成注册）。
        // 用 SAF（ACTION_OPEN_DOCUMENT）而不是 READ_EXTERNAL_STORAGE：
        // 系统代我们读取用户选中的文件，因此无需任何存储权限。
        uploadPicker = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts
                        .OpenMultipleDocuments(),
                uris -> {
                    if (uris == null || uris.isEmpty()) {
                        return;   // 用户取消
                    }
                    startUpload(uris);
                });

        // Android 13+ 通知需要运行时授权。未授权时前台服务仍能运行
        // （保活有效），但通知不可见、锁屏控制缺失，因此主动申请一次。
        notificationPermLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts
                        .RequestPermission(),
                granted -> {
                    if (rlog != null) {
                        rlog.i(TAG, "通知权限申请结果: " + granted);
                    }
                });
        requestNotificationPermissionIfNeeded();

        // 无需运行时权限：下载写入的是 App 私有目录（getExternalFilesDir），
        // 上传通过系统文件选择器（SAF）由系统代读，均不需要存储权限。
        initViews();
        initManagers();
        initPlayer();

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 恢复上次的导航位置（Activity 被系统回收后重建时，
        // 若不恢复会退回根目录，表现为"切回前台回到首页"）
        boolean restored = restoreNavState(savedInstanceState);

        // 加载当前路径（restored 时 currentPath 已指向上次所在目录）
        loadCurrentPath();

        // 内容渲染完成后再恢复滚动位置
        restoreScrollPos(savedInstanceState);

        // 若此前已在播放（服务仍在运行），确保前台服务处于活动状态。
        // 这样即便 Activity 被回收重建，保活链条也不会断。
        if (musicPlayer != null && musicPlayer.getCurrentTrack() != null) {
            MusicService.start(this);
            if (rlog != null) rlog.i(TAG, "检测到已有播放，重新确保前台服务运行");
        }

        if (rlog != null) {
            rlog.i(TAG, "onCreate 完成: restored=" + restored
                    + " path=" + currentPath);
        }
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

        // 进度条可拖动：用户按住/拖动时暂停自动刷新，松手后 seekTo。
        // 若不做这个保护，播放进度每秒回调会把手柄"拽"回去，表现为拖不动。
        playerProgressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 拖动时不需要额外处理，松手才真正 seek
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                musicPlayer.seekTo(seekBar.getProgress());
                isSeeking = false;
            }
        });
        
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
        rlog = RemoteLogger.getInstance(this);
        rlog.i(TAG, "MainActivity initManagers");
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
    
    /**
     * 加载当前目录。
     *
     * 策略：缓存优先 + 后台刷新（stale-while-revalidate）。
     *   1. 有本地快照 → 立即渲染，用户瞬间看到内容
     *   2. 同时发起网络请求
     *   3. 新数据回来后覆盖快照、刷新 UI
     *
     * 离线分支（关键修正）：不再使用"目录快照"，而是列出
     * 【实际已下载到本地的音频】，按当前路径过滤。原因：
     * 目录快照记录的是"上次联网时云端有什么"，其中大部分并未下载，
     * 离线时点开必然播放失败；且从未访问过的目录没有快照，
     * 表现为"同一文件夹不同时间进去内容还不一样"。
     */
    private void loadCurrentPath() {
        if (!webDAVClient.isConfigured()) {
            // 如果未配置，跳转到配置界面
            startActivity(new Intent(this, ServerConfigActivity.class));
            finish();
            return;
        }

        // 网络恢复检测：isOfflineMode 之前是单向开关（只会被置 true），
        // 导致联网后仍卡在离线模式。这里在每次加载前先根据真实网络状态
        // 复位 —— 有网络且用户未手动锁定离线，就回到在线模式。
        refreshOfflineFlag();

        updateNetworkStatus();
        updatePathDisplay();

        // 返回时先清空列表，避免把上一个目录的文件短暂显示成当前目录的内容。
        fileListAdapter.setFiles(new ArrayList<>());

        final boolean online = !isOfflineMode && NetworkUtils.isNetworkConnected(this);

        // 离线：直接列出本地已下载歌曲（不需要云端快照）
        if (!online) {
            showLocalDownloads();
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        // 在线：缓存优先 —— 有快照先渲染，再去网络拉最新
        boolean rendered = false;
        List<WebDAVFile> cached = cacheManager.loadSnapshot(currentPath);
        if (cached != null && !cached.isEmpty()) {
            updateFileDownloadStates(cached);
            fileListAdapter.setFiles(cached);
            fileListAdapter.setOfflineMode(false);
            rendered = true;
            Log.d(TAG, "命中快照，先渲染 " + cached.size() + " 项: " + currentPath);
        }

        loadFromServer(!rendered);
    }

    /**
     * 根据真实网络状态复位 isOfflineMode。
     *
     * 原有缺陷：isOfflineMode 只在加载失败时被置 true，恢复网络后无人复位，
     * 于是永久停留在离线模式。这里每次加载都重新判断。
     */
    private void refreshOfflineFlag() {
        boolean connected = NetworkUtils.isNetworkConnected(this);
        if (connected && isOfflineMode) {
            isOfflineMode = false;
            if (rlog != null) rlog.i(TAG, "网络已恢复，退出离线模式");
        } else if (!connected && !isOfflineMode) {
            isOfflineMode = true;
            if (rlog != null) rlog.i(TAG, "网络已断开，进入离线模式");
        }
    }

    /**
     * 离线模式：显示【完整目录内容】（与在线时一致），并标注下载状态。
     *
     * 数据来源优先级：
     *   1. 该目录的云端快照 —— 记录完整的目录树与文件列表
     *   2. 快照缺失时，用本地已下载文件反推目录结构（兜底）
     *
     * 这样离线时仍能看到整张歌单：已下载的直接可播，未下载的点了会
     * 提示需要联网，而不是像之前那样"整个目录都看不到"或"只看到零星
     * 几个下载过的文件"。
     */
    private void showLocalDownloads() {
        executorService.execute(() -> {
            // 1) 优先用快照（完整目录）
            List<WebDAVFile> listed = cacheManager.loadSnapshot(currentPath);
            boolean fromSnapshot = listed != null && !listed.isEmpty();

            // 2) 快照缺失：用已下载文件反推目录结构
            List<WebDAVFile> items;
            if (fromSnapshot) {
                items = listed;
            } else {
                items = cacheManager.listLocalTree(currentPath);
            }

            // 无论来源如何，都刷新一次下载状态标记
            if (!items.isEmpty()) {
                updateFileDownloadStates(items);
            }

            final boolean useSnapshot = fromSnapshot;
            final List<WebDAVFile> result = items;

            mainHandler.post(() -> {
                fileListAdapter.setOfflineMode(true);

                if (result.isEmpty()) {
                    fileListAdapter.setFiles(new ArrayList<>());
                    Toast.makeText(this,
                            "该目录无离线数据\n（联网浏览过的目录会自动缓存列表）",
                            Toast.LENGTH_LONG).show();
                } else {
                    fileListAdapter.setFiles(result);
                    if (!useSnapshot) {
                        Toast.makeText(this,
                                "离线模式（仅本地已下载内容）", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this,
                                "离线模式", Toast.LENGTH_SHORT).show();
                    }
                }
                updatePathDisplay();
                swipeRefreshLayout.setRefreshing(false);
            });
        });
    }

    /**
     * 从服务器加载。
     *
     * @param showLoading 是否显示加载指示。命中快照时传 false ——
     *                    已经有内容在显示，不该再盖一层 loading 遮罩。
     */
    private void loadFromServer(boolean showLoading) {
        if (showLoading) {
            showLoading("正在加载...");
        }

        executorService.execute(() -> {
            webDAVClient.listFolder(currentPath, new WebDAVClient.WebDAVCallback<List<WebDAVFile>>() {
                @Override
                public void onSuccess(List<WebDAVFile> files) {
                    // 更新文件的下载状态
                    updateFileDownloadStates(files);

                    // 只有拿到内容才覆盖快照 —— 避免一次空响应用空列表
                    // 把之前缓存好的目录数据抹掉
                    if (files != null && !files.isEmpty()) {
                        cacheManager.saveSnapshot(currentPath, files);
                    }

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

                        // 已经有缓存内容在显示：静默失败即可，不打断浏览
                        if (fileListAdapter.getItemCount() > 0) {
                            Toast.makeText(MainActivity.this,
                                    "刷新失败，显示的是缓存数据", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 没有内容可显示：退回离线模式，列出本地已下载歌曲
                        // （不再使用云端目录快照 —— 那些文件多数并未下载，
                        //   离线点开必然播放失败）
                        int localCount = cacheManager.getOfflineFileCount();
                        if (localCount > 0) {
                            Toast.makeText(MainActivity.this,
                                    "网络连接失败，切换到离线模式（本地已存 "
                                            + localCount + " 首）",
                                    Toast.LENGTH_SHORT).show();
                            isOfflineMode = true;
                            updateNetworkStatus();
                            showLocalDownloads();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
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
        // 下拉刷新：用户主动要求最新数据。
        // 不走 loadCurrentPath() 的"清空 + 快照渲染"流程，
        // 否则会把已有列表清掉再填回来，出现明显闪烁。
        // 保持当前列表显示，直接后台拉取。
        if (isOfflineMode) {
            // 离线模式下，尝试重新连接
            isOfflineMode = false;
            updateNetworkStatus();
        }

        if (!NetworkUtils.isNetworkConnected(this)) {
            swipeRefreshLayout.setRefreshing(false);
            Toast.makeText(this, "网络未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean hasContent = fileListAdapter.getItemCount() > 0;
        loadFromServer(!hasContent);
    }
    
    @Override
    public void onItemClick(WebDAVFile file) {
        if (file.isCollection()) {
            // 进入文件夹
            navigateToFolder(file);
        } else if (file.isAudio()) {
            // 离线模式下，未下载的歌曲无法播放 —— 提前给明确提示，
            // 而不是让 MediaPlayer 抛一个含糊的错误
            if (isOfflineMode && !cacheManager.isDownloaded(file.getHref())) {
                Toast.makeText(this,
                        "离线模式：该歌曲未下载，无法播放\n联网后可播放或先下载",
                        Toast.LENGTH_LONG).show();
                return;
            }
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

            // 开始播放即启动前台服务：进入前台后进程优先级提升，
            // 锁屏/切后台不会被系统回收，从而保证播放连续、
            // Activity 也不会被销毁（避免"切回前台回到首页"）。
            MusicService.start(this);

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
            webDAVClient.download(file, destFile, new WebDAVClient.ProgressCallback() {
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
                        // 同时写入离线索引：离线模式需要按目录列举已下载歌曲，
                        // 仅靠 downloadMap 无法还原目录结构与显示名
                        cacheManager.indexOfflineFile(file.getHref(),
                                file.getDisplayName(), destFile.length());
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
    
    /**
     * 上传入口：弹出选择菜单，然后拉起系统文件选择器。
     *
     * 之所以要有这个菜单，是因为 SAF 选择器没法限制"只选音频"，
     * 而用户从微信/QQ 收到的音乐常常落在 Download 目录里。
     */
    private void showUploadDialog() {
        if (isOfflineMode) {
            Toast.makeText(this, "离线模式下无法上传，请先连接网络", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!webDAVClient.isConfigured()) {
            Toast.makeText(this, "请先配置 WebDAV 服务器", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("上传到 " + currentPath)
                .setItems(new CharSequence[]{
                        "选择文件（可多选）",
                        "选择音频文件",
                }, (d, which) -> {
                    // OpenMultipleDocuments 的 mimeTypes 决定过滤条件
                    if (which == 0) {
                        uploadPicker.launch(new String[]{"*/*"});
                    } else {
                        uploadPicker.launch(new String[]{"audio/*"});
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 开始批量上传（串行，一次一个，避免云盘限流） */
    private void startUpload(List<Uri> uris) {
        uploadQueue.clear();
        uploadQueue.addAll(uris);
        uploadTotal = uris.size();
        uploadDone = 0;

        showUploadProgressDialog();
        uploadNext();
    }

    /** 取队列中的下一个上传；队列空则收尾 */
    private void uploadNext() {
        Uri uri = uploadQueue.poll();
        if (uri == null) {
            finishUpload();
            return;
        }

        String displayName = queryDisplayName(uri);
        updateUploadProgress(uploadDone, uploadTotal, displayName);

        executorService.execute(() -> {
            File tmp = null;
            try {
                // 1) 把 content:// 拷到应用私有缓存，OkHttp 需要一个真实文件
                tmp = copyUriToCache(uri, displayName);
                if (tmp == null || !tmp.exists() || tmp.length() == 0) {
                    throw new java.io.IOException("读取所选文件失败");
                }

                // 2) 目标远端路径（当前目录 + 文件名）
                String remotePath = joinPath(currentPath, displayName);

                // 3) 上传（回调已在主线程）
                final File toUpload = tmp;
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                final Exception[] err = new Exception[1];

                webDAVClient.upload(toUpload, remotePath,
                        new WebDAVClient.ProgressCallback() {
                            @Override
                            public void onProgress(int progress) {
                                updateUploadPercent(progress, displayName);
                            }

                            @Override
                            public void onSuccess(Object result) {
                                err[0] = null;
                                latch.countDown();
                            }

                            @Override
                            public void onError(Exception e) {
                                err[0] = e;
                                latch.countDown();
                            }
                        });

                // 等本次上传结束再处理下一个（串行，避免占用过多云盘配额）
                latch.await(10, java.util.concurrent.TimeUnit.MINUTES);

                if (err[0] != null) {
                    throw err[0];
                }

                mainHandler.post(() -> {
                    uploadDone++;
                    updateUploadProgress(uploadDone, uploadTotal, displayName);
                });

            } catch (Exception e) {
                Log.e(TAG, "上传失败: " + displayName, e);
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "上传失败: " + displayName + "\n" + msg,
                        Toast.LENGTH_LONG).show());
            } finally {
                if (tmp != null) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                mainHandler.post(this::uploadNext);
            }
        });
    }

    private void finishUpload() {
        dismissUploadProgressDialog();
        Toast.makeText(this, "上传完成（" + uploadDone + "/" + uploadTotal + "）",
                Toast.LENGTH_SHORT).show();
        // 刷新列表，让新文件立刻出现
        loadCurrentPath();
    }

    // ---- 上传进度对话框 ----

    private void showUploadProgressDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_upload_progress, null);
        uploadProgressText = v.findViewById(R.id.uploadProgressText);
        uploadProgressBar = v.findViewById(R.id.uploadProgressBar);

        uploadDialog = new AlertDialog.Builder(this)
                .setTitle("正在上传")
                .setView(v)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> {
                    uploadQueue.clear();
                    dismissUploadProgressDialog();
                    Toast.makeText(this, "已取消剩余上传", Toast.LENGTH_SHORT).show();
                })
                .create();
        uploadDialog.show();
    }

    private void updateUploadProgress(int done, int total, String currentName) {
        if (uploadProgressText == null) return;
        uploadProgressText.setText(String.format(
                java.util.Locale.getDefault(),
                "%d / %d\n%s", done + 1, total, currentName));
        if (uploadProgressBar != null) {
            uploadProgressBar.setMax(total);
            uploadProgressBar.setProgress(done);
        }
    }

    private void updateUploadPercent(int percent, String name) {
        if (uploadProgressText == null) return;
        uploadProgressText.setText(String.format(
                java.util.Locale.getDefault(),
                "%d / %d  (%d%%)\n%s",
                uploadDone + 1, uploadTotal, percent, name));
    }

    private void dismissUploadProgressDialog() {
        if (uploadDialog != null && uploadDialog.isShowing()) {
            uploadDialog.dismiss();
        }
        uploadDialog = null;
        uploadProgressText = null;
        uploadProgressBar = null;
    }

    // ---- SAF 辅助 ----

    /** 从 content:// URI 取显示名（含扩展名）；失败时回退为时间戳 */
    private String queryDisplayName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(
                uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "查询文件名失败: " + e.getMessage());
        }
        if (name == null || name.trim().isEmpty()) {
            name = "upload_" + System.currentTimeMillis();
        }
        // 去掉路径分隔符，避免构造出越界的远端路径
        return name.replace("/", "_").replace("\\", "_").trim();
    }

    /** 把 content:// 内容复制到应用私有缓存目录，返回临时文件 */
    private File copyUriToCache(Uri uri, String displayName) {
        File dir = new File(getCacheDir(), "upload");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File out = new File(dir, displayName);
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            if (in == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            Log.e(TAG, "复制所选文件失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** 拼接远端路径，保证单个分隔符且规范化 */
    private String joinPath(String dir, String name) {
        String d = dir == null || dir.isEmpty() ? "/" : dir;
        if (!d.startsWith("/")) d = "/" + d;
        if (!d.endsWith("/")) d = d + "/";
        return d + name;
    }
    
    private void togglePlayPause() {
        // 委托给 MusicPlayer：它内部会判断缓冲状态，
        // 缓冲期间忽略点击，避免 MediaPlayer 抛 IllegalStateException（what=-38）
        musicPlayer.togglePlayPause();
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
            if (!isSeeking) {
                playerProgressBar.setProgress(musicPlayer.getCurrentPosition());
            }
            
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
            // 高亮列表中正在播放的条目
            if (fileListAdapter != null && track != null) {
                fileListAdapter.setPlayingHref(track.getHref());
            }
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
            // 用户正在拖动时不覆盖进度，否则手柄会被每秒回调拽回原位
            if (!isSeeking) {
                playerProgressBar.setProgress(position);
            }
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
        } else if (id == R.id.menu_upload_log) {
            uploadLogManually();
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
        if (rlog != null) rlog.i(TAG, "<<< onDestroy");


        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        
        if (musicPlayer != null) {
            musicPlayer.removePlaybackListener(this);
        }
    }

    /** 手动上报日志（菜单触发），并告知本地路径便于排查 */
    private void uploadLogManually() {
        if (rlog == null) {
            Toast.makeText(this, "日志组件未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        rlog.i(TAG, "用户手动触发日志上报");
        rlog.upload("用户手动上报");
        Toast.makeText(this,
                "正在上报日志…\n（约几秒后可在服务器查看）",
                Toast.LENGTH_LONG).show();
    }

    /** Android 13(T) 及以上申请通知权限；低版本无需申请 */
    private void requestNotificationPermissionIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    if (rlog != null) rlog.i(TAG, "申请通知权限（Android 13+）");
                    notificationPermLauncher.launch(
                            android.Manifest.permission.POST_NOTIFICATIONS);
                }
            }
        } catch (Exception e) {
            if (rlog != null) rlog.w(TAG, "通知权限申请失败: " + e.getMessage());
        }
    }

    // ---- Activity 状态保存与恢复 ----
    //
    // 必要性：MainActivity 在后台可能被系统回收（日志实测：onStop 后 1-2 秒
    // 就重建，且没有 onDestroy）。进程存活但 Activity 重建时，
    // currentPath 会退回 "/"，用户看到的就是"切回前台回到首页"。
    // 把导航状态存进 savedInstanceState 即可原位恢复。

    private static final String STATE_CURRENT_PATH = "state_current_path";
    private static final String STATE_PATH_STACK = "state_path_stack";
    private static final String STATE_OFFLINE = "state_offline_mode";
    private static final String STATE_SCROLL = "state_scroll_pos";

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (rlog != null) rlog.i(TAG, "onSaveInstanceState: path=" + currentPath);
        outState.putString(STATE_CURRENT_PATH, currentPath);
        outState.putStringArrayList(STATE_PATH_STACK, new ArrayList<>(pathStack));
        outState.putBoolean(STATE_OFFLINE, isOfflineMode);
        if (recyclerView != null && recyclerView.getLayoutManager() != null) {
            int pos = ((LinearLayoutManager) recyclerView.getLayoutManager())
                    .findFirstVisibleItemPosition();
            outState.putInt(STATE_SCROLL, pos);
        }
    }

    /** 从 savedInstanceState 恢复导航状态；返回是否恢复了非根路径 */
    private boolean restoreNavState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return false;

        String savedPath = savedInstanceState.getString(STATE_CURRENT_PATH, "/");
        ArrayList<String> savedStack =
                savedInstanceState.getStringArrayList(STATE_PATH_STACK);
        boolean savedOffline = savedInstanceState.getBoolean(STATE_OFFLINE, false);

        pathStack.clear();
        if (savedStack != null) pathStack.addAll(savedStack);
        isOfflineMode = savedOffline;

        if (savedPath != null && !savedPath.isEmpty() && !"/".equals(savedPath)) {
            currentPath = savedPath;
            if (rlog != null) {
                rlog.i(TAG, "已恢复导航状态: path=" + currentPath
                        + " stackSize=" + pathStack.size()
                        + " offline=" + isOfflineMode);
            }
            return true;
        }
        return false;
    }

    /** 把内容滚动到上次位置（恢复时调用，避免跳到列表顶部） */
    private void restoreScrollPos(Bundle savedInstanceState) {
        if (savedInstanceState == null || recyclerView == null) return;
        final int pos = savedInstanceState.getInt(STATE_SCROLL, -1);
        if (pos > 0) {
            recyclerView.post(() -> {
                try {
                    recyclerView.scrollToPosition(pos);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // singleTask 模式下复用实例时会走这里，而不是重新 onCreate。
        // 此时导航状态与播放状态都是现成的，只需重新对齐一次 UI。
        if (rlog != null) rlog.i(TAG, "onNewIntent（复用已有实例）");
        syncUiWithPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rlog != null) rlog.i(TAG, ">>> onResume（回到前台）");

        // 回到前台时检查网络是否已恢复。
        // 之前 isOfflineMode 是单向开关，联网后没人复位，导致永远停在
        // 离线模式；这里在每次回到前台时补一次判断并自动切回在线。
        boolean wasOffline = isOfflineMode;
        refreshOfflineFlag();
        if (wasOffline && !isOfflineMode) {
            if (rlog != null) rlog.i(TAG, "回到前台检测到网络恢复，重新加载在线数据");
            Toast.makeText(this, "网络已恢复", Toast.LENGTH_SHORT).show();
            loadCurrentPath();
        }

        // 与 MusicPlayer 单例同步播放状态。
        // Activity 被系统回收重建后（进程仍存活），播放可能一直在进行 ——
        // 此时必须把迷你播放器的标题、按钮、进度条重新对齐到真实状态，
        // 否则会出现"音乐在响但界面显示未播放"。
        syncUiWithPlayer();
    }

    /** 把 UI 状态对齐到 MusicPlayer 单例的真实状态 */
    private void syncUiWithPlayer() {
        try {
            if (musicPlayer == null) return;
            WebDAVFile current = musicPlayer.getCurrentTrack();
            if (current != null) {
                miniPlayerLayout.setVisibility(View.VISIBLE);
                if (playerTitleTextView != null) {
                    playerTitleTextView.setText(current.getDisplayName());
                }
                if (fileListAdapter != null) {
                    fileListAdapter.setPlayingHref(current.getHref());
                }
            } else {
                if (miniPlayerLayout != null) {
                    miniPlayerLayout.setVisibility(View.GONE);
                }
                if (fileListAdapter != null) {
                    fileListAdapter.setPlayingHref(null);
                }
            }
            updatePlayerUI();
        } catch (Exception e) {
            if (rlog != null) rlog.w(TAG, "同步播放状态失败: " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (rlog != null) rlog.i(TAG, "<<< onPause（离开前台）");
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (rlog != null) {
            rlog.i(TAG, "<<< onStop（不可见，锁屏/切后台）");
            // 到后台时立刻上报一次，确保即便随后被系统杀死也能拿到现场日志
            rlog.upload("onStop 自动上报");
        }
    }

}

