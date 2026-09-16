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
    /** 播放时间文本：当前进度 / 总时长 */
    private TextView playerCurrentTimeText;
    private TextView playerTotalTimeText;
    
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
    /**
     * 当前正在浏览的服务器。
     *
     * null = 停留在「根目录」，也就是服务器列表本身。
     * 非 null = 已进入某个服务器的目录树，currentPath 是它在【该服务器内】
     * 的相对路径（不含服务器前缀）。
     *
     * 这是「服务器当根目录」的核心状态：服务器本身就是第一层目录，
     * 因此不需要「活动服务器」这种全局概念，也不会出现
     * 「切了服务器但界面还在用旧地址」的两套状态。
     */
    private ServerProfile currentServer;
    private boolean isOfflineMode = false;
    private boolean isSeeking = false;   // 用户正在拖动进度条时，暂停自动刷新

    /**
     * 目录加载请求序号。
     *
     * 解决的问题：目录列表有多个异步渲染路径（快照、网络、离线列举、
     * 失败回退），它们都通过 setFiles() 更新同一个适配器。若两个请求
     * 交叠（例如快速"进目录→返回"），后完成的会覆盖先完成的 ——
     * 表现为列表内容闪烁、条目时有时无。
     *
     * 用法：发起加载时 ++loadToken 并记下局部值，异步回调里先比对
     * 是否仍等于当前 loadToken，不等就说明已有更新的请求，直接丢弃。
     */
    private int loadToken = 0;

    // ---- 目录内存缓存 ----
    //
    // 目的：大幅减少对服务器的请求。
    // 用户"进目录 → 返回 → 再进另一个目录"是最高频的操作序列，
    // 其中"返回上级"所需的数据刚刚才请求过，完全没必要重新拉取。
    //
    // 策略（stale-while-revalidate 的内存版）：
    //   · 命中且未过期（TTL 内） → 直接渲染，一个请求都不发
    //   · 命中但已过期           → 先渲染，再后台静默刷新
    //   · 未命中                 → 走原有"磁盘快照优先 + 网络请求"流程
    //
    // 为什么需要 TTL 而不是永久缓存：长时间停留后云盘内容可能已变化
    // （尤其本应用支持上传/删除），超过 TTL 就静默校准一次。
    private static final long DIR_CACHE_TTL_MS = 5 * 60 * 1000L;   // 5 分钟

    /** 路径 → 该目录的文件列表 */
    private final Map<String, List<WebDAVFile>> dirCache = new HashMap<>();
    /** 路径 → 写入缓存的时间戳 */
    private final Map<String, Long> dirCacheTime = new HashMap<>();

    /** 写入内存缓存 */
    private void putDirCache(String path, List<WebDAVFile> files) {
        if (path == null || files == null || files.isEmpty()) return;
        dirCache.put(path, new ArrayList<>(files));
        dirCacheTime.put(path, System.currentTimeMillis());
        if (rlog != null) rlog.i(TAG, "[内存缓存] 写入 " + path + " (" + files.size() + " 项)");
    }

    /** 读取内存缓存；未命中返回 null */
    private List<WebDAVFile> getDirCache(String path) {
        List<WebDAVFile> cached = dirCache.get(path);
        if (cached == null || cached.isEmpty()) return null;
        return new ArrayList<>(cached);
    }

    /** 缓存是否仍然新鲜（TTL 内） */
    private boolean isDirCacheFresh(String path) {
        Long t = dirCacheTime.get(path);
        if (t == null) return false;
        return (System.currentTimeMillis() - t) < DIR_CACHE_TTL_MS;
    }

    /** 让某目录的缓存失效（上传/删除后调用） */
    private void invalidateDirCache(String path) {
        if (path == null) return;
        dirCache.remove(path);
        dirCacheTime.remove(path);
        if (rlog != null) rlog.i(TAG, "[内存缓存] 失效 " + path);
    }

    /** 判断异常是否代表服务端限流（429），用于给用户明确提示 */
    private boolean isRateLimitError(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        String s = msg.toLowerCase();
        return s.contains("429") || s.contains("too many") || s.contains("rate");
    }

    // ---- 上传相关 ----
    /** 系统文件选择器（SAF），可多选；无需存储权限 */
    private androidx.activity.result.ActivityResultLauncher<String[]> uploadPicker;
    /** Android 13+ 通知权限申请 */
    private androidx.activity.result.ActivityResultLauncher<String> notificationPermLauncher;
    /** 服务器管理页的回传：用户可能点了「进入某台服务器」 */
    private androidx.activity.result.ActivityResultLauncher<Intent> serverManageLauncher;
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

        // 服务器管理页可能带回「进入某台服务器」的指令
        serverManageLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts
                        .StartActivityForResult(),
                result -> {
                    Intent data = (result == null) ? null : result.getData();
                    String enterId = (data == null) ? null
                            : data.getStringExtra(ServerManageActivity.EXTRA_ENTER_SERVER_ID);
                    if (enterId != null) {
                        ServerProfile target = findServerById(enterId);
                        if (target != null) {
                            enterServer(target);
                            return;
                        }
                    }
                    // 没带「进入」指令：只刷新清单（可能新增/删除/改名了）
                    syncServerListState();
                });

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

        // 恢复滚动位置。必须在【数据灌入适配器之后】执行 —— 这里延迟
        // 300ms 作为兜底，且 restoreScrollPos 内部还会校验项数是否足够。
        // 只在 Activity 重建（savedInstanceState 非空）时才有意义。
        if (savedInstanceState != null) {
            mainHandler.postDelayed(() -> restoreScrollPos(savedInstanceState), 300);
        }

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
        playerCurrentTimeText = findViewById(R.id.playerCurrentTimeText);
        playerTotalTimeText = findViewById(R.id.playerTotalTimeText);

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
            toast("点击了播放器", Toast.LENGTH_SHORT);
        });
        
        // 上传按钮
        findViewById(R.id.fabUpload).setOnClickListener(v -> showUploadDialog());
        
        // 返回按钮
        findViewById(R.id.backButton).setOnClickListener(v -> navigateUp());
    }
    
    private void initManagers() {
        // 【多服务器 / 服务器即根目录】不再需要「全局活动服务器」——
        // 每条曲目自带 serverId，取流时按它找服务器，所以这里
        // 不再把某一台服务器 configure 到全局单例上。
        rlog = RemoteLogger.getInstance(this);
        cacheManager = LocalCacheManager.getInstance(this);

        // 一台都没有 → 引导去添加
        List<ServerProfile> servers = ServerStore.list(this);
        if (servers.isEmpty()) {
            toast("请先添加 WebDAV 服务器", Toast.LENGTH_LONG);
            serverManageLauncher.launch(new Intent(this, ServerManageActivity.class));
            finish();
            return;
        }
        rlog.i(TAG, "MainActivity initManagers，共 " + servers.size() + " 台服务器");

        // 兼容旧代码：webDAVClient 保留指向「第一台」的实例，
        // 但所有按目录/按曲目的请求都走各自条目的服务器（见 clientFor）
        webDAVClient = WebDAVClient.forServer(servers.get(0));

        musicPlayer = MusicPlayer.getInstance(this);
        musicPlayer.addPlaybackListener(this);
    }

    /**
     * 构造缓存键：服务器 id + 目录相对路径。
     *
     * 目录的内存缓存与磁盘快照都用它做键，于是「两台服务器有同名目录」
     * 这件事天然不再冲突 —— 切换服务器不必再清空缓存（旧实现要清）。
     * 没有服务器（根目录）时退回纯路径。
     */
    private String cacheKeyOf(ServerProfile server, String path) {
        String p = (path == null || path.isEmpty()) ? "/" : path;
        if (server == null || server.getId() == null || server.getId().isEmpty()) {
            return p;
        }
        return server.getId() + ":" + p;
    }

    /** 取某台服务器的客户端（目录浏览、上传等按服务器发请求） */
    private WebDAVClient clientFor(ServerProfile profile) {
        // profile 为 null 时（例如旧快照里的条目没有服务器归属）
        // 退回已初始化的那台，避免 NPE；调用方不应依赖此时的正确性
        if (profile == null) {
            if (webDAVClient == null) {
                List<ServerProfile> all = ServerStore.list(this);
                webDAVClient = WebDAVClient.forServer(all.isEmpty() ? null : all.get(0));
            }
            return webDAVClient;
        }
        return WebDAVClient.forServer(profile);
    }

    /** 组装 Basic 认证头（WebDAVClient 与播放器流式请求都要用同一份） */
    private Map<String, String> buildAuthHeaders(String username, String password) {
        Map<String, String> authHeaders = new HashMap<>();
        String credentials = username + ":" + password;
        authHeaders.put("Authorization", "Basic " + android.util.Base64.encodeToString(
                credentials.getBytes(), android.util.Base64.NO_WRAP));
        return authHeaders;
    }
    
    private void initPlayer() {
        updatePlayerUI();
    }
    
    /** 进入某台服务器：清空导航状态，从它的根目录开始浏览 */
    private void enterServer(ServerProfile profile) {
        if (profile == null) return;
        if (!profile.isComplete()) {
            toast(getString(R.string.msg_server_incomplete));
            return;
        }
        if (rlog != null) {
            rlog.i(TAG, "进入服务器: " + profile.getDisplayName() + " (" + profile.getUrl() + ")");
        }
        currentServer = profile;
        // 换服务器时必须重置导航状态与请求序号，避免旧服务器的
        // 异步回调回来把列表覆盖成另一台的内容
        pathStack.clear();
        currentPath = "/";
        loadToken++;
        if (fileListAdapter != null) {
            fileListAdapter.setFiles(new ArrayList<>());
        }
        loadCurrentPath();
    }

    /** 回到根目录（服务器列表） */
    private void exitToServerRoot() {
        currentServer = null;
        pathStack.clear();
        currentPath = "/";
        loadToken++;        // 作废在途请求
        loadCurrentPath();
    }

    /**
     * 根目录：列出全部已保存的服务器（每台当做一个「文件夹」）。
     * 不发起任何网络请求，纯本地渲染。
     */
    private void showServerRoot() {
        updateNetworkStatus();
        List<ServerProfile> servers = ServerStore.list(this);

        List<WebDAVFile> rows = new ArrayList<>();
        for (ServerProfile p : servers) {
            WebDAVFile f = new WebDAVFile();
            // 用「服务器」这个虚拟容器承载，isCollection=true 让它
            // 复用文件列表的文件夹渲染与点击进入逻辑
            f.setDisplayName(p.getDisplayName());
            f.setCollection(true);
            f.setServerId(p.getId());
            f.setServerName(p.getDisplayName());
            f.setRelativePath("");
            // 用别名做二次行，显示账号便于区分同址不同账号的两台
            rows.add(f);
        }

        fileListAdapter.setFiles(rows);
        fileListAdapter.setOfflineMode(false);
        updatePathDisplay();
        swipeRefreshLayout.setRefreshing(false);

        if (rlog != null) {
            rlog.i(TAG, "[根目录] 列出服务器 " + rows.size() + " 台");
        }
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
        // 【根目录 = 服务器列表】
        // 这是「服务器即根目录」的入口：根目录不请求任何 WebDAV，
        // 直接把已保存的服务器当作「文件夹」列出来，点进去才是那台
        // 服务器的目录树。因此这里不走缓存/快照/网络那套流程。
        if (currentServer == null) {
            showServerRoot();
            return;
        }

        if (!webDAVClient.isConfigured()) {
            // 如果未配置，跳转到配置界面
            serverManageLauncher.launch(new Intent(this, ServerManageActivity.class));
            finish();
            return;
        }

        // 网络恢复检测：isOfflineMode 之前是单向开关（只会被置 true），
        // 导致联网后仍卡在离线模式。这里在每次加载前先根据真实网络状态
        // 复位 —— 有网络且用户未手动锁定离线，就回到在线模式。
        refreshOfflineFlag();

        updateNetworkStatus();
        updatePathDisplay();

        // 本次加载的序号：所有异步回调都必须携带它回来比对，
        // 否则交叠的旧请求会覆盖新请求的结果（列表闪烁、条目时有时无）
        final int token = ++loadToken;
        final String requestPath = currentPath;
        // 缓存键 = 服务器 id + 目录相对路径：两台服务器同名目录互不干扰，
        // 因此切换服务器不再需要清空缓存
        final String cacheKey = cacheKeyOf(currentServer, requestPath);

        // 返回时先清空列表，避免把上一个目录的文件短暂显示成当前目录的内容。
        // 若下面命中快照会立刻重新填充。
        fileListAdapter.setFiles(new ArrayList<>());

        // 切换目录后把列表滚回顶部。
        // 否则 RecyclerView 会保留上一个目录的滚动偏移，导致新目录的
        // 前几项被顶出视野 —— 用户会以为"某个目录消失了"
        // （实测：从子目录返回后，列表停在中间，前 7 项不可见）。
        if (recyclerView != null) {
            recyclerView.scrollToPosition(0);
        }

        final boolean online = !isOfflineMode && NetworkUtils.isNetworkConnected(this);

        // 离线：显示完整目录（快照为主，本地文件兜底）
        if (!online) {
            showLocalDownloads(token, requestPath);
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        // 【内存缓存优先】这是减少请求的关键路径。
        // "返回上级"所需的数据刚刚才请求过，命中时一个请求都不发。
        List<WebDAVFile> mem = getDirCache(cacheKey);
        if (mem != null) {
            updateFileDownloadStates(mem);
            fileListAdapter.setFiles(mem);
            fileListAdapter.setOfflineMode(false);

            if (isDirCacheFresh(cacheKey)) {
                // 5 分钟内看过的目录：完全静默，不发任何请求
                if (rlog != null) {
                    rlog.i(TAG, "[内存缓存] 命中且新鲜，跳过网络请求: " + cacheKey
                            + " (" + mem.size() + " 项)");
                }
                swipeRefreshLayout.setRefreshing(false);
                return;
            }

            // 缓存已过期：先用旧数据渲染，再后台静默校准
            if (rlog != null) {
                rlog.i(TAG, "[内存缓存] 命中但过期，后台静默刷新: " + cacheKey);
            }
            loadFromServer(false, token, requestPath);
            return;
        }

        // 未命中内存缓存：走原有逻辑（磁盘快照先渲染 + 网络请求）
        boolean rendered = false;
        List<WebDAVFile> cached = cacheManager.loadSnapshot(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            updateFileDownloadStates(cached);
            fileListAdapter.setFiles(cached);
            fileListAdapter.setOfflineMode(false);
            rendered = true;
            if (rlog != null) {
                rlog.i(TAG, "[在线] 命中磁盘快照 " + cached.size() + " 项: " + cacheKey);
            }
        } else if (rlog != null) {
            rlog.i(TAG, "[在线] 无快照: " + cacheKey);
        }

        loadFromServer(!rendered, token, requestPath);
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
    private void showLocalDownloads(final int token, final String requestPath) {
        executorService.execute(() -> {
            if (rlog != null) rlog.i(TAG, "[离线] 开始列举: " + requestPath);

            // 主数据源：该目录的云端快照（完整列表，含未下载项）
            List<WebDAVFile> listed = cacheManager.loadSnapshot(requestPath);
            boolean fromSnapshot = listed != null && !listed.isEmpty();
            if (rlog != null) {
                rlog.i(TAG, "[离线] 快照" + (fromSnapshot ? "命中 " + listed.size() + " 项"
                        : "缺失") + ": " + requestPath);
            }

            List<WebDAVFile> items;
            if (fromSnapshot) {
                items = listed;
            } else {
                // 快照缺失（该目录从未联网浏览过，或缓存被清理）：
                // 退化为"本地已下载 + 由此推导的目录"
                items = cacheManager.listLocalTree(requestPath);
            }

            // 关键：与本地已下载内容做并集。
            // 快照可能早于某次下载（下载后没再进过该目录），
            // 只信快照会让"刚下载的歌"消失；只信本地则会让
            // "未下载的歌"消失。两者并集才能既完整又准确。
            items = mergeWithLocal(requestPath, items);

            if (!items.isEmpty()) {
                updateFileDownloadStates(items);
            }

            final boolean useSnapshot = fromSnapshot;
            final List<WebDAVFile> result = items;

            mainHandler.post(() -> {
                // 过期结果直接丢弃（快速"进目录→返回"时最关键的保护）
                if (token != loadToken || !requestPath.equals(currentPath)) {
                    Log.d(TAG, "丢弃过期的离线列表: " + requestPath);
                    return;
                }
                fileListAdapter.setOfflineMode(true);

                if (result.isEmpty()) {
                    fileListAdapter.setFiles(new ArrayList<>());
                    toast("该目录无离线数据\n（联网浏览过的目录会自动缓存列表）",
                            Toast.LENGTH_LONG);
                } else {
                    fileListAdapter.setFiles(result);
                    if (!useSnapshot) {
                        toast("离线模式（仅本地已下载内容）", Toast.LENGTH_SHORT);
                    } else {
                        toast("离线模式", Toast.LENGTH_SHORT);
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
    /**
     * 从服务器加载。
     *
     * @param token       发起时的加载序号；回调中若已过期则整体丢弃
     * @param requestPath 发起时的路径；与 token 一起用于丢弃过期结果
     */
    private void loadFromServer(boolean showLoading, final int token, final String requestPath) {
        if (showLoading) {
            showLoading("正在加载...");
        }

        // 【多服务器】请求发往【当前浏览的服务器】，并把它捕获成 final，
        // 供回调里写快照/内存缓存时构造带服务器前缀的键。
        // 若在这里直接用 currentServer，用户在请求飞行途中切了服务器，
        // 回来时就会把 A 的内容写进 B 的缓存 —— 必须捕获当时的那个。
        final ServerProfile serverAtRequest = currentServer;
        final String cacheKey = cacheKeyOf(serverAtRequest, requestPath);

        executorService.execute(() -> {
            clientFor(serverAtRequest).listFolder(requestPath,
                    new WebDAVClient.WebDAVCallback<List<WebDAVFile>>() {
                @Override
                public void onSuccess(List<WebDAVFile> files) {
                    // 已有更新的加载请求 → 丢弃本次结果。
                    // 必须同时校验 token 与路径：仅有 token 不足以排除
                    // "同一 token 但路径已变"的情况。
                    if (token != loadToken || !requestPath.equals(currentPath)) {
                        Log.d(TAG, "丢弃过期的加载结果: " + requestPath);
                        return;
                    }
                    // 更新文件的下载状态
                    updateFileDownloadStates(files);

                    // 只有拿到内容才覆盖快照 —— 避免一次空响应用空列表
                    // 把之前缓存好的目录数据抹掉。
                    //
                    // 必须用 requestPath 而非 currentPath：快照的 key 表示
                    // "这份数据属于哪个目录"。若用 currentPath，当用户已切到
                    // 别的目录、而这个旧请求恰好通过校验（边界情况）时，
                    // 会把 A 目录的内容写进 B 目录的快照，导致内容错乱与闪烁。
                    //
                    // cacheKey 里含服务器 id，两台服务器同名目录互不覆盖。
                    if (files != null && !files.isEmpty()) {
                        cacheManager.saveSnapshot(cacheKey, files);
                        // 同步写入内存缓存，后续"返回上级"可零请求命中
                        putDirCache(cacheKey, files);
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

                    // 过期请求的失败不处理，否则会把新目录的界面搞乱
                    if (token != loadToken || !requestPath.equals(currentPath)) {
                        Log.d(TAG, "丢弃过期的错误回调: " + requestPath);
                        return;
                    }

                    // 服务端限流（429）时给明确提示。
                    // 不做退避/自动重试：本应用的关键防护是"同目录 5 分钟
                    // 内存缓存"，它已把请求量降到很低；而 OpenList 的 429
                    // 实际由"认证失败次数"触发，密码正确时不会出现。
                    // 再加一层退避只会增加复杂度，还可能在误判时挡住正常请求。
                    if (isRateLimitError(e)) {
                        if (rlog != null) rlog.w(TAG, "服务端返回 429（限流）: " + requestPath);
                        toast("服务器繁忙，请稍后再试", Toast.LENGTH_SHORT);
                    }

                    mainHandler.post(() -> {
                        dismissLoading();
                        swipeRefreshLayout.setRefreshing(false);

                        // 已经有缓存内容在显示：静默失败即可，不打断浏览
                        if (fileListAdapter.getItemCount() > 0) {
                            toast("刷新失败，显示的是缓存数据", Toast.LENGTH_SHORT);
                            return;
                        }

                        // 没有内容可显示：退回离线模式，列出本地已下载歌曲
                        // （不再使用云端目录快照 —— 那些文件多数并未下载，
                        //   离线点开必然播放失败）
                        int localCount = cacheManager.getOfflineFileCount();
                        if (localCount > 0) {
                            toast("网络连接失败，切换到离线模式（本地已存 "
                                            + localCount + " 首）",
                                    Toast.LENGTH_SHORT);
                            isOfflineMode = true;
                            updateNetworkStatus();
                            showLocalDownloads(token, requestPath);
                        } else {
                            toast("加载失败: " + e.getMessage(), Toast.LENGTH_LONG);
                        }
                    });
                }
            });
        });
    }
    
    private void updateFileDownloadStates(List<WebDAVFile> files) {
        for (WebDAVFile file : files) {
            if (!file.isCollection()) {
                if (cacheManager.isDownloaded(file.getCacheKey())) {
                    file.setDownloadState(WebDAVFile.DownloadState.DOWNLOADED);
                    file.setLocalPath(cacheManager.getLocalFile(file.getCacheKey()).getAbsolutePath());
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
        // 根目录显示「服务器」，进去后显示「别名: /路径」，让用户随时知道
        // 自己正处在哪台服务器上（多服务器下这个信息很关键）
        if (currentServer == null) {
            pathTextView.setText(getString(R.string.label_server_root));
        } else {
            pathTextView.setText(currentServer.getDisplayName() + ": " + currentPath);
        }

        // 更新返回按钮状态：根目录时隐藏
        View backButton = findViewById(R.id.backButton);
        backButton.setVisibility(currentServer == null ? View.GONE : View.VISIBLE);
    }

    /** 按 id 找服务器配置 */
    private ServerProfile findServerById(String serverId) {
        if (serverId == null) return null;
        for (ServerProfile p : ServerStore.list(this)) {
            if (serverId.equals(p.getId())) return p;
        }
        return null;
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
            toast("网络未连接", Toast.LENGTH_SHORT);
            return;
        }

        boolean hasContent = fileListAdapter.getItemCount() > 0;
        // 【根目录 = 服务器列表】根目录没有远端内容可刷，只是重新读一遍
        // 本地服务器清单（可能刚在管理页加/删/改过）
        if (currentServer == null) {
            showServerRoot();
            toast("已刷新服务器列表", Toast.LENGTH_SHORT);
            return;
        }
        // 刷新也走新的 token：如果用户在刷新过程中切换了目录，
        // 刷新的结果不应覆盖新目录的内容
        final int token = ++loadToken;
        loadFromServer(!hasContent, token, currentPath);
    }
    
    @Override
    public void onItemClick(WebDAVFile file) {
        if (file.isCollection()) {
            // 进入文件夹
            navigateToFolder(file);
        } else if (file.isAudio()) {
            // 离线模式下，未下载的歌曲无法播放 —— 提前给明确提示，
            // 而不是让 MediaPlayer 抛一个含糊的错误
            if (isOfflineMode && !cacheManager.isDownloaded(file.getCacheKey())) {
                toast("离线模式：该歌曲未下载，无法播放\n联网后可播放或先下载",
                        Toast.LENGTH_LONG);
                return;
            }
            // 播放音频文件
            playAudioFile(file);
        } else {
            toast("暂不支持预览此类文件: " + file.getDisplayName(), Toast.LENGTH_SHORT);
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
        // 【服务器即根目录】在根目录点一个「文件夹」，就是进入那台服务器
        if (currentServer == null) {
            ServerProfile target = findServerById(folder.getServerId());
            if (target == null) {
                toast("该服务器已被删除，请先在服务器管理里重新添加");
                loadCurrentPath();
                return;
            }
            enterServer(target);
            return;
        }

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

        if (rlog != null) {
            rlog.i(TAG, "进入目录: " + folder.getDisplayName()
                    + " | relativePath=" + rel
                    + " | href=" + folder.getHref()
                    + " | isCollection=" + folder.isCollection()
                    + " | → currentPath=" + currentPath
                    + " | stack=" + pathStack);
        }

        // 加载新路径
        loadCurrentPath();
    }
    
    private void navigateUp() {
        if (rlog != null) {
            rlog.i(TAG, "返回上级: 当前服务器=" + (currentServer == null ? "根目录" : currentServer.getDisplayName())
                    + " 当前路径=" + currentPath + " stack=" + pathStack);
        }

        // 【服务器即根目录】已在服务器根目录时，返回上级 = 回到服务器列表
        if (currentServer != null && pathStack.isEmpty()) {
            exitToServerRoot();
            return;
        }

        if (pathStack.isEmpty()) {
            if (!currentPath.equals("/")) {
                // 回到根目录
                currentPath = "/";
                loadCurrentPath();
            }
        } else {
            // 从栈中取出上一个路径
            currentPath = pathStack.remove(pathStack.size() - 1);
            if (rlog != null) rlog.i(TAG, "返回后 currentPath=" + currentPath);
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
            toast("网络不可用，无法下载", Toast.LENGTH_SHORT);
            return;
        }
        
        // 更新下载状态
        file.setDownloadState(WebDAVFile.DownloadState.DOWNLOADING);
        fileListAdapter.updateFile(file);
        
        // 获取本地存储路径。
        // 【多服务器】用 getCacheKey()（含服务器 id）命名，两台服务器
        // 存在同名路径时不会互相覆盖本地文件。
        File destFile = cacheManager.getDownloadDestination(file.getCacheKey());

        showLoading("正在下载: " + file.getDisplayName());

        // 【多服务器】下载请求发往该条目所属的服务器
        final WebDAVClient dlClient = clientFor(findServerById(file.getServerId()));

        executorService.execute(() -> {
            dlClient.download(file, destFile, new WebDAVClient.ProgressCallback() {
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
                        cacheManager.markFileDownloaded(file.getCacheKey(), destFile);
                        // 同时写入离线索引：离线模式需要按目录列举已下载歌曲，
                        // 仅靠 downloadMap 无法还原目录结构与显示名
                        cacheManager.indexOfflineFile(file.getHref(),
                                file.getDisplayName(), destFile.length());
                        file.setDownloadState(WebDAVFile.DownloadState.DOWNLOADED);
                        file.setLocalPath(destFile.getAbsolutePath());
                        fileListAdapter.updateFile(file);
                        
                        toast("下载完成: " + file.getDisplayName(), Toast.LENGTH_SHORT);
                    });
                }
                
                @Override
                public void onError(Exception e) {
                    mainHandler.post(() -> {
                        dismissLoading();
                        
                        file.setDownloadState(WebDAVFile.DownloadState.NOT_DOWNLOADED);
                        fileListAdapter.updateFile(file);
                        
                        toast("下载失败: " + e.getMessage(), Toast.LENGTH_LONG);
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
                    
                    toast("已删除本地文件: " + file.getDisplayName(), Toast.LENGTH_SHORT);
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
        toast("重命名功能待实现", Toast.LENGTH_SHORT);
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
                toast("本地文件不存在", Toast.LENGTH_SHORT);
            }
        } else {
            toast("请先下载文件", Toast.LENGTH_SHORT);
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
            toast("离线模式下无法上传，请先连接网络", Toast.LENGTH_SHORT);
            return;
        }
        if (currentServer == null) {
            toast("请先进入某个服务器再上传", Toast.LENGTH_SHORT);
            return;
        }
        if (!currentServer.isComplete()) {
            toast("当前服务器配置不完整，请先在服务器管理里补全", Toast.LENGTH_SHORT);
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

                // 【多服务器】上传发往当前浏览的服务器
                final WebDAVClient upClient = clientFor(currentServer);
                upClient.upload(toUpload, remotePath,
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
                mainHandler.post(() -> toast("上传失败: " + displayName + "\n" + msg,
                        Toast.LENGTH_LONG));
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
        toast("上传完成（" + uploadDone + "/" + uploadTotal + "）",
                Toast.LENGTH_SHORT);
        // 新文件已写入，当前目录的内存缓存必须失效，否则会继续显示旧列表
        invalidateDirCache(currentPath);
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
                    toast("已取消剩余上传", Toast.LENGTH_SHORT);
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
            int dur = musicPlayer.getDuration();
            int cur = musicPlayer.getCurrentPosition();
            playerProgressBar.setMax(dur);
            if (!isSeeking) {
                playerProgressBar.setProgress(cur);
            }
            updateTimeTexts(cur, dur);
            
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
            // track 可能为 null —— 切换服务器时 clearForServerSwitch() 会发一次
            // 「当前曲目为空」的通知，用来把迷你播放器/标题清干净。
            if (track == null) {
                if (playerTitleTextView != null) {
                    playerTitleTextView.setText("");
                }
                if (fileListAdapter != null) {
                    fileListAdapter.setPlayingHref(null);
                }
                return;
            }
            playerTitleTextView.setText(track.getDisplayName());
            // 高亮列表中正在播放的条目
            if (fileListAdapter != null) {
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
            updateTimeTexts(position, duration);
        });
    }

    /**
     * 刷新"当前时间 / 总时长"文本。
     *
     * @param positionMs 当前播放位置（毫秒）；<=0 或时长未知时显示 00:00
     * @param durationMs 总时长（毫秒）
     */
    private void updateTimeTexts(int positionMs, int durationMs) {
        try {
            if (playerCurrentTimeText != null) {
                playerCurrentTimeText.setText(formatDuration(
                        isSeeking ? playerProgressBar.getProgress() : positionMs));
            }
            if (playerTotalTimeText != null) {
                playerTotalTimeText.setText(formatDuration(durationMs));
            }
        } catch (Exception ignored) {
        }
    }

    /** 毫秒 → mm:ss（超过 1 小时用 h:mm:ss） */
    private String formatDuration(int ms) {
        if (ms <= 0) return "00:00";
        int totalSec = ms / 1000;
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }
    
    @Override
    public void onError(String error) {
        mainHandler.post(() -> {
            // MusicPlayer 传来的 error 已含"播放错误: "前缀，这里不再重复拼接
            toast(error, Toast.LENGTH_LONG);
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
        
        if (id == R.id.menu_locate_current) {
            // 定位到当前正在播放的歌曲。
            // 停留在服务器根目录时该功能无意义（那里列的是服务器不是曲目），
            // 直接给出提示而不是静默什么都不做。
            if (currentServer == null) {
                toast("请先进入某个服务器再定位", Toast.LENGTH_SHORT);
                return true;
            }
            locateCurrentTrack();
            return true;
        } else if (id == R.id.menu_manage_servers) {
            // 「WebDAV 服务器管理」：新增/删除/编辑服务器（别名唯一）
            serverManageLauncher.launch(new Intent(this, ServerManageActivity.class));
            return true;
        } else if (id == R.id.menu_settings) {
            // 改为进入「WebDAV 服务器管理」页（新增/删除/切换服务器）。
            // 旧行为是跳 ServerConfigActivity，而它在已配置时会立刻 finish 回主界面，
            // 等于菜单点了没反应；服务器管理才是这里真正该去的地方。
            serverManageLauncher.launch(new Intent(this, ServerManageActivity.class));
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
                    toast("缓存已清空", Toast.LENGTH_SHORT);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void showAboutDialog() {
        String version = getAppVersionName();
        String msg = "DavMusic v" + version
                + "\n\nWebDAV 音乐播放器"
                + "\n\n功能："
                + "\n· 浏览 WebDAV 上的音乐目录"
                + "\n· 在线播放 / 下载后离线播放"
                + "\n· 后台与锁屏连续播放（前台服务）"
                + "\n· 上传文件到当前目录"
                + "\n\n构建信息："
                + "\n· 版本号 " + version
                + "（versionCode " + getAppVersionCode() + "）"
                + "\n· 提交 " + BuildConfig.GIT_COMMIT;
        new AlertDialog.Builder(this)
                .setTitle("关于 DavMusic")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }

    /** 读取 APK 中声明的版本名，避免手写版本号与实际安装包不一致 */
    private String getAppVersionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 读取 APK 中声明的 versionCode */
    private long getAppVersionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
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
            toast("日志组件未初始化", Toast.LENGTH_SHORT);
            return;
        }
        rlog.i(TAG, "用户手动触发日志上报");
        rlog.upload("用户手动上报");
        toast("正在上报日志…\n（约几秒后可在服务器查看）",
                Toast.LENGTH_LONG);
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

    /**
     * 把"本地已下载内容"并入给定列表。
     *
     * 为什么需要并集：快照与本地下载是两个独立演进的集合，各有一部分
     * 对方没有的数据 ——
     *   · 快照有"未下载的歌"（离线时只能展示、不能播）
     *   · 本地有"下载后没再进过该目录的歌"（快照里可能还没有）
     * 只取其一都会丢内容：只信快照→刚下载的歌消失；
     * 只信本地→未下载的歌消失。两者并集才完整。
     *
     * 合并规则：按 href 去重，已存在的保留（快照版本信息更全）。
     */
    private List<WebDAVFile> mergeWithLocal(String dirPath, List<WebDAVFile> fromSnapshot) {
        List<WebDAVFile> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        if (fromSnapshot != null) {
            for (WebDAVFile f : fromSnapshot) {
                if (f == null) continue;
                String key = f.getHref() == null ? f.getRelativePath() : f.getHref();
                if (key == null) continue;
                if (seen.add(key)) result.add(f);
            }
        }

        // 并入本地已下载（含由此推导的子目录）
        List<WebDAVFile> local = cacheManager.listLocalTree(dirPath);
        for (WebDAVFile f : local) {
            if (f == null) continue;
            String key = f.getHref() == null ? f.getRelativePath() : f.getHref();
            if (key == null) continue;
            if (seen.add(key)) {
                result.add(f);
            }
        }

        return result;
    }

    /**
     * 把内容滚动到上次位置。
     *
     * 仅在 Activity 被系统回收后重建时使用（savedInstanceState 非空）。
     *
     * 关键约束：必须在【数据已经灌入适配器之后】才能 scrollToPosition。
     * 原实现放在 onCreate 里，此时列表还是空的 —— 滚动请求落空，
     * 等异步数据到达后 RecyclerView 行为不确定（可能保留偏移、
     * 也可能跳回顶部），表现为"条目时有时无"。
     *
     * 另外注意：目录切换（进入/返回）不应该滚动 —— 那会让用户
     * 误以为某个条目"消失"（实际只是被滚出视野）。只有 Activity
     * 重建这一种场景才需要恢复滚动位置。
     */
    private void restoreScrollPos(Bundle savedInstanceState) {
        if (savedInstanceState == null || recyclerView == null) return;
        final int pos = savedInstanceState.getInt(STATE_SCROLL, -1);
        if (pos <= 0) return;

        if (rlog != null) rlog.i(TAG, "恢复滚动位置: " + pos);

        recyclerView.post(() -> {
            try {
                if (fileListAdapter != null && fileListAdapter.getItemCount() > pos) {
                    recyclerView.scrollToPosition(pos);
                } else if (rlog != null) {
                    rlog.i(TAG, "跳过滚动恢复：列表尚未就绪或项数不足（"
                            + (fileListAdapter == null ? -1
                               : fileListAdapter.getItemCount()) + " ≤ " + pos + "）");
                }
            } catch (Exception ignored) {
            }
        });
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

        // 【多服务器 / 服务器即根目录】不再需要「回来检测活动服务器是否变了」：
        // 每台服务器就是根目录里的一个条目，进入哪台看 currentServer，
        // 缓存键也带服务器 id，所以不存在「切了服务器界面还在用旧地址」。
        // 此处只需处理：当前浏览的服务器被删掉了 / 列表被改过。
        syncServerListState();

        // 回到前台时检查网络是否已恢复。
        // 之前 isOfflineMode 是单向开关，联网后没人复位，导致永远停在
        // 离线模式；这里在每次回到前台时补一次判断并自动切回在线。
        boolean wasOffline = isOfflineMode;
        refreshOfflineFlag();
        if (wasOffline && !isOfflineMode) {
            if (rlog != null) rlog.i(TAG, "回到前台检测到网络恢复，重新加载在线数据");
            toast("网络已恢复", Toast.LENGTH_SHORT);
            loadCurrentPath();
        }

        // 与 MusicPlayer 单例同步播放状态。
        // Activity 被系统回收重建后（进程仍存活），播放可能一直在进行 ——
        // 此时必须把迷你播放器的标题、按钮、进度条重新对齐到真实状态，
        // 否则会出现"音乐在响但界面显示未播放"。
        syncUiWithPlayer();
    }

    // ==================== 新功能①：定位到当前歌曲 ====================

    /**
     * 「更多」菜单 → 定位到当前歌曲。
     *
     * 两种结果：
     *   · 当前目录里有正在播放的这首 → 列表滑动到它
     *   · 当前目录里没有（用户翻到别的目录了）→ toast 告知它在哪个目录
     */
    private void locateCurrentTrack() {
        WebDAVFile current = (musicPlayer != null) ? musicPlayer.getCurrentTrack() : null;
        if (current == null) {
            toast(getString(R.string.msg_no_track_playing));
            return;
        }

        List<WebDAVFile> files = (fileListAdapter != null) ? fileListAdapter.getFiles() : null;
        if (files == null || files.isEmpty()) {
            // 列表还没加载出来（刚进目录/正在请求）：给出明确原因，
            // 而不是静默什么都不做
            toast(getString(R.string.msg_list_not_ready));
            return;
        }

        int index = -1;
        for (int i = 0; i < files.size(); i++) {
            if (MusicPlayer.isSameTrack(files.get(i), current)) {
                index = i;
                break;
            }
        }

        if (index >= 0) {
            // 命中：滑动到该条目。
            // 用平滑滚动而不是 scrollToPosition —— 需求是「滑动到」，
            // 而且目录列表通常不长，动画能让用户看清它是从哪儿滑过去的。
            if (recyclerView != null) {
                recyclerView.smoothScrollToPosition(index);
            }
            if (rlog != null) {
                rlog.i(TAG, "定位到当前歌曲: " + current.getDisplayName()
                        + " @ " + currentPath + " index=" + index);
            }
            toast(getString(R.string.msg_located_track, current.getDisplayName()));
        } else {
            // 未命中：提示它所在的完整路径（含文件名 + 服务器别名）
            String full = trackFullPath(current);
            if (rlog != null) {
                rlog.i(TAG, "当前歌曲不在本目录(" + currentPath + ")，完整路径=" + full);
            }
            toast(getString(R.string.msg_track_in_other_dir, full), Toast.LENGTH_LONG);
        }
    }

    /**
     * 反查正在播放歌曲所在的「完整路径」——目录 + 文件名。
     *
     * 为什么要带文件名：同一目录下常有多首，只报目录用户还得自己找；
     * 直接给出「/cmcc/music/国语/男/其他/陈小春-0932.m4a」可以一眼定位。
     * 多服务器下再带上服务器别名，免得两台服务器路径相同时分不清。
     */
    private String trackFullPath(WebDAVFile track) {
        String dir = trackDirectoryOf(track);
        String name = track.getDisplayName();
        String path;
        if (name == null || name.isEmpty()) {
            path = dir;
        } else if (dir.endsWith("/")) {
            path = dir + name;
        } else {
            path = dir + "/" + name;
        }
        String serverName = track.getServerName();
        if (serverName != null && !serverName.isEmpty()) {
            return serverName + ": " + path;
        }
        return path;
    }

    /**
     * 反查正在播放歌曲所在的「显示目录」。
     *
     * relativePath 是去掉服务器根路径后的相对路径（例：cmcc/music/国语/男/其他/x.m4a），
     * 取父级再补前导 / 就与 currentPath 的表示法一致（见 navigateToFolder）。
     */
    private String trackDirectoryOf(WebDAVFile track) {
        String rel = track.getRelativePath();
        if (rel != null && !rel.isEmpty()) {
            String cleaned = rel;
            while (cleaned.startsWith("/")) {
                cleaned = cleaned.substring(1);
            }
            int slash = cleaned.lastIndexOf('/');
            if (slash > 0) {
                return "/" + cleaned.substring(0, slash);
            }
        }
        // 兜底：直接用 href 的父级
        String href = track.getHref();
        if (href != null) {
            int slash = href.lastIndexOf('/');
            if (slash > 0) {
                return href.substring(0, slash);
            }
        }
        return "/";
    }

    // ==================== 新功能②：多服务器（服务器即根目录） ====================

    /**
     * 从「服务器管理」页回来后，把主界面对齐到最新的服务器清单。
     *
     * 在「服务器即根目录」的模型下，这里【只需要处理两种情况】——
     * 旧实现在这里要重建数据源、换认证头、清空播放状态、清目录缓存、
     * 清快照，那五项在服务器成为根目录第一层之后全都不需要了：
     *   · 请求按条目自带的 serverId 发往对应服务器 → 无需「换数据源」
     *   · 播放队列里的条目自带服务器归属 → 无需清空播放状态
     *   · 缓存键含服务器 id → 两台服务器同名目录不会串，无需清缓存
     */
    private void syncServerListState() {
        if (musicPlayer == null || cacheManager == null) return;

        List<ServerProfile> servers = ServerStore.list(this);

        // 情况一：服务器被删光了 → 回管理页添加
        if (servers.isEmpty()) {
            if (rlog != null) rlog.w(TAG, "服务器已全部删除，回管理页");
            currentServer = null;
            showServerRoot();
            toast("服务器已全部删除，请重新添加", Toast.LENGTH_LONG);
            serverManageLauncher.launch(new Intent(this, ServerManageActivity.class));
            return;
        }

        // 情况二：正在浏览的那台被删掉了 → 退回根目录
        if (currentServer != null) {
            ServerProfile still = findServerById(currentServer.getId());
            if (still == null) {
                if (rlog != null) {
                    rlog.w(TAG, "正在浏览的服务器已被删除，退回根目录: " + currentServer.getDisplayName());
                }
                toast("正在浏览的服务器已被删除，已回到服务器列表", Toast.LENGTH_LONG);
                exitToServerRoot();
                return;
            }
        }

        // 情况三：在根目录 → 刷新列表（可能有新增/删除/改名）
        if (currentServer == null) {
            showServerRoot();
        }
    }

    /** 清空「目录内存缓存」（路径 → 文件列表）。仅在「清空缓存」时用 */
    private void clearDirCache() {
        try {
            dirCache.clear();
            dirCacheTime.clear();
            if (rlog != null) rlog.i(TAG, "已清空目录内存缓存");
        } catch (Exception e) {
            Log.w(TAG, "清空目录缓存失败: " + e.getMessage());
        }
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


    // ---- Toast 单例（新提示覆盖旧提示） ----
    //
    // 默认的 Toast.makeText().show() 会把提示排入队列，连续触发时
    // 会依次弹完 —— 用户看到的是"过期的提示还在弹"，尤其切歌报错这类
    // 场景会堆出好几条。这里维护一个单例 Toast，新提示直接替换旧提示，
    // 永远只显示最新的一条。
    private Toast currentToast;

    private void toast(String message) {
        toast(message, Toast.LENGTH_SHORT);
    }

    private void toast(String message, int duration) {
        try {
            if (currentToast != null) {
                currentToast.cancel();   // 取消上一条，避免排队
            }
            currentToast = Toast.makeText(this, message, duration);
            currentToast.show();
        } catch (Exception e) {
            Log.w(TAG, "显示提示失败: " + e.getMessage());
        }
    }

}

