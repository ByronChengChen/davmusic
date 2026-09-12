# Design — davmusic

> Android WebDAV 音乐播放器 · 技术设计文档
>
> 本文档描述系统的架构、数据流、接口、数据模型、错误处理与单元测试策略。
> 与 `requirements.md` 中的需求编号（R*、N*）对应。

---

## 1. 架构概览

### 1.1 分层设计（第一版：简化三层）

```
┌──────────────────────────────────────────────────────────┐
│  UI 层                                                    │
│  ├─ ServerConfigActivity   服务器配置（首次启动入口）      │
│  ├─ MainActivity           文件夹浏览 + 迷你播放器         │
│  └─ FileListAdapter        RecyclerView 列表适配器         │
├──────────────────────────────────────────────────────────┤
│  业务层                                                    │
│  ├─ WebDAVClient           WebDAV 协议（PROPFIND/GET/PUT）│
│  ├─ MusicPlayer            播放控制 + 播放列表             │
│  └─ LocalCacheManager      下载缓存 + 文件夹快照           │
├──────────────────────────────────────────────────────────┤
│  数据层                                                    │
│  ├─ OkHttp                 网络传输                        │
│  ├─ MediaPlayer            音频解码播放                    │
│  ├─ SharedPreferences      配置 + 下载记录映射             │
│  └─ 本地文件系统            快照 JSON + 音频文件           │
└──────────────────────────────────────────────────────────┘
```

**为什么不用 MVVM / Room / WorkManager**：第一版目标是尽快跑通端到端流程。三者都会引入显著复杂度（ViewModel 生命周期、Room 的 schema 与迁移、WorkManager 的任务调度），而对"浏览—下载—播放—离线"这条主链路并非必需。用 `Activity + 回调 + SharedPreferences + JSON` 已足够表达全部需求（R1–R8、N1–N6）。

### 1.2 外部依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `okhttp` | 4.12.0 | 唯一的第三方库；PROPFIND 自定义方法、GET 流式下载、PUT 上传 |
| `androidx.appcompat` | 1.7.0 | Activity / 主题兼容 |
| `com.google.android.material` | 1.12.0 | Material 组件与主题 |
| `androidx.constraintlayout` | 2.1.4 | 布局 |

> XML 解析使用 Android 内置的 `XmlPullParser` / DOM，不引入 SimpleXML。JSON 序列化使用 Android 内置 `org.json`，不引入 Gson。

---

## 2. 组件设计

### 2.1 WebDAVClient（会话契约层）

**职责**：封装 WebDAV 协议细节，把 HTTP/XML 转成 Java 对象。

**设计要点**：

| 要点 | 做法 |
|------|------|
| 单例 | `synchronized getInstance()`，全局唯一 OkHttpClient（复用连接池） |
| 认证 | `configure(baseUrl, username, password)` 保存凭据，每次请求构造 Basic Auth 头 |
| 自定义方法 | OkHttp 的 `Request.Builder.method("PROPFIND", body)` 发起 Depth:1 请求 |
| 超时 | connect 15s / read 60s（对应 N1） |
| 线程 | 所有网络调用为异步（`enqueue`），回调在主线程外的调用方线程处理，UI 更新由调用方切回 |
| 取消 | 返回 `Call` 对象支持取消（上传/下载中断） |

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

**XML 解析策略**：遍历 `<D:response>` 节点，逐个提取 `<D:href>`、`<D:getcontentlength>`、`<D:getlastmodified>`、`<D:getcontenttype>`、`<D:getetag>`，通过 `<D:resourcetype>` 是否含 `<D:collection/>` 判定文件夹。

**过滤规则**（对应 R2.9）：跳过 `.DS_Store`、以 `._` 开头的 macOS 元数据文件、以及路径等于当前目录自身的第一条记录。

**关键方法**：

| 方法 | 签名 | 说明 |
|------|------|------|
| `getInstance` | `static WebDAVClient getInstance()` | 单例获取 |
| `configure` | `void configure(String baseUrl, String user, String pass)` | 设置凭据 |
| `isConfigured` | `boolean isConfigured()` | 配置是否就绪 |
| `listFolder` | `void listFolder(String path, WebDAVCallback<List<WebDAVFile>> cb)` | PROPFIND 列目录 |
| `download` | `void download(String remote, File dest, ProgressCallback cb)` | 下载到本地（带进度） |
| `upload` | `void upload(File local, String remote, ProgressCallback cb)` | PUT 上传（带进度） |
| `getDownloadUrl` | `String getDownloadUrl(String remotePath)` | 拼出可 GET 的完整 URL |
| `testConnection` | `void testConnection(WebDAVCallback<Boolean> cb)` | 配置页连接测试 |

### 2.2 LocalCacheManager（本地状态层）

**职责**：管理两类本地数据 —— **已下载文件** 与 **文件夹快照**。

**设计要点**：

**① 下载文件存储**

| 项 | 做法 |
|----|------|
| 位置 | `context.getExternalFilesDir("music")`（对应 R4.7 / N6） |
| 命名 | 用远程路径避免非法字符与重名冲突 |
| 映射 | 远程路径 → 本地路径的映射持久化到 SharedPreferences |
| 查询 | `isDownloaded(remotePath)` / `getLocalFile(remotePath)` |

**② 文件夹快照**（离线能力核心，对应 R6.1–R6.6）

- 用户每次成功列出某文件夹后，把 `List<WebDAVFile>` 序列化为 JSON 写入本地文件
- 快照文件名由文件夹路径派生（保证可逆查找）
- 序列化字段：`href`、`displayName`、`contentLength`、`contentType`、`lastModified`、`etag`、`isCollection`
- **不序列化** `downloadState` / `localPath` —— 这两个是本地状态，读取快照时**实时**向下载映射查询（否则会出现"快照说已下载、实际文件已被删"的不一致）

**关键方法**：

| 方法 | 说明 |
|------|------|
| `getInstance(Context)` | 单例 |
| `getLocalFile(String remotePath)` | 返回本地文件或 null |
| `isDownloaded(String remotePath)` | 是否已下载 |
| `getDownloadDestination(String remotePath)` | 计算下载目标路径 |
| `markFileDownloaded(String, File)` | 记录下载完成 |
| `removeDownloadedFile(String)` | 删除本地文件 + 清除记录（R4.6） |
| `saveSnapshot(String folderPath, List<WebDAVFile>)` | 保存文件夹快照 |
| `loadSnapshot(String folderPath)` | 读取快照（离线浏览用） |
| `hasSnapshot(String folderPath)` | 是否存在快照（R6.5 判定） |
| `clearAllCache()` / `getCacheSize()` | 缓存清理 |

### 2.3 MusicPlayer（播放层）

**职责**：封装 `MediaPlayer`，管理播放列表与状态回调。

**设计要点**：

| 要点 | 做法 |
|------|------|
| 单例 | 全局唯一播放器实例，避免多 Activity 重复创建 |
| **在线播放** | `setDataSource(Context, Uri, Map<String,String> headers)` —— headers 携带 `Authorization`，这是能播放受保护 WebDAV 资源的关键（R5.2） |
| **本地播放** | 直接 `setDataSource(filePath)`，无网络请求（R6.3） |
| 进度更新 | `Handler.postDelayed` 每 500ms 回调一次当前位置 |
| 播放列表 | 持有 `List<WebDAVFile>` 与当前索引，支持上一首/下一首 |
| 状态回调 | `OnPlaybackListener`：`onTrackChanged` / `onPlayStateChanged` / `onProgress` / `onError` |
| 资源释放 | `release()` 停止进度轮询、释放 MediaPlayer、清空监听器 |

**播放源决策逻辑**（R5.1）：

```
playFile(WebDAVFile file):
    if LocalCacheManager.isDownloaded(file.href):
        → 播放本地文件路径
    else:
        → 播放 getDownloadUrl(file.href)，携带 Authorization 头
```

### 2.4 UI 层

**ServerConfigActivity**

- 三个输入框：WebDAV 地址 / 用户名 / 密码
- "测试连接"按钮 → `WebDAVClient.testConnection()` → Toast 反馈（R1.4）
- "保存"按钮 → 写入 SharedPreferences → 跳转 MainActivity
- `isConfigured(Context)` 静态方法供启动时判断是否跳过本页（R1.1 / R1.3）

**MainActivity**（731 行，最大的类）

| 区域 | 内容 |
|------|------|
| 顶部 | 面包屑路径 + 在线/离线状态标识 |
| 中部 | RecyclerView 文件列表 |
| 底部 | 迷你播放器（曲目名 / 播放暂停 / 上下一首 / 进度条） |
| 悬浮按钮 | 上传 |

关键逻辑：

- `onBackPressed()`：非根目录则回上级并重新加载，根目录则交给系统（R2.4）
- 点击文件夹 → 更新路径 → `loadFolder()`；点击音频 → `MusicPlayer.playFile()`（R2.3 / R5.1）
- 下载按钮 → 子线程下载 + `runOnUiThread` 更新进度（R4.1 / R4.2 / N2）
- 长按音频 → 弹出菜单（下载 / 删除本地 / 详情）
- `loadFolder()` 的降级链：**网络请求 → 失败则读快照 → 无快照则提示**（R6.2 / R6.5）

**FileListAdapter**

- 两种 viewType：文件夹 / 文件
- 音频条目右侧三态 UI：下载按钮（未下载）· 进度条（下载中）· 删除按钮（已下载）
- **已下载条目文字色 `#4CAF50`（绿色），未下载用默认色**（R4.3 / R4.4）
- 提供 `updateDownloadState(href, state)` 做局部刷新，避免整表重绘

### 2.5 工具类

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

### 3.2 播放列表

不单独建类，直接用 `List<WebDAVFile>` + 当前索引表示。播放列表来自"当前文件夹的全部音频文件"，简化了跨目录队列的复杂度。

---

## 4. 数据流

### 4.1 浏览文件夹（在线）

```
用户点击文件夹
  → MainActivity.loadFolder(path)
  → WebDAVClient.listFolder(path, callback)
  → PROPFIND Depth:1 → 解析 XML → List<WebDAVFile>
  → 过滤系统文件 + 按类型分组排序
  → LocalCacheManager.saveSnapshot(path, files)   ← 落快照
  → LocalCacheManager.isDownloaded() 标注每个条目的下载状态
  → FileListAdapter.setFiles(files) → 渲染（已下载显示绿色）
```

### 4.2 浏览文件夹（离线，对应 R6.2）

```
用户点击文件夹
  → MainActivity.loadFolder(path)
  → NetworkUtils.isNetworkConnected() == false
  → LocalCacheManager.loadSnapshot(path)
      ├─ 有快照 → 展示快照内容 + 提示"离线模式"
      └─ 无快照 → 提示"该文件夹无本地快照，需联网加载"（R6.5）
```

### 4.3 下载音乐（对应 R4）

```
用户点击下载按钮
  → 条目状态置 DOWNLOADING，显示进度条
  → WebDAVClient.download(remotePath, destFile, progressCallback)
  → 进度回调 → runOnUiThread → 更新进度条（R4.2）
  → 完成 → LocalCacheManager.markFileDownloaded(href, file)
          → 持久化映射到 SharedPreferences（R4.5）
          → adapter.updateDownloadState(href, DOWNLOADED) → 文字变绿（R4.3）
  → 失败 → 清理临时文件 + Toast 提示（R4.8）
```

### 4.4 播放（对应 R5.1）

```
用户点击音频条目
  → MusicPlayer.setPlaylist(当前文件夹的音频列表, 点击项索引)
  → MusicPlayer.playFile(file)
      → LocalCacheManager.isDownloaded(file.href)?
          是 → setDataSource(本地文件路径)         ← 无网络请求
          否 → setDataSource(uri, headers={Authorization})  ← 流式播放
  → onTrackChanged / onPlayStateChanged → MainActivity 更新迷你播放器
  → Handler 每 500ms → onProgress → 更新进度条与时长显示
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
| 网络不可用 | 降级读快照；无快照则提示 | R6.2 / R6.5 |
| 认证失败（401） | 提示检查用户名密码，引导回配置页 | R1.4 |
| 目录不存在（404） | 提示"文件夹不存在"，返回上级 | R2.8 |
| 服务器错误（5xx） | 显示状态码 + 可读提示 | R2.8 |
| XML 解析失败 | 跳过该条目，不中断整个列表 | R2.8 |
| 下载中断 | 删除不完整临时文件，状态回退为未下载 | R4.8 |
| 上传失败 | Toast 提示，不影响其他功能 | R7.5 |
| 播放失败（格式不支持/网络断） | `onError` 回调 → 提示并跳过到下一首 | R5.7 |
| 超时 | connect 15s / read 60s，超时按网络错误处理 | N1 |

---

## 7. 单元测试策略

### 7.1 可测试性设计

第一版不做依赖注入，但通过**纯函数抽取**和**接口隔离**保留可测性：

| 可测单元 | 测试方式 |
|---------|---------|
| `FileUtils` 的扩展名判定 | 纯函数，输入字符串 → 断言布尔值，无需 Android 环境 |
| `FileUtils` 的大小/时长格式化 | 纯函数，覆盖边界值（0、负数、超大值） |
| `WebDAVClient` 的 XML 解析 | 把解析逻辑做成接收 XML 字符串的方法，用样例 XML 断言解析结果 |
| `LocalCacheManager` 的 JSON 序列化 | 构造 `List<WebDAVFile>` → 序列化 → 反序列化 → 断言字段一致 |
| `NetworkUtils` 的判断逻辑 | 依赖 `Context`，需 Robolectric 或仪器测试 |

**关键重构建议**：`WebDAVClient.listFolder()` 中把"HTTP 请求"与"XML 解析"分开。解析部分应为独立方法（如 `parseResponse(String xml)`），这样测试无需真实服务器。

### 7.2 测试用例清单

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

**`LocalCacheManagerTest`（需 Robolectric 或仪器测试）**

| 用例 | 说明 |
|------|------|
| 快照存取往返 | `saveSnapshot` → `loadSnapshot` 字段一致 |
| 快照不含本地状态 | 序列化结果中不含 `downloadState` / `localPath` |
| 下载标记持久化 | `markFileDownloaded` 后 `isDownloaded` 返回 true |
| 删除后状态清除 | `removeDownloadedFile` 后 `isDownloaded` 返回 false |
| 不存在路径查询 | `getLocalFile("不存在")` 返回 null |
| 快照覆盖更新 | 重复 `saveSnapshot` 同路径 → 内容被覆盖而非追加 |

**`MusicPlayerTest`（需仪器测试，或抽出决策逻辑做纯函数测试）**

| 用例 | 说明 |
|------|------|
| 播放源选择 | 已下载 → 本地路径；未下载 → 在线 URL |
| 播放列表索引 | `setPlaylist(list, 3)` → 当前曲目为第 4 项 |
| 下一首边界 | 最后一首时 `playNext()` → 行为符合设计（循环或停止） |
| 上一首边界 | 第一首时 `playPrevious()` → 行为符合设计 |
| 错误回调 | 设置无效数据源 → 触发 `onError` |

**`NetworkUtilsTest`（需 Robolectric）**

| 用例 | 说明 |
|------|------|
| 无网络检测 | 模拟断网 → `isNetworkConnected()` 返回 false |
| 网络状态字符串 | 离线 → 返回可读的离线描述（供 UI 显示） |

### 7.3 不在第一版覆盖的部分

| 项 | 原因 |
|----|------|
| UI 交互测试（Espresso） | 第一版优先验证核心链路，UI 测试成本高 |
| 真实 WebDAV 服务器集成测试 | 需要稳定测试环境与凭据，改用 MockWebServer 后续补充 |
| MediaPlayer 真实解码测试 | 依赖音频文件与设备能力，手工验证 |
| 性能测试 | 数据量小，暂不需要 |

### 7.4 建议的测试依赖

```gradle
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.robolectric:robolectric:4.11.1'   // 需要 Context 的场景
testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'  // 后续补网络层测试
androidTestImplementation 'androidx.test.ext:junit:1.1.5'
androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
```

> 当前 `app/build.gradle` 已含 JUnit 与 Espresso；Robolectric 与 MockWebServer 为建议新增项，第一版可先跑纯 JUnit 测试。

---

## 8. 已知技术风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| WebDAV 服务器实现差异 | PROPFIND 响应结构不一 | 兼容大小写命名空间；解析失败时跳过单条而非整体失败 |
| 在线流式播放需 `Authorization` 头 | 若服务器重定向到 CDN，headers 可能丢失 | 已实测目标服务器支持 Range 与 302 回源，需真机验证 |
| MediaPlayer 对流式的缓冲 | 弱网下可能卡顿 | 提示用户下载后离线播放（这也是产品设计的核心动机） |
| 快照与真实内容不一致 | 离线看到已删除的文件 | 快照带 `lastModified`；在线时以服务器结果为准并覆盖快照 |
| Android 后台限制 | 第一版未做前台服务，切后台可能停止播放 | 已列入范围外，后续版本补前台服务 |
| 大量文件时列表卡顿 | 目录含数百音频 | RecyclerView 复用 + 局部刷新（`updateDownloadState`）而非整表重绘 |
