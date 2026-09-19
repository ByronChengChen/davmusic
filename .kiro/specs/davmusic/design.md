# Design — davmusic

> Android WebDAV 音乐播放器 · 技术设计文档
>
> 本文档描述系统的架构、数据流、接口、数据模型、错误处理与单元测试策略。
> 与 `requirements.md` 中的需求编号（R*、N*）对应。
>
> **修订记录**
> - 初版：简化三层 + 单服务器，9 个类 / 2783 行
> - 本次修订（对应代码 v1.30）：新增**多服务器与会话隔离**、**根层级（服务器列表）**、
>   **前台服务与 MediaSession**、**本地离线索引**、**定位当前歌曲**，共 15 个类 / 7634 行

---

## 1. 架构概览

### 1.1 分层设计（当前版本）

```
┌──────────────────────────────────────────────────────────────┐
│  UI 层                                                        │
│  ├─ ServerConfigActivity    单台服务器配置（首次启动入口）      │
│  ├─ ServerManageActivity    多服务器管理（列表/增删改/设活动）  │
│  ├─ MainActivity            根层级 + 文件夹浏览 + 迷你播放器    │
│  ├─ FileListAdapter         文件列表适配器（文件/文件夹两态）  │
│  └─ ServerListAdapter       服务器列表适配器                   │
├──────────────────────────────────────────────────────────────┤
│  业务层                                                        │
│  ├─ WebDAVClient            按服务器实例化的协议客户端           │
│  ├─ MusicPlayer             播放控制 + 播放列表 + 单一播放线程  │
│  ├─ MusicService            前台服务 + MediaSession + 通知栏     │
│  ├─ LocalCacheManager       下载缓存 + 快照 + 离线索引（按作用域）│
│  ├─ ServerStore             服务器配置读写与活动服务器解析        │
│  └─ RemoteLogger            远程日志上报（真机诊断）             │
├──────────────────────────────────────────────────────────────┤
│  数据层                                                        │
│  ├─ OkHttp                 网络传输（每服务器一个 client 实例）  │
│  ├─ MediaPlayer            音频解码播放                        │
│  ├─ SharedPreferences      服务器列表 JSON + 活动 id + 下载映射  │
│  └─ 本地文件系统            快照 JSON + 音频文件 + 日志缓冲      │
└──────────────────────────────────────────────────────────────┘
```

**为什么仍不用 MVVM / Room / WorkManager**：主链路（浏览—下载—播放—离线）用 `Activity + 回调 + SharedPreferences + JSON` 已足够表达；引入三者会带来 ViewModel 生命周期、Room schema 迁移、WorkManager 调度等额外复杂度，而对当前需求并非必需。

**与初版的关键结构变化**：

| 变化 | 初版 | 当前 |
|------|------|------|
| 服务器 | 单例 `WebDAVClient`，全局唯一凭据 | **按 `ServerProfile` 实例化**，`clientFor(server)` 取用，凭据不共享 |
| 顶层 | 直接进入 WebDAV 根目录 | **服务器列表即根目录**，进入某台后才显示其 WebDAV 根 |
| 缓存键 | 远程路径 | **`cacheKeyOf(server, path)`**，路径前挂服务器作用域 |
| 播放 | `MediaPlayer` 在 Activity 内 | **前台服务持有播放器**，Activity 只做 UI 绑定 |
| 离线 | 仅文件夹快照 | 快照 **+ 已下载文件的离线索引与目录树重建** |

### 1.2 外部依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `okhttp` | 4.12.0 | 唯一的第三方网络库；PROPFIND 自定义方法、GET 流式下载、PUT 上传 |
| `androidx.appcompat` | 1.7.0 | Activity / 主题兼容 |
| `com.google.android.material` | 1.12.0 | Material 组件与主题 |
| `androidx.constraintlayout` | 2.1.4 | 布局 |
| `androidx.recyclerview` | 1.3.2 | 文件列表与服务器列表 |
| `androidx.swiperefreshlayout` | 1.1.0 | 下拉刷新 |
| `androidx.activity` | 1.9.3 | Activity Result API（文件选择、跳转回调） |
| `androidx.media` | 1.7.0 | `MediaSessionCompat` 与媒体通知样式 |

> XML 解析使用 Android 内置的 `XmlPullParser` / DOM，不引入 SimpleXML。JSON 序列化使用 Android 内置 `org.json`，不引入 Gson。

---

## 2. 组件设计

### 2.1 WebDAVClient（协议客户端，按服务器实例化）

**职责**：封装 WebDAV 协议细节，把 HTTP/XML 转成 Java 对象。

**设计要点**：

| 要点 | 做法 |
|------|------|
| 实例化 | **不再是全局单例**。每台服务器一个客户端实例（`clientFor(ServerProfile)`），凭据随实例绑定，避免多服务器互相覆盖 |
| 认证 | 实例持有该服务器的 Base64 Basic Auth 头，每次请求附带 |
| 自定义方法 | OkHttp 的 `Request.Builder.method("PROPFIND", body)` 发起 Depth:1 请求 |
| 超时 | connect 15s / read 60s（对应 N1） |
| 线程 | 所有网络调用为异步（`enqueue`） |
| 取消 | 返回 `Call` 对象支持取消（上传/下载中断） |
| 请求节流 | 遇 429 仅提示，不做自动失败计数与退避（N7，参见 v1.24 决策） |
| 内存缓存 | 对短期重复请求做内存级缓存以降低请求量（N8，参见 v1.23 决策） |

**PROPFIND 请求体**（要求服务器返回所需属性）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:getcontentlength/>
    <D:getlastmodified/>
    <D:getcontenttype/>
    <D:getetag/>
    <D:resourcetype/>
  </D:prop>
</D:propfind>
```

**XML 解析策略**：遍历 `<D:response>` 节点，逐个提取 `<D:href>`、`<D:getcontentlength>`、`<D:getlastmodified>`、`<D:getcontenttype>`、`<D:getetag>`，通过 `<D:resourcetype>` 是否含 `<D:collection/>` 判定文件夹。解析需容忍小写命名空间前缀（`d:`）。

**路径处理**：`href` 需剥离服务器 base 前缀并与相对路径统一，避免路径重复导致 404；拼接 URL 时须做 URL 编码（中文路径必需）。

**过滤规则**（对应 R2.9）：跳过 `.DS_Store`、以 `._` 开头的 macOS 元数据文件、以及路径等于当前目录自身的第一条记录。

**关键方法**：

| 方法 | 说明 |
|------|------|
| `listFolder(path, callback)` | PROPFIND 列目录 |
| `download(remote, dest, progressCb)` | 下载到本地（带进度） |
| `upload(local, remote, progressCb)` | PUT 上传（带进度） |
| `getDownloadUrl(remotePath)` | 拼出可 GET 的完整 URL（含编码） |
| `testConnection(cb)` | 配置页连接测试 |

### 2.2 ServerStore / ServerProfile（服务器配置与作用域）

**职责**：管理多服务器的持久化、活动服务器解析与**缓存作用域**。

**ServerProfile 字段**：

```java
public class ServerProfile {
    private String id;        // 唯一标识（UUID）
    private String name;      // 别名（用于展示与去重校验）
    private String url;       // WebDAV 基地址
    private String username;
    private String password;

    public String getDisplayName();  // 展示名（无别名时回落到 host）
    public String getHost();         // 由 url 解析出的主机
    public String getScopeId();      // 缓存作用域标识
    public boolean isComplete();     // 字段是否齐全
    public JSONObject toJson() / static fromJson(JSONObject);
}
```

**ServerStore 设计要点**：

| 项 | 做法 |
|----|------|
| 存储 | SharedPreferences `davmusic_config`：`servers_json`（服务器数组）+ `active_server_id` |
| 读取 | `list()` / `getActive()` / `getActiveScope()` |
| 写入 | `add()`（补 id 后写入）/ `update()` / `setActive()` / `remove()` |
| **回退策略** | `pickActive()`：优先按 `active_server_id` 匹配；**匹配不到则退回列表第一台** —— 服务器可能被删而 id 仍指向它，或 JSON 被外部改坏。绝不允许出现"有服务器却选不出活动服务器"导致跳回配置页 |
| 别名去重 | `isAliasTaken(alias, self)` 阻止重名保存（R1.9） |
| 作用域 | `getScopeId()` 供 `LocalCacheManager` 区分"同一远端路径属于哪台服务器"（R1.10） |

**为什么需要作用域**：两台服务器都可能有 `/music/周杰伦/`，若缓存键只用路径，会互相串扰（A 的快照被 B 读到、下载映射撞车）。作用域把缓存键变成 `scopeId + path`。

### 2.3 LocalCacheManager（本地状态层）

**职责**：管理三类本地数据 —— **已下载文件**、**文件夹快照**、**离线索引**，全部按服务器作用域隔离。

**设计要点**：

**① 下载文件存储**

| 项 | 做法 |
|----|------|
| 位置 | `context.getExternalFilesDir("music")`（对应 R4.7 / N6） |
| 命名 | 用远程路径避免非法字符与重名冲突 |
| 映射 | 远程路径 → 本地路径的映射持久化到 SharedPreferences |

**② 文件夹快照**（对应 R6.1–R6.6）

- 用户每次成功列出某文件夹后，把 `List<WebDAVFile>` 序列化为 JSON 写入本地文件
- 快照键由**作用域 + 文件夹路径**派生
- 序列化字段：`href`、`displayName`、`contentLength`、`contentType`、`lastModified`、`etag`、`isCollection`
- **不序列化** `downloadState` / `localPath` —— 本地状态读取时**实时**查询（否则会出现"快照说已下载、实际文件已被删"的不一致）

**③ 离线索引与本地文件树**（对应 R6.7–R6.8）

- 下载成功时 `indexOfflineFile()` 记录远程路径、显示名、大小
- `listLocalTree(path)` 把已下载文件**按远程路径归组**重建出目录树，供离线时浏览
- `removeDownloadedFile()` 删除本地文件时同步 `removeOfflineIndex()`，保持索引与磁盘一致
- `getOfflineFileCount()` 供"关于"页展示离线文件数

**关键方法**：

| 方法 | 说明 |
|------|------|
| `getInstance(Context)` | 单例 |
| `getLocalFile(String remotePath)` | 返回本地文件或 null |
| `isDownloaded(String remotePath)` | 是否已下载 |
| `getDownloadDestination(String)` | 计算下载目标路径 |
| `markFileDownloaded(String, File)` | 记录下载完成 |
| `removeDownloadedFile(String)` | 删除本地文件 + 清除记录（R4.6） |
| `indexOfflineFile(...)` / `removeOfflineIndex(...)` | 离线索引维护 |
| `listLocalTree(String currentPath)` | 由离线索引重建目录树（R6.8） |
| `getOfflineFileCount()` | 离线文件计数 |
| `saveSnapshot(String, List<WebDAVFile>)` / `loadSnapshot(String)` / `deleteSnapshot(String)` / `hasSnapshot(String)` / `clearSnapshots()` | 快照读写 |
| `clearAllCache()` / `getCacheSize()` | 缓存清理 |

> **作用域传入方式**：早期实现依赖 `ServerStore.getActiveScope()` 隐式取值，在多服务器场景下会串扰。当前实现由调用方把作用域（或已拼好的 cacheKey）显式传入。

### 2.4 MusicPlayer / MusicService（播放层）

**职责**：`MusicPlayer` 封装 `MediaPlayer` 与播放列表；`MusicService` 作为前台服务承载播放器，向上提供通知栏/锁屏/耳机控制。

**MusicPlayer 设计要点**：

| 要点 | 做法 |
|------|------|
| **单一播放线程** | 消息队列串行处理所有播放器操作，杜绝多线程并发访问造成状态错乱（R9.7） |
| **在线播放** | `setDataSource(Context, Uri, Map<String,String> headers)` —— headers 携带 `Authorization`（R5.2） |
| **本地播放** | 直接 `setDataSource(filePath)`，无网络请求（R6.3） |
| 进度更新 | 定时回调当前位置（服务侧 1s 与通知同频，UI 侧 500ms） |
| 播放列表 | 持有 `List<WebDAVFile>` 与当前索引，支持上一首/下一首 |
| 状态回调 | `OnPlaybackListener`：`onTrackChanged` / `onPlayStateChanged` / `onProgress` / `onError` |
| 错误抑制 | 切歌过程中抑制中间态误报错误（R5.10） |
| 资源释放 | `release()` 停止轮询、释放 MediaPlayer、清空监听器 |

**MusicService 设计要点**（对应 R9）：

| 要点 | 做法 |
|------|------|
| 启动 | `startForegroundService()` → `onCreate` 内 5 秒内必须调用 `startForeground` |
| 通知 | `NotificationCompat.MediaStyle` + 播放/暂停、上一首、下一首 action |
| 通知渠道 | `IMPORTANCE_LOW`（不响铃不震动），`VISIBILITY_PUBLIC`（锁屏可见） |
| 媒体会话 | 使用 **androidx 的 `MediaSessionCompat`**，而非平台版 `MediaSession`。原因：`androidx.media.app.NotificationCompat.MediaStyle` 要求 `MediaSessionCompat.Token`，与平台版 Token 类型不兼容 |
| 按键映射 | `Callback.onPlay/onPause/onSkipToNext/onSkipToPrevious`；耳机单击=播放暂停、双击=下一首、三击=上一首（由系统/耳机固件转换为 MediaSession 回调） |
| **进度同步** | `PlaybackState.position` 是**静态快照**，只在状态变化时更新会导致系统进度条停住。因此每秒周期性刷新 `updateMediaSessionState()` |
| 播完续播 | `onCompletion` → 下一首，锁屏/后台状态下同样成立（R9.6） |
| 释放 | `stopSelf()` 结束服务 |

**播放源决策逻辑**（R5.1）：

```
playFile(WebDAVFile file):
    if LocalCacheManager.isDownloaded(file.href):
        → 播放本地文件路径
    else:
        → 播放 getDownloadUrl(file.href)，携带 Authorization 头
```

### 2.5 RemoteLogger（诊断）

**职责**：把关键路径的日志上报到远端，用于真机定位难以复现的问题（尤其是锁屏/后台播放，无法连 IDE 看 logcat）。

- 提供 `i` / `w` / `e` 级别方法，带 TAG
- 日志写入本地缓冲并批量上报，避免频繁网络请求
- 典型用途：定位"锁屏后台切歌失败"、"预加载被自己销毁"等仅在真机长时运行下出现的问题（N9）

### 2.6 UI 层

**ServerConfigActivity**

- 输入框：WebDAV 地址 / 用户名 / 密码 / 别名
- "测试连接"按钮 → `WebDAVClient.testConnection()` → Toast 反馈（R1.4）
- "保存"按钮 → `ServerStore` 持久化 → 返回主界面
- 支持编辑模式：进入时回填当前值（R1.5）

**ServerManageActivity**

- 服务器列表（`ServerListAdapter`）：展示别名 / 主机 / 活动标记
- 支持新增、编辑、删除、设为活动（R1.5）
- 删除时同步清理该作用域下的缓存与快照
- 可通过 `EXTRA_ENTER_SERVER_ID` 直接进入指定服务器

**MainActivity**（2266 行，最大的类）

| 区域 | 内容 |
|------|------|
| 顶部 | 返回按钮 + 面包屑路径（含服务器名）+ 在线/离线状态标识 |
| 中部 | RecyclerView 列表（**根层级显示服务器列表，进入服务器后显示文件列表**） |
| 底部 | 迷你播放器（曲目名 / 播放暂停 / 上下一首 / 进度条 / 时间） |
| 菜单 | 上传、服务器管理、定位到当前歌曲、关于 |

关键逻辑：

- `onBackPressed()`：**三层回退** —— 服务器内非根目录 → 上跳一级文件夹；服务器根目录 → 返回服务器列表；服务器列表 → 交给系统退出（R2.4）
- `currentServer` 为 null 时即处于服务器列表根层级（R2.0）
- 点击文件夹 → 更新路径 → `loadFolder()`；点击音频 → `MusicPlayer.playFile()`（R2.3 / R5.1）
- 下载按钮 → 子线程下载 + `runOnUiThread` 更新进度（R4.1 / R4.2 / N2）
- **请求期服务器快照**：请求发出时用 `final ServerProfile serverAtRequest = currentServer` 固化，
  回调里用该实例算 cacheKey；否则用户请求途中切服务器会把 A 的结果写进 B 的作用域（R2.10）
- `loadFolder()` 的降级链：**网络请求 → 失败则读快照 → 再退到本地离线索引 → 都无则提示**（R6.2 / R6.5 / R6.8）
- `locateCurrentTrack()`：由当前播放曲目的路径推出所在目录并跳转、滚动定位（R10）
- 滚动位置需按目录保存/恢复，否则"离线返回后目录看似消失"（v1.20 真因）

**FileListAdapter**

- 两种 viewType：文件夹 / 文件
- 音频条目右侧三态 UI：下载按钮（未下载）· 进度条（下载中）· 删除按钮（已下载）
- **已下载条目文字色 `#4CAF50`（绿色），未下载用默认色**（R4.3 / R4.4）
- 提供 `updateDownloadState(href, state)` 做局部刷新，避免整表重绘
- **ViewHolder 复用需重置全部状态**，否则出现列表渲染错乱（v1.21 真因）

### 2.7 工具类

| 类 | 职责 |
|----|------|
| `FileUtils` | 扩展名判定（`isAudioFile` / `isImageFile` / `isLyricsFile`）、大小与时长格式化、路径操作 |
| `NetworkUtils` | 网络连通性检测、网络类型判定、`getNetworkStatusString` 供 UI 显示在线/离线（R6.4） |

**音频扩展名白名单**（R3.1）：
`mp3` · `m4a` · `flac` · `wav` · `wma` · `aac` · `ogg` · `ape` · `opus`（大小写不敏感）

---

## 3. 数据模型

### 3.1 WebDAVFile

```java
public class WebDAVFile {
    private String href;            // 服务器路径（唯一标识）
    private String displayName;     // 显示名称
    private long contentLength;     // 字节数
    private String contentType;     // MIME
    private long lastModified;      // 时间戳
    private String etag;            // 版本标识（用于快照更新判定）
    private boolean isCollection;   // true = 文件夹
    private DownloadState downloadState;  // 本地状态（不入快照）
    private String localPath;       // 本地路径（不入快照）

    public enum DownloadState {
        NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED
    }
}
```

**设计说明**：`downloadState` 与 `localPath` 是**运行时字段**，不写入快照 JSON。快照记录的是服务器端的客观事实（文件存在、大小、etag）；本地状态通过 `LocalCacheManager.isDownloaded(href)` 实时查询。这样快照不会因本地文件被删除而失真。

### 3.2 服务器配置（持久化模型）

```
SharedPreferences "davmusic_config"
  ├─ servers_json      → JSON 数组，元素为 ServerProfile 序列化对象
  └─ active_server_id  → 当前活动服务器的 id

ServerProfile (JSON)
  ├─ id        唯一标识
  ├─ name      别名（展示 + 去重）
  ├─ url       WebDAV 基地址
  ├─ username
  └─ password
```

**作用域键（scopeId）**：由 `ServerProfile.getScopeId()` 派生，作为 `LocalCacheManager` 中所有缓存键的前缀：

```
cacheKey = scopeId + "::" + remotePath
```

**设计含义**：同一远端路径在不同服务器下必然得到不同的 cacheKey，因此：
- 快照不会被跨服务器误读（A 的 `/music/` 不会覆盖 B 的 `/music/`）
- 下载映射不会撞车（两台服务器都有 `/a.mp3` 时各存各的）
- 删除服务器时可据 scopeId 精确清理其全部本地数据

**与初版的差异**：初版把单台服务器的 url/user/password 直接存成三个独立 key（`server_url` 等），无 id、无别名、无作用域概念，多服务器场景下会全面串扰。当前保留这些旧 key 仅为兼容历史配置的读取。

### 3.3 播放列表

不单独建类，直接用 `List<WebDAVFile>` + 当前索引表示。播放列表来自"当前文件夹的全部音频文件"，简化了跨目录队列的复杂度。

**与初版的差异**：播放列表现在由 `MusicService` 持有并跨 Activity 生命周期存活，Activity 销毁重建不影响播放；服务通过 MediaSession 与系统媒体控制中心保持同步。

---

## 4. 数据流

### 4.0 启动与根层级（对应 R1、R2.0）

```
应用启动
  → ServerStore.list() 有服务器？
      ├─ 无 → ServerConfigActivity 强制配置（R1.1）
      └─ 有 → ServerStore.getActive()（匹配 active_server_id，失败则回退第一台，R1.8）
              → MainActivity 进入根层级：currentServer = null
                  → 列表显示全部服务器（ServerListAdapter）
                  → 用户点击某台 → currentServer = 该 profile
                                    → 以其 WebDAV 根为当前路径 → loadFolder()
```

### 4.1 浏览文件夹（在线）

```
用户点击文件夹
  → MainActivity.loadFolder(path)
  → final ServerProfile serverAtRequest = currentServer   ← 固化，防请求途中切换（R2.10）
  → final String cacheKey = cacheKeyOf(serverAtRequest, path)   ← 作用域隔离
  → WebDAVClient.listFolder(path, callback)
  → PROPFIND Depth:1 → 解析 XML → List<WebDAVFile>
  → 过滤系统文件 + 按类型分组排序
  → LocalCacheManager.saveSnapshot(path, files)   ← 落快照（带作用域）
  → LocalCacheManager.isDownloaded() 标注每个条目的下载状态
  → FileListAdapter.setFiles(files) → 渲染（已下载显示绿色）
```

### 4.2 浏览文件夹（离线，对应 R6.2 / R6.5 / R6.8）

```
用户点击文件夹
  → MainActivity.loadFolder(path)
  → NetworkUtils.isNetworkConnected() == false
  → 降级链：
      ① LocalCacheManager.loadSnapshot(path)   ← 曾在线访问过
      ② LocalCacheManager.listLocalTree(path)  ← 仅凭已下载文件重建目录树（R6.8）
      ③ 都没有 → 提示"该文件夹无本地快照，需联网加载"（R6.5）
```

### 4.2.1 网络恢复（对应 R6.10）

```
NetworkUtils 回调检测到由离线 → 在线
  → 自动重新 loadFolder(当前路径)
  → 用服务器结果覆盖快照与列表（从本地视图切回在线视图）
```

### 4.3 下载音乐（对应 R4）

```
用户点击下载按钮
  → 条目状态置 DOWNLOADING，显示进度条
  → WebDAVClient.download(remotePath, destFile, progressCallback)
  → 进度回调 → runOnUiThread → 更新进度条（R4.2）
  → 完成 → LocalCacheManager.markFileDownloaded(href, file)
          → 持久化映射到 SharedPreferences（R4.5）
          → LocalCacheManager.indexOfflineFile(...)   ← 写离线索引（R6.7）
          → adapter.updateDownloadState(href, DOWNLOADED) → 文字变绿（R4.3）
  → 失败 → 清理临时文件 + Toast 提示（R4.8）
```

### 4.4 播放与后台保活（对应 R5.1、R9）

```
用户点击音频条目
  → MusicService 启动（startForegroundService）+ 创建媒体通知与 MediaSession（R9.1–R9.3）
  → MusicPlayer.setPlaylist(当前文件夹的音频列表, 点击项索引)
  → MusicPlayer.playFile(file)   ← 在单一播放线程串行执行（R9.7）
      → LocalCacheManager.isDownloaded(file.href)?
          是 → setDataSource(本地文件路径)         ← 无网络请求
          否 → setDataSource(uri, headers={Authorization})  ← 流式播放
  → onTrackChanged / onPlayStateChanged → MainActivity 更新迷你播放器
  → 定时进度回调（UI 500ms / 通知 1s） → 更新进度条与时长显示
  → updateMediaSessionState() 每秒刷新 → 系统媒体控制中心进度条持续走动（R9.5）

曲目播完
  → MusicPlayer.onCompletion → 播放列表下一首
  → 该行为在锁屏/后台（Activity 已销毁）下同样成立（R9.6）

用户从通知栏/锁屏/蓝牙耳机操作
  → MediaSessionCompat.Callback（onPlay / onPause / onSkipToNext / onSkipToPrevious）
  → MusicPlayer 对应动作
```

### 4.5 上传（对应 R7）

```
用户点击上传按钮 → 系统文件选择器 → 选中文件
  → FileUtils.isAudioFile()? 否 → 拒绝（R8.1）
  → 是 → WebDAVClient.upload(localFile, 当前目录 + 文件名, progressCallback)
  → 进度回调 → 更新 UI（R7.3）
  → 成功 → 重新 loadFolder() 刷新列表（R7.4）
  → 失败 → Toast 提示（R7.5）
```

### 4.6 定位到当前歌曲（对应 R10）

```
用户在"更多"菜单选择"定位到当前歌曲"
  → 取当前播放曲目（无则提示，R10.2）
  → 由曲目远程路径推出所在目录
  → 必要时先回到该曲目所属服务器
  → 加载该目录并滚动到该条目、高亮（R10.3）
```

### 4.7 切换服务器（对应 R1.7、R1.10）

```
用户在某台服务器内选择"切换服务器"
  → ServerStore.setActive(newId)
  → MainActivity 重置路径栈与 currentServer
  → 以新服务器的作用域重新 loadFolder()
  → 旧服务器的快照/下载映射保持独立，不受影响
```

---

## 5. 接口契约

### 5.1 回调接口

```java
// WebDAVClient 通用回调
public interface WebDAVCallback<T> {
    void onSuccess(T result);
    void onError(Exception e);
}

// WebDAVClient 进度回调（下载/上传）
public interface ProgressCallback {
    void onProgress(int progress);       // 0-100
    void onSuccess(Object result);
    void onError(Exception e);
}

// MusicPlayer 播放状态回调
public interface OnPlaybackListener {
    void onTrackChanged(WebDAVFile track);
    void onPlayStateChanged(boolean isPlaying);
    void onProgress(int position, int duration);
    void onError(String error);
}
```

### 5.2 线程契约

| 操作 | 线程 | UI 更新方式 |
|------|------|------------|
| 网络请求 | OkHttp 异步回调线程 | 调用方用 `runOnUiThread` |
| 文件下载 | ExecutorService 子线程 | 进度回调内 `runOnUiThread` |
| MediaPlayer 操作 | 主线程 | 直接更新 |
| 进度轮询 | Handler（主线程） | 直接更新 |

**原则**（N2）：任何可能阻塞的操作都不在主线程执行；任何 View 操作都不在子线程执行。

---

## 6. 错误处理

| 场景 | 处理策略 | 需求 |
|------|---------|------|
| 网络不可用 | 降级链：快照 → 本地离线索引 → 提示 | R6.2 / R6.5 / R6.8 |
| 认证失败（401） | 提示检查用户名密码，引导回该服务器的配置页 | R1.4 |
| 目录不存在（404） | 提示"文件夹不存在"，返回上级 | R2.8 |
| 服务器错误（5xx） | 显示状态码 + 可读提示 | R2.8 |
| **限流（429）** | **仅 Toast 提示，不自动重试**（避免失败计数与退避和服务器限制自锁，见 v1.24 决策） | N7 |
| XML 解析失败 | 跳过该条目，不中断整个列表 | R2.8 |
| 下载中断 | 删除不完整临时文件，状态回退为未下载 | R4.8 |
| 上传失败 | Toast 提示，不影响其他功能 | R7.5 |
| 播放失败（格式不支持/网络断） | `onError` 回调 → 提示并跳过到下一首 | R5.7 |
| **切歌过程中的中间态错误** | **抑制误报**，不弹错误提示（v1.26 修复） | R5.10 |
| 缓冲态 | 保持稳定不崩溃，缓冲结束继续播放 | R5.9 |
| 超时 | connect 15s / read 60s，超时按网络错误处理 | N1 |
| **活动服务器 id 失效** | **回退到列表第一台**，不跳回配置页 | R1.8 |
| **请求途中切换服务器** | 回调按请求发出时的服务器实例算 cacheKey，不污染新作用域 | R2.10 |
| **列表渲染错乱** | ViewHolder 复用时重置全部状态（v1.21 真因） | R2.2 |
| **离线返回后条目消失** | 按目录保存/恢复滚动位置（v1.20 真因） | R2.5 |

---

## 7. 已知技术风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| WebDAV 服务器实现差异 | PROPFIND 响应结构不一 | 兼容大小写命名空间；解析失败时跳过单条而非整体失败 |
| 在线流式播放需 `Authorization` 头 | 若服务器重定向到 CDN，headers 可能丢失 | 已实测目标服务器支持 Range 与 302 回源，需真机验证 |
| MediaPlayer 对流式的缓冲 | 弱网下可能卡顿 | 提示用户下载后离线播放（这也是产品设计的核心动机） |
| 快照与真实内容不一致 | 离线看到已删除的文件 | 快照带 `lastModified`；在线时以服务器结果为准并覆盖快照；并补离线索引以兜底 |
| **多服务器缓存串扰** | A 服务器的快照/下载被 B 读到 | **scopeId 作用域前缀**；请求期固化 `serverAtRequest`（R1.10 / R2.10） |
| **Android 后台限制** | 切后台/锁屏播放被系统杀死 | **已实现前台服务 + 媒体通知**（v 前台服务提交）；配合 `WAKE_LOCK` |
| **锁屏/后台事件难以复现调试** | 无法连 IDE 看 logcat | **RemoteLogger 远程日志上报**，从真机回收日志定位根因 |
| **`startForeground` 超时** | 服务启动 5 秒内未调用会 ANR/崩溃 | `onCreate` 中立即调用 `startForegroundCompat()` |
| **MediaSession 类型不兼容** | 平台版 Token 无法用于 androidx MediaStyle | 统一使用 `androidx.media.session.MediaSessionCompat` |
| **系统媒体进度条不动** | `PlaybackState.position` 是静态快照 | 每秒周期性 `updateMediaSessionState()`（R9.5） |
| 大量文件时列表卡顿 | 目录含数百音频 | RecyclerView 复用 + 局部刷新（`updateDownloadState`）而非整表重绘 |
| 离线索引与磁盘不一致 | 索引里有但文件已删 | `removeDownloadedFile()` 同步清理索引；`getCacheSize()`/`getOfflineFileCount()` 供核对 |
| **CI 构建环境变更** | `sdkmanager` 移除 `tools` 包导致 SDK 安装失败 | 工作流已适配（不再安装 `tools`）；见 tasks.md 任务 10 |

---

## 8. 单元测试策略

### 8.1 可测试性设计

当前不做依赖注入，但通过**纯函数抽取**和**接口隔离**保留可测性：

| 可测单元 | 测试方式 |
|---------|---------|
| `FileUtils` 的扩展名判定 | 纯函数，输入字符串 → 断言布尔值，无需 Android 环境 |
| `FileUtils` 的大小/时长格式化 | 纯函数，覆盖边界值（0、负数、超大值） |
| `WebDAVClient` 的 XML 解析 | 抽取 `parseResponse(String xml)`，用样例 XML 断言结果 |
| `ServerProfile` 的 JSON 往返 / `getScopeId` | 纯函数，构造对象 → 序列化 → 反序列化 → 断言字段一致 |
| `ServerStore.pickActive` 的回退逻辑 | 纯函数（列表 + activeId → 期望 profile），覆盖 id 失效场景 |
| `LocalCacheManager` 的 JSON 序列化 | 构造 `List<WebDAVFile>` → 序列化 → 反序列化 → 断言字段一致 |
| `LocalCacheManager.listLocalTree` 的目录重建 | 给定离线索引条目集合 → 断言重建出的树结构 |
| `NetworkUtils` 的判断逻辑 | 依赖 `Context`，需 Robolectric 或仪器测试 |

### 8.2 测试用例清单

**`FileUtilsTest`（JUnit，本地测试）**

| 用例 | 输入 | 期望 |
|------|------|------|
| 识别 mp3 | `"song.mp3"` | `true` |
| 识别 m4a | `"01 黄色月亮.m4a"` | `true` |
| 大小写不敏感 | `"SONG.MP3"` | `true` |
| 拒绝非音频 | `"cover.jpg"` | `false` |
| 拒绝无扩展名 | `"README"` | `false` |
| 中文文件名 | `"02 被动.m4a"` | `true` |
| 格式化大小 | `7793636` | `"7.4 MB"` |
| 格式化 0 字节 | `0` | `"0 B"` |
| 提取扩展名 | `"a.b.mp3"` | `"mp3"` |
| 去扩展名 | `"01 黄色月亮.m4a"` | `"01 黄色月亮"` |

**`WebDAVResponseParserTest`（JUnit，本地测试）**

| 用例 | 说明 |
|------|------|
| 解析文件夹响应 | 含 `<D:collection/>` 的条目 → `isCollection() == true` |
| 解析文件响应 | 含 `<D:getcontentlength>` 的条目 → 大小正确 |
| 解析中文路径 | `href` 为 URL 编码的中文 → 正确解码为可读名称 |
| 跳过自身条目 | 响应首条通常是目录本身 → 应被过滤 |
| 过滤 `.DS_Store` | macOS 元数据文件 → 不出现在结果中 |
| 过滤 `._` 前缀 | `._.DS_Store` → 被过滤 |
| 空目录 | 仅含自身条目 → 返回空列表 |
| 命名空间变体 | 服务器用 `d:` 小写前缀 → 仍能正确解析 |

**`ServerProfileTest` / `ServerStoreTest`（JUnit，本地测试）**

| 用例 | 说明 |
|------|------|
| JSON 往返 | `toJson` → `fromJson` → 各字段一致 |
| scopeId 稳定性 | 同一 profile 多次调用 `getScopeId()` 结果一致 |
| scopeId 区分性 | 两台不同 url 的服务器 → scopeId 不同 |
| 展示名回落 | 无别名时 `getDisplayName()` 回落到 host |
| 活动服务器匹配 | activeId 命中 → 返回该台 |
| **活动服务器回退** | activeId 指向已删除的 id → 返回列表第一台（R1.8） |
| 空列表 | 无服务器 → `getActive()` 返回 null |
| 别名重复检测 | 与其它服务器同名 → `isAliasTaken` 返回 true |

**`LocalCacheManagerTest`（需 Robolectric 或仪器测试）**

| 用例 | 说明 |
|------|------|
| 快照存取往返 | `saveSnapshot` → `loadSnapshot` 字段一致 |
| 快照不含本地状态 | 序列化结果中不含 `downloadState` / `localPath` |
| 下载标记持久化 | `markFileDownloaded` 后 `isDownloaded` 返回 true |
| 删除后状态清除 | `removeDownloadedFile` 后 `isDownloaded` 返回 false |
| 删除后索引同步 | `removeDownloadedFile` 后离线索引中不再含该条目 |
| 不存在路径查询 | `getLocalFile("不存在")` 返回 null |
| 快照覆盖更新 | 重复 `saveSnapshot` 同路径 → 内容被覆盖而非追加 |
| **作用域隔离** | 相同路径在不同 scopeId 下 → 快照与下载记录互不可见 |
| **目录树重建** | 索引含 `/a/b/c.mp3` → `listLocalTree("/a/b")` 能列出 `c.mp3` |

**`MusicPlayerTest`（需仪器测试，或抽出决策逻辑做纯函数测试）**

| 用例 | 说明 |
|------|------|
| 播放源选择 | 已下载 → 本地路径；未下载 → 在线 URL |
| 播放列表索引 | `setPlaylist(list, 3)` → 当前曲目为第 4 项 |
| 下一首边界 | 最后一首时 `playNext()` → 行为符合设计（循环或停止） |
| 上一首边界 | 第一首时 `playPrevious()` → 行为符合设计 |
| 错误回调 | 设置无效数据源 → 触发 `onError` |
| **切歌不误报** | 连续切歌过程 → 不产生误报 `onError`（R5.10） |
| **单一线程** | 并发发起多个播放操作 → 状态不乱（R9.7） |

**`NetworkUtilsTest`（需 Robolectric）**

| 用例 | 说明 |
|------|------|
| 无网络检测 | 模拟断网 → `isNetworkConnected()` 返回 false |
| 网络状态字符串 | 离线 → 返回可读的离线描述（供 UI 显示） |

### 8.3 不在当前版本覆盖的部分

| 项 | 原因 |
|----|------|
| UI 交互测试（Espresso） | 优先验证核心链路，UI 测试成本高 |
| 真实 WebDAV 服务器集成测试 | 需要稳定测试环境与凭据，改用 MockWebServer 后续补充 |
| MediaPlayer 真实解码测试 | 依赖音频文件与设备能力，手工验证 |
| **MediaSession / 通知栏交互** | 依赖系统媒体控制中心，真机手工验证 |
| **前台服务保活时长** | 依赖厂商 ROM 后台策略，真机长时验证 |
| 性能测试 | 数据量小，暂不需要 |

### 8.4 建议的测试依赖

```gradle
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.robolectric:robolectric:4.11.1'   // 需要 Context 的场景
testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'  // 后续补网络层测试
androidTestImplementation 'androidx.test.ext:junit:1.1.5'
androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
```

> 当前 `app/build.gradle` 已含 JUnit 与 Espresso；Robolectric 与 MockWebServer 为建议新增项。
