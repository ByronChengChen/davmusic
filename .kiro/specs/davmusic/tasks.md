# Tasks — davmusic

> v1.68 已验收增补：歌词页 AB 复读功能（底部弹窗、按歌曲保存/选择/删除区间、开始/停止、Material Design 3 语义浅深色配色、按钮与区间行至少 8dp 间距）。详细任务及验收证据见 [AB repeat](../ab-repeat/tasks.md)。已验收 APK 提交 `13f2b6a`，CI run [43554497840](https://github.com/ByronChengChen/davmusic/actions/runs/43554497840)：158 项测试、0 失败；用户确认复读功能真机测试无问题。

> Android WebDAV 音乐播放器 · 实施任务清单
>
> 状态标记：`[ ]` 未开始 · `[-]` 进行中 · `[x]` 已完成
>
> ⚠️ **动手前请先读 [`AGENTS.md`](../../../AGENTS.md)** —— 那是本项目的**架构决策记录**，
> 记录维护者明确定下的取舍，尤其注意标记 **⛔ 不要加回来** 的已废弃方案
> （如「全局活动服务器 + 切换清缓存」、「失败计数/退避重试」）。
> 本文档描述**做过/要做什么**；`AGENTS.md` 描述**为什么这样设计、什么不能改**。两者互补，冲突时以 `AGENTS.md` 为准。
>
> 任务来源：Kiro `kiro_planner` agent 生成的原始计划（10 项），已按实际实现状态标注。
>
> **修订记录**：任务 1–9 为初版脚手架产出；任务 10 已跑通（CI 可出包）；
> 新增任务 12–21 覆盖 v1.18 → v1.30 的实际迭代（多服务器、前台服务、离线索引等）；
> 任务 22 覆盖 v1.39 播放模式；**任务 23–25 覆盖 v1.32 → v1.38 的写操作与上报鉴权**（2026-10-09 补记）；
> 任务 26 覆盖 v1.42 排序方式与真实时间解析。
> 任务 27 覆盖 v1.44–v1.45 全屏歌词页（动态滚动）；任务 28 覆盖 v1.46 歌词搜索与上传；
> 任务 29 覆盖 v1.47 歌词页播放控制条与双击定位。
> 任务 30 覆盖 v1.48 系统媒体控制中心 seek；任务 31 覆盖 v1.49–v1.50 深色模式与列表文字修复。
> 2026-11-01 修订：核对 `fba3714`（versionCode 150 / versionName 1.50），补齐任务 30–31、
> 总览/依赖/状态摘要，纠正封面与列表可见性、活动服务器、快照字段、缓存位置及测试数量的旧描述。
> v1.50 用户已持续实机使用并确认正常；当前工作区为 **v1.52 CI 交付预备（29 个生产 Java 文件 / 11485 物理行）**，任务 32 的本地纯 JVM 验证通过，CI / 真机待验收。

---

## 任务总览

| # | 任务 | 状态 | 对应需求 | 产出文件 |
|---|------|------|---------|---------|
| 1 | 项目基础搭建与服务器配置 | `[x]` | R1.1–R1.6 | 构建配置 + `ServerConfigActivity` |
| 2 | WebDAV 客户端核心功能 | `[x]` | R2.1, R2.9, N1 | `WebDAVClient` |
| 3 | 数据模型与工具类 | `[x]` | R3.1, R3.5 | `WebDAVFile`, `FileUtils`, `NetworkUtils` |
| 4 | 文件夹浏览 UI | `[x]` | R2.2–R2.8 | `MainActivity`, `FileListAdapter` |
| 5 | 音乐文件识别与列表展示 | `[x]` | R3.2–R3.4 | `FileListAdapter`, `item_file.xml` |
| 6 | 下载管理系统 | `[-]` | R4.1–R4.8 | 下载可用；实时百分比及失败残留清理未实现 |
| 7 | 播放器核心功能 | `[x]` | R5.1–R5.7 | `MusicPlayer` + 迷你播放器 |
| 8 | 离线快照机制 | `[-]` | R6.1–R6.6 | 快照存取可用；离线读取键未统一 |
| 9 | 上传功能 | `[x]` | R7.1–R7.5, R8.1 | `WebDAVClient.upload` + 上传入口 |
| 10 | 构建流水线与产物验证 | `[x]` | — | `.github/workflows/build.yml` |
| 11 | 单元测试补充 | `[-]` | 见 `design.md` §8 | 9 个测试类 / 97 个用例，仍待补播放器与网络测试 |
| **12** | **播放器前台服务与媒体控制** | `[x]` | R9.1–R9.8 | `MusicService` |
| **13** | **多服务器管理** | `[-]` | R1.5, R1.7–R1.10 | 管理已实现；旧称删除服务器自动清缓存未实现 |
| **14** | **服务器列表即根目录（三层导航）** | `[x]` | R2.0, R2.4, R2.4.1 | `MainActivity` |
| **15** | **本地离线索引与目录树重建** | `[-]` | R6.7–R6.9 | 索引与下载映射键不一致，删除同步未接通 |
| **16** | **定位到当前歌曲** | `[-]` | R10.1–R10.3 | 当前列表内滚动已实现；跨目录跳转未实现 |
| **17** | **远程日志上报** | `[x]` | N9 | `RemoteLogger` |
| **18** | **请求节流与内存缓存** | `[x]` | N7, N8 | `MainActivity` 目录内存缓存与 429 提示 |
| **19** | **固定签名（可覆盖安装保留数据）** | `[x]` | — | `app/build.gradle` keystore 配置 |
| **20** | **播放健壮性修复（缓冲/切歌/续播）** | `[x]` | R5.9, R5.10, R9.6 | `MusicPlayer`, `MusicService` |
| **21** | **列表与离线渲染修复** | `[x]` | R2.5, R6.10 | `MainActivity`, `FileListAdapter` |
| **22** | **播放模式（顺序 / 列表循环 / 单曲循环 / 随机）** | `[x]` | R5.11–R5.14 | `MusicPlayer`, `MainActivity`, 4 个矢量图标 |
| **23** | **远端新建文件夹（MKCOL）** | `[x]` | R11.1–R11.4 | `WebDAVClient.createFolder`, `MainActivity` |
| **24** | **左滑删除远端文件/目录（DELETE）** | `[x]` | R11.5–R11.7 | `WebDAVClient.deleteRemote`, `MainActivity` |
| **25** | **上报鉴权改 HMAC + 端点可配** | `[x]` | R12.1–R12.7 | `RemoteLogger` |
| **26** | **排序方式（修改时间 / 名称）+ 真实时间解析** | `[x]` | R13.1–R13.8 | `TrackSorter`, `HttpDateParser`, `FileListAdapter`, `MusicPlayer`, `MainActivity` |
| **27** | **全屏歌词页（动态滚动）** | `[x]` | R14.1–R14.8 | `LyricsActivity`, `LrcParser`, `LyricLineAdapter`, `WebDAVClient.fetchBytes`, `MainActivity` |
| **28** | **歌词搜索（LRCLIB）与上传** | `[x]` | R14.9–R14.11 | `LyricsSearchClient`, `LyricsQuery`, `WebDAVClient.putBytes`, `LyricsActivity` |
| **29** | **歌词页播放控制条 + 双击定位** | `[x]` | R14.12–R14.13 | `view_player_bar`, `PlayerBarController`, `LrcParser.indexAt`, `LyricsActivity` |
| **30** | **系统媒体控制中心进度拖动（v1.48）** | `[x]` | R9.9 | `MusicService.onSeekTo` / `syncPlaybackState` |
| **31** | **系统深色模式与列表文字修复（v1.49–v1.50）** | `[x]` | R15.1–R15.4 | `values-night/colors.xml`, 主题/图标, `FileListAdapter` |
| **32** | **目录快照原子写入（v1.51 预备）** | `[-]` | R6.11 | 原子写入与本地纯 JVM 已验证；CI / 真机待验收 |
| **33** | **本地快照跨服务器歌曲搜索播放** | `[-]` | R16.1–R16.5 | CI 通过；真机待验收 |
| **34** | **搜索点击歌曲历史 v1.56** | `[-]` | R17 | Store + SearchActivity widget 测试与实现；真机待验收 |
| **35** | **返回父目录位置恢复与历史行紧凑布局 v1.57** | `[-]` | R18 | `MainActivity` 祖先滚动状态栈；`item_search_result` 横向行；CI / 真机待验收 |

> **说明**：任务 1–9 由 Kiro 执行 agent 一次完成（9 个 Java 文件 / 2783 行）。
> 任务 12–21 是后续在真机验证中迭代出来的实际工作，原 spec 未记录，本次补入。
> 任务 11（单元测试）已部分完成：v1.43 接入 CI，初始 6 类 / 62 例；截至 v1.50，
> 源码共有 9 类 / 97 个 `@Test`（含后续歌词测试）；`MusicPlayerTest` / `NetworkUtilsTest` 仍待补。
> 本次只做文档与源码核对，未重跑云端 CI；真机状态来自用户持续使用确认，不冒充本次测试执行。

---

## 任务 1 — 项目基础搭建与服务器配置 `[x]`

**目标**：创建 Android 项目骨架，实现服务器配置界面与凭据存储

**实现**
- [x] 创建 Gradle 工程：AGP 8.5.2 / Gradle 8.7 / Java 17 / compileSdk 34 / minSdk 26
- [x] `gradle.properties` 含 `android.useAndroidX=true`（避免 AGP 8 构建失败）
- [x] Gradle Wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar` / `.properties`）
- [x] 引入 OkHttp 4.12.0（唯一第三方网络库）
- [x] `ServerConfigActivity`：地址 / 用户名 / 密码 / 别名四个输入框 + 测试连接 + 保存
- [x] SharedPreferences 持久化配置
- [x] `isConfigured(Context)` 静态判定，已配置则跳过本页（当前由 `ServerStore.list()` 替代）

**产出**：`build.gradle`, `app/build.gradle`, `gradle.properties`, `ServerConfigActivity.java`(244行), `activity_server_config.xml`

**测试**：`design.md` §8.2 `NetworkUtilsTest`；配置读写为手工验证项

**演示**：首次启动显示配置界面 → 输入地址凭据 → 测试连接成功 → 保存 → 进入主界面

**历史问题（已解决）**：初版 wrapper jar 为 0 字节；任务 10 已替换，当前为 43453 字节，不再是待修阻塞项。

---

## 任务 2 — WebDAV 客户端核心功能 `[x]`

**目标**：实现 PROPFIND 请求、XML 解析、Basic Auth 认证

**实现**
- [x] `WebDAVClient.forServer(profile)` 按服务器创建实例并绑定凭据；底层共享 OkHttpClient。`getInstance()` 仅为遗留兼容入口，不是当前多服务器架构
- [x] `listFolder(path, callback)`：PROPFIND + `Depth: 1`
- [x] XML 解析提取 `href` / `getcontentlength` / `getlastmodified` / `getcontenttype` / `getetag` / `resourcetype`
- [x] Basic Auth 请求头
- [x] 超时配置：connect 15s / read 60s
- [x] `testConnection()` 供配置页调用
- [x] 过滤 `.DS_Store` 与 `._` 前缀文件

**产出**：`WebDAVClient.java`(839行)

**测试**：`WebDAVResponseParserTest`（13 个用例，含中文路径与命名空间变体）

**演示**：配置页"测试连接"成功获取根目录文件列表

---

## 任务 3 — 数据模型与工具类 `[x]`

**目标**：定义核心模型，提供文件类型判定与格式化

**实现**
- [x] `WebDAVFile`：`href` / `displayName` / `contentLength` / `contentType` / `lastModified` / `etag` / `isCollection` / `downloadState` / `localPath`
- [x] `DownloadState` 枚举：`NOT_DOWNLOADED` / `DOWNLOADING` / `DOWNLOADED`
- [x] `FileUtils`：`isAudioFile` / `isImageFile` / `isLyricsFile` / `formatFileSize` / `formatDuration` / 路径操作
- [x] `NetworkUtils`：连通性检测 / 网络类型 / `getNetworkStatusString`

**产出**：`WebDAVFile.java`(195行), `FileUtils.java`(208行), `NetworkUtils.java`(217行)

**测试**：`FileUtilsTest`（9 个用例，覆盖中文文件名与大小写不敏感）

**演示**：单元测试验证扩展名判定与格式化输出

---

## 任务 4 — 文件夹浏览 UI `[x]`

**目标**：实现文件夹树形浏览与面包屑导航

**实现**
- [x] `MainActivity` 主界面（顶部路径 / 中部列表 / 底部播放器 / 上传按钮）
- [x] `FileListAdapter`：两种 viewType（文件夹 / 文件）
- [x] 面包屑路径显示
- [x] 下拉刷新（`SwipeRefreshLayout`）
- [x] 加载状态指示
- [x] `onBackPressed()` 返回上级，根目录交由系统处理

**产出**：`MainActivity.java`(3049行), `FileListAdapter.java`(353行), `activity_main.xml`, `view_player_bar.xml`

**测试**：UI 交互为手工验证项（见 `design.md` §8.3）

**演示**：浏览文件夹结构 → 点击进入子文件夹 → 返回键回上级

---

## 任务 5 — 音乐文件识别与列表展示 `[x]`

**目标**：识别音频并展示文件信息；保留图片/歌词类型判定，但不把类型判定误记成封面关联或列表展示

**实现**
- [x] 9 种音频扩展名过滤：mp3 / m4a / flac / wav / wma / aac / ogg / ape / opus
- [x] 条目显示文件名 + 人性化大小
- [ ] **范围外 / 未实现**：封面关联（`Cover.jpg` / `cover.jpg` / `folder.jpg`）；类型工具不等于封面加载链路（R3.3）
- [x] 歌词文件类型识别（`.lrc` / `.ttml`）；v1.44 起同名 `.lrc` 展示另见任务 27
- [x] `FileListAdapter.setFiles()` 只保留文件夹与音频；图片/歌词图标分支被过滤挡住、不可达（R3.5）
- [ ] **范围外 / 未实现**：图片与歌词在目录列表分类展示；原完成标记错误，不计入当前交付范围

**产出**：`FileListAdapter.java`, `item_file.xml`, `FileUtils.java`

**测试**：`design.md` §8.2 `FileUtilsTest`

**演示**：列表只显示文件夹与音频；音频显示文件名与大小，图片/歌词不出现。

---

## 任务 6 — 下载管理系统 `[-]`

**目标**：实现下载、进度显示、状态持久化、视觉标记

**实现**
- [x] 下载按钮触发下载（OkHttp 异步 + 进度回调）
- [ ] 实时下载百分比尚未接通：MainActivity 的 onProgress 仅写日志，当前只有下载中状态与加载提示（R4.2）
- [x] 下载到 `getExternalFilesDir("music")`
- [x] 已完成条目使用 `download_state_downloaded`：浅色 `#4CAF50`，深色 `#81C784`；正在播放时优先使用播放高亮色
- [x] 未下载条目保持默认色
- [x] 下载状态持久化到 SharedPreferences（重启后仍为绿色）
- [x] 删除本地缓存 + 清除记录
- [x] 失败时清理临时文件

**产出**：下载逻辑（`MainActivity` + `WebDAVClient.download`）+ `LocalCacheManager` 下载部分 + `colors.xml`

**测试**：`design.md` §8.2 `LocalCacheManagerTest`（下载标记部分）

**演示**：点击下载 → 进度条推进 → 完成后文字变绿 → 重启应用仍为绿色

---

## 任务 7 — 播放器核心功能 `[x]`

**目标**：实现在线/离线播放与播放控制

**实现**
- [x] `MusicPlayer` 单例封装 `MediaPlayer`
- [x] **播放源自动选择**：已下载 → 本地文件；未下载 → 在线 URL
- [x] 在线播放携带 `Authorization` 头（`setDataSource(Context, Uri, headers)`）
- [x] 播放列表（当前文件夹音频）+ 当前索引
- [x] 播放 / 暂停 / 上一首 / 下一首 / 进度拖动
- [x] `Handler` 每 1000ms 回调更新进度；歌词滚动另用 80ms 轮询（任务 27）
- [x] 迷你播放器 UI
- [x] `OnPlaybackListener` 状态回调

**产出**：`MusicPlayer.java`(1480行), `view_player_bar.xml`

**测试**：`design.md` §8.2 `MusicPlayerTest`

**演示**：点击音频播放 → 底部显示迷你播放器 → 控制播放/暂停/切歌；断网后已下载的音乐仍可播放

---

## 任务 8 — 离线快照机制 `[-]`

**目标**：实现文件夹快照与离线浏览（**核心需求**）

**实现**
- [x] 每次成功列出文件夹后自动保存 JSON 快照
- [x] `loadSnapshot(path)` 读取快照
- [x] `hasSnapshot(path)` 判定是否存在
- [ ] **离线降级链未完整接通**：在线快照按 serverId:路径保存，showLocalDownloads 仍按裸路径读取（R1.10 / R6.2）
- [x] 网络状态检测与 UI 标识
- [x] 当前快照实际序列化 `downloadState` / `localPath` / `serverId`（`LocalCacheManager.fileToJson`）；渲染前由 `MainActivity.updateFileDownloadStates()` 按本地文件存在性校正，不把历史设计当现状
- [x] 在线访问时以服务器结果覆盖旧快照

**产出**：`LocalCacheManager.java`(679行) 快照部分 + `NetworkUtils.java` + `MainActivity.loadCurrentPath()`

**测试**：`LocalCacheManagerTest`（快照往返包含本地状态、空目录、覆盖、损坏降级、隔离）；并非“不含本地状态”。

**演示**：打开飞行模式 → 仍可浏览曾访问过的文件夹 → 播放已下载音乐

---

## 任务 9 — 上传功能 `[x]`

**目标**：实现音频文件上传到 WebDAV 服务器

**实现**
- [x] 上传按钮（浮动按钮）
- [x] 系统文件选择器（`Intent`）
- [x] `WebDAVClient.upload()`：`PUT` 请求 + 进度回调
- [x] 上传进度显示
- [x] 成功后自动刷新列表
- [x] 失败提示，不影响其他功能
- [x] 格式验证：非音频格式拒绝上传

**产出**：`WebDAVClient.upload()` + `MainActivity` 上传入口

**测试**：手工验证（需真实服务器）

**演示**：点击上传 → 选择 MP3 → 显示进度 → 成功后文件出现在列表

---

## 任务 10 — 构建流水线与产物验证 `[x]`

**目标**：通过 GitHub Actions 云端构建出可安装的 APK

**实现**
- [x] 创建 `.github/workflows/build.yml`
  - `ubuntu-latest` + JDK 17 (temurin) + `android-actions/setup-android@v3`
  - `gradle/actions/setup-gradle@v4` + `./gradlew assembleDebug --no-daemon`
  - `upload-artifact@v4`（`name: davmusic-debug-apk`，`if-no-files-found: error`）
- [x] 创建 `.gitignore`（含 `*.jar` 规则 + `!gradle/wrapper/gradle-wrapper.jar` 例外）
- [x] 修复 `gradle-wrapper.jar`（0 字节占位 → 有效版本）
- [x] 适配 SDK 安装：`sdkmanager` 已移除 `tools` 包，工作流不再尝试安装该包
- [x] 创建 GitHub 仓库并推送
- [x] 触发构建并等待成功
- [x] 下载 APK 并验证（`file` / 结构 / MD5）

**产出**：`.github/workflows/build.yml`, `.gitignore`

**测试**：云端构建成功 = 集成验证通过

**演示**：actions 页面显示绿色 ✓ → 下载 artifact → 真机安装运行

**已知风险**：agent 生成的 Android 脚手架通常需要 2–4 轮云端构建才能通过。已识别的历史失败模式（均在预推送清单中覆盖）：
1. 缺 `gradle.properties` → `checkDebugAarMetadata` 失败
2. wrapper 不完整或 `gradle-wrapper.jar` 为空
3. `.gitignore` 的 `*.jar` 吞掉 wrapper jar
4. 手写 `gradlew` 缺 `JAVA_HOME` 解析 → `exit 127`
5. 自适应图标把 `<color>` 引用为 `@drawable/`
6. 引用了不存在的 `proguard-rules.pro`
7. Java 文件缺 `import` → `cannot find symbol`
8. `sdkmanager` 移除 `tools` 包导致 SDK 安装失败（已修复）

**补充约定（后续迭代形成）**：
- 本机无 JDK/SDK → 一律走云端构建，不在服务器上试跑 `./gradlew`
- 使用**固定 keystore** 签名，支持覆盖安装并保留数据（任务 19）
- 每次交付**递增 `versionName` / `versionCode`**，关于页显示版本号供用户确认装的是哪一版
- 真机验证通过后才打 tag

---

## 任务 11 — 单元测试补充 `[-]` 部分完成（截至 v1.50）

**目标**：按 `design.md` §8 实现测试用例

**实现（已完成）**
- [x] `HttpDateParserTest`：10 个用例（RFC 1123 / RFC 850 / asctime / ISO 变体 → 同一时刻；
      1970、尾部垃圾、纯日期 → 未知）
- [x] `TrackSorterTest`：11 个用例（自然序、补零同值由后续文本决定、时间降序、
      文件夹置顶、未知时间沉底、同名同时间按路径/服务器定序、null 元素）
- [x] `FileUtilsTest`：9 个用例（扩展名判定、大小/时长格式化、路径操作）
- [x] `WebDAVResponseParserTest`：13 个用例（中文解码、`+` 不被当空格、目录自身剔除、
      文件夹判定、属性解析、**时间来自服务器而非 now**、系统文件过滤、完整 URL href）
- [x] `LocalCacheManagerTest`：7 个用例（快照往返保真、空目录、覆盖写、删除、
      损坏 JSON 降级、目录间隔离）—— Robolectric
- [x] `ServerStoreTest`：12 个用例（首台自动活动、活动指针回退、别名唯一性、
      JSON 往返、scopeId、旧版单服务器配置迁移）—— Robolectric
- [x] 重构：XML 解析已抽成独立的 `WebDAVResponseParser`（纯 JVM，不依赖 OkHttp/Context）

**仍未完成**
- [ ] `MusicPlayerTest`：7 个用例（播放源选择、列表边界、切歌不误报）。
      需要 ShadowMediaPlayer 与音频焦点模拟，风险高于收益，留待后续
- [ ] `NetworkUtilsTest`：2 个用例（网络检测）。需要 ConnectivityManager shadow

**前置条件**：已引入 `org.robolectric:robolectric:4.13` 与
`testOptions.unitTests.includeAndroidResources`；MockWebServer 未引入（当前用例不触网）

**后续补充（v1.44–v1.47）**
- [x] `LrcParserTest`：21 个用例（解析/编码/歌手提取/路径/当前行二分定位）
- [x] `LyricsQueryTest`：6 个用例；`LyricsSearchClientTest`：8 个用例

**产出**：`app/src/test/java/com/byron/davmusic/*Test.java`（9 个类 / 97 个 `@Test`；源码静态计数，不等同于本次运行结果）

**演示**：CI 步骤 `./gradlew testDebugUnitTest` 全部通过；红了不出 APK

---

## 任务 12 — 播放器前台服务与媒体控制 `[x]`

**目标**：让播放不因切后台/锁屏而中断，并接入系统媒体控制

**实现**
- [x] `MusicService`：`startForegroundService()` 启动，`onCreate` 内 5 秒内调用 `startForeground`
- [x] 通知渠道 `IMPORTANCE_LOW`（不响铃不震动）+ `VISIBILITY_PUBLIC`（锁屏可见）
- [x] `NotificationCompat.MediaStyle` 媒体通知（曲目信息 + 播放/暂停/上下首）
- [x] **`MediaSessionCompat`（androidx）** —— 平台版 `Token` 与 androidx MediaStyle 类型不兼容
- [x] 耳机/线控按键映射：单击播放暂停、双击下一首、三击上一首
- [x] **每秒刷新 `PlaybackState`** —— position 是静态快照，不刷新则系统进度条停住
- [x] 播完自动续播下一首（锁屏/后台同样成立）
- [x] `WAKE_LOCK` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限
- [x] `stopSelf()` 释放

**产出**：`MusicService.java`(630行) + `AndroidManifest.xml` service 声明

**测试**：真机手工验证（锁屏控制、耳机按键、后台续播）

**演示**：播放中锁屏 → 通知栏/锁屏出现媒体控制 → 双击耳机切歌 → 播完自动下一首

**踩坑记录**：
- `MediaSessionCompat` 包路径为 `android.support.v4.media.session`（旧版命名空间），不是 `androidx.media.session`

---

## 任务 13 — 多服务器管理 `[-]`

**目标**：支持配置多台 WebDAV 服务器，并隔离各自的缓存

**实现**
- [x] `ServerProfile`：id / 别名 / url / 凭据 + `getScopeId()` / `getDisplayName()` / JSON 往返
- [x] `ServerStore`：`list` / `getActive` / `add` / `update` / `setActive` / `remove` / `isAliasTaken`
- [x] **`pickActive()` 回退**：activeId 失配则退回第一台，避免跳回配置页
- [x] `ServerManageActivity`：服务器列表 + 新增/编辑/删除/进入；不要求全局“设为活动”
- [x] `ServerListAdapter`：展示服务器别名 / 主机，点击即进入（方案 A）
- [x] 当前条目 `serverId` + 相对路径组成 `getCacheKey()`（`serverId:relativePath`），防跨服务器串扰；`getScopeId()` 仅为遗留兼容
- [ ] **旧完成标记纠正**：删除服务器时自动清理对应缓存/快照未实现；`ServerManageActivity.doDelete` → `ServerStore.remove` 只删配置并修正兼容活动指针，没有缓存清理调用
- [x] 旧版单服务器 key（`server_url` 等）兼容读取

**产出**：`ServerProfile.java`(156行), `ServerStore.java`(292行), `ServerManageActivity.java`(310行), `ServerListAdapter.java`(126行)

**测试**：`design.md` §8.2 `ServerProfileTest` / `ServerStoreTest`

**演示**：新增两台服务器 → 根层级分别进入 → 各自浏览互不干扰；`getActive/setActive/pickActive` 保留仅兼容，不恢复“活动服务器 + 切换清缓存”。

---

## 任务 14 — 服务器列表即根目录（三层导航）`[x]`

**目标**：把服务器列表提升为最顶层，形成"服务器 → 目录 → 子目录"三级导航

**实现**
- [x] `currentServer == null` 即处于根层级，列表显示服务器（`ServerListAdapter`）
- [x] 进入某台服务器后以其 WebDAV 根为当前路径，切回 `FileListAdapter`
- [x] **`onBackPressed()` 三层回退**：非根目录→上级；服务器根→服务器列表；服务器列表→退出
- [x] 顶部返回按钮：处于服务器内（含其根）时显示
- [x] 面包屑显示服务器名 + 路径
- [x] **请求期固化 `serverAtRequest`**：防止请求途中切服务器把结果写进错误作用域

**产出**：`MainActivity.java` 导航部分 + `activity_main.xml`, `activity_server_list.xml`

**测试**：真机手工验证（三层返回、面包屑跳转、切服务器）

**演示**：启动 → 服务器列表 → 进入 A → 进子目录 → 返回三次回到列表 → 退出

**踩坑记录**：
- 修复前 `pathStack` 为空时直接 `super.onBackPressed()` 退出 App，跳过了"服务器列表"这一层（v1.30 修复）
- 匿名回调里取 Color 需写 `MainActivity.this`，`this` 指向回调对象会类型不匹配

---

## 任务 15 — 本地离线索引与目录树重建 `[-]`

**目标**：离线时不只靠快照，已下载文件也能独立构成可浏览的目录树

**实现**
- [x] `indexOfflineFile(remotePath, displayName, size)`：下载成功即写索引
- [ ] `removeOfflineIndex(...)` 虽存在，但 removeDownloadedFile 未调用，删除本地文件不保证索引同步清理
- [x] `listLocalTree(currentPath)`：按远程路径归组重建目录树
- [x] `getOfflineFileCount()`：离线文件计数（关于页展示）
- [ ] 离线链路完整性：下载映射用 getCacheKey()、离线索引用 href，listLocalTree 查映射时键不一致，且未按服务器过滤（R6.7–R6.8）

**产出**：`LocalCacheManager.java`(679行) 索引部分

**测试**：现有 LocalCacheManagerTest 仅覆盖快照；目录树重建与索引同步尚无自动化测试。

**待验证目标**：下载后断网重建目录树；当前受上述键不一致限制，不记为已验收。

---

## 任务 16 — 定位到当前歌曲 `[-]`

**目标**：定位当前播放曲目；当前仅支持在可见目录列表内滚动，不自动跳转目录

**实现**
- [x] "更多"菜单 → "定位到当前歌曲"
- [x] 用 `MusicPlayer.isSameTrack` 在当前 `FileListAdapter.getFiles()` 内查找，命中后 `smoothScrollToPosition`；播放高亮由适配器维持
- [x] 无曲目提示；列表未就绪提示；未命中则以 Toast 显示带服务器别名的完整曲目路径
- [ ] **未实现**：自动进入歌曲所属目录/服务器再滚动。证据：`MainActivity.locateCurrentTrack()`（2824–2867 行）无 `enterServer` / `loadFolder` / 导航调用，未命中仅调用 `trackFullPath` 与 Toast；原完成描述错误

**产出**：`MainActivity.locateCurrentTrack()`

**测试**：真机手工验证

**演示**：播放某曲 → 在同目录滚动到别处 → 菜单定位 → 滚回该条目；换目录后定位仅提示完整路径，不自动跳回。

---

## 任务 17 — 远程日志上报 `[x]`

**目标**：在无法连 IDE 的真机场景下回收日志定位问题

**实现**
- [x] `RemoteLogger`：`i` / `w` / `e` 分级方法，带 TAG
- [x] 本地缓冲 + 批量上报，避免频繁网络请求
- [x] 覆盖播放/服务/媒体会话关键路径

**产出**：`RemoteLogger.java`(565行)

**测试**：手工验证（日志可在服务端查看）

**演示**：复现锁屏切歌问题 → 从远端日志中读到根因

**价值实证**：任务 20、21 的多个"真因"都是靠远程日志定位的，而非猜测修改。

---

## 任务 18 — 请求节流与内存缓存 `[x]`

**目标**：降低请求量，同时避免与服务器限流机制冲突

**实现**
- [x] 内存级缓存减少短时间重复请求（v1.23）
- [x] 遇 429 仅向用户提示（v1.24）
- [x] **移除失败计数与自动退避重试** —— 与服务器限制叠加会自锁

**产出**：`MainActivity.java` 的 `dirCache` / `dirCacheTime` / `DIR_CACHE_TTL_MS`（5 分钟，键含服务器）与 429 提示；不在 `WebDAVClient` 内另建退避层

**测试**：真机手工验证（观察请求量与 429 表现）

**演示**：快速来回切换目录 → 请求量显著下降且不触发封禁

**决策记录**：这是一次**减法**。v1.23 引入的失败计数+退避在真机上造成"自锁"（本地认为在退避、服务器认为无请求），v1.24 回退为只保留内存缓存 + 提示。参见 `AGENTS.md` 的架构约定。

---

## 任务 19 — 固定签名（可覆盖安装保留数据）`[x]`

**目标**：升级安装时不丢数据

**实现**
- [x] `app/build.gradle` 配置固定 keystore
- [x] keystore 路径用 `rootProject.file()` 解析（app 模块相对路径会失败）
- [x] CI 显式引用仓库已跟踪的 `keystore/davmusic.p12` 固定 debug 签名；不是“keystore 已被排除”，勿改成 runner 临时生成的签名

**产出**：签名配置

**测试**：真机验证（装新版后数据仍在）

**演示**：安装 v1.29 后直接覆盖安装 v1.30，配置与下载记录保留

---

## 任务 20 — 播放健壮性修复 `[x]`

**目标**：解决真机上暴露的播放稳定性问题

**实现**
- [x] 预加载失效（被自己销毁）→ 切歌永远走不通
- [x] 切歌后新曲未开始播放
- [x] 锁屏/后台播完不自动切下一首
- [x] 缓冲期崩溃
- [x] 播放中条目高亮
- [x] 消除切歌时的误报错误（v1.26）
- [x] 播放时间被布局裁掉（容器高度不足，v1.27）
- [x] 蓝牙耳机按键控制（v1.22）
- [x] 主 Looper Handler 调度回调；没有独立播放线程或所有 public 方法统一串行封送（R9.7）

**产出**：`MusicPlayer.java`(1480行), `MusicService.java`

**测试**：真机长时验证

**演示**：锁屏播放整张专辑 → 每首自动续播、无误报、耳机可控

---

## 任务 21 — 列表与离线渲染修复 `[x]`

**目标**：解决列表渲染与离线切换的显示问题

**实现**
- [x] **ViewHolder 复用残留导致列表渲染错乱**（v1.21 真因）
- [x] **离线返回后目录看似消失 —— 实为滚动位置残留**（v1.20 真因）
- [x] 异步渲染竞态导致列表闪烁/条目时有时无
- [x] 离线模式显示完整目录
- [x] 网络恢复后自动切回在线
- [x] 前后台切换后回到首页（Activity 重建未恢复导航状态）
- [x] 同名文件夹重复显示
- [x] 进度条无法拖动
- [x] 在线播放失败（URL 未编码 + href/相对路径混用）
- [x] 路径解析：href 前缀剥离失败导致路径重复与 404

**产出**：`MainActivity.java`, `FileListAdapter.java`(353行)

**测试**：真机手工验证

**演示**：飞行模式反复进出目录 → 列表稳定、位置正确、恢复网络后自动刷新

**方法论**：这些问题全部是**先收真机日志再改**定位到的，不是凭猜测修改。

---

## 任务 22 — 播放模式（顺序 / 列表循环 / 单曲循环 / 随机）`[x]`

**目标**：把 README 里那句"随机/循环"变成真正可切换、可记住、即时生效的功能（R5.11–R5.14）

**背景**：v1.30 的 README 写有"随机/循环"，但代码里**从未实现过** ——
`playNext()` 只有 `(pos + 1) % size`，即"顺序播放 + 播完回到开头"，没有任何可切换的模式；
requirements 曾把「播放模式」列在范围外。这次是补做，不是恢复被删的功能。

**实现**
- [x] 四个模式：`MODE_SEQUENTIAL` / `MODE_LIST_LOOP`（默认）/ `MODE_SINGLE_LOOP` / `MODE_SHUFFLE`
- [x] `nextIndexOf(mode, pos, size, userInitiated)` 抽成**纯静态函数**，预加载与播完切歌共用同一套决策
- [x] 单曲循环走 `MediaPlayer.setLooping(true)`：native 无缝重播，循环点不重新取流
- [x] 切模式立即生效：`setNextMediaPlayer(null)` 取消旧目标的预加载 → 按新模式重挂
      （目标未变则不重挂，避免白白多拉一路流）
- [x] 预加载记下标 `preloadedPosition`：播完按它推进索引（随机模式下不能用 +1）
- [x] 在途预加载用 `preloadGeneration` 作废，避免旧目标的 `onPrepared` 覆盖新目标
- [x] 取消预加载后不当场 `release()`，记入 `pendingReleasePlayer` 待安全点回收
- [x] 模式持久化（`player_prefs` / `play_mode`）；默认列表循环＝改动前的既有行为
- [x] 迷你播放器新增播放模式按钮（4 个图标随模式切换 + Toast 提示 + contentDescription）
- [x] `OnPlaybackListener.onPlayModeChanged`，MainActivity 与 MusicService 均已实现
- [x] README 的不实描述改为与实现一致；spec 的「范围外」条目替换为真正未做的部分

**产出**：`MusicPlayer.java`, `MainActivity.java`, `MusicService.java`, `activity_main.xml`,
`strings.xml`, `ic_sequential/ic_repeat/ic_repeat_one/ic_shuffle.xml`（新增 4 个矢量图标）

**测试**：`nextIndexOf()` 是纯函数，可直接用普通 JUnit 覆盖（用例清单见 design.md 8.2）；
但真实的接续行为（取消/重挂预加载、`setLooping` 无缝重播）**只能真机验证**

**演示**：播放一首 → 切到单曲循环 → 该曲无缝重播；切到顺序播放 → 放到最后一首停止；
切到随机 → 每次换歌都不同；重启 App → 模式仍在

---

## 任务 23 — 远端新建文件夹（MKCOL）`[x]`

**目标**：在 App 内直接在当前 WebDAV 目录下建文件夹，无需外部客户端（R11.1–R11.4）

**实现**
- [x] `WebDAVClient.createFolder(remotePath, cb)`：`MKCOL` + 空 `Content-Length: 0` 请求体
- [x] 右上角菜单项「新建文件夹」，**仅在已进入某台服务器时可见**（停在服务器列表根层级时没有落点）
- [x] 菜单可见性用缓存的 `menuInServerState` + `invalidateOptionsMenu()` 对齐，
      否则从服务器退回列表后该项会残留（与上传按钮同一个前置条件）
- [x] 前置校验：离线模式拒绝、未进入服务器拒绝、本地已知列表已有同名条目时直接提示
- [x] 失败按状态码给可排障提示：`405` 同名已存在/服务端不支持、`409` 父目录不存在、`415` 不接受请求体
- [x] **空路径防御**：退化成对服务器根目录 MKCOL 的请求在入口被挡
- [x] **响应方法校验**：确认 `response.request().method()` 仍是 `MKCOL`，2xx 不足以证明建成

**产出**：`WebDAVClient.java`, `MainActivity.java`

**测试**：真机手工验证（新建成功、重名、离线、无权限四种路径）

**演示**：进入服务器 → 右上角新建文件夹 → 服务器上立刻出现该目录

---

## 任务 24 — 左滑删除远端文件/目录（DELETE）`[x]`

**目标**：在列表里直接删远端内容（R11.5–R11.7）

**实现**
- [x] `WebDAVClient.deleteRemote(remotePath, isCollection, cb)`：`DELETE`，目录由服务端递归删除
- [x] `ItemTouchHelper` 左滑手势 + 条目背后红底「删除」，滑动时给出明确预期
- [x] 选择左滑而非再加行内按钮：行内已有下载状态与进度条，再加按钮会放大误触
- [x] 目标服务器取自**条目自带的 `serverId`**，不查全局状态（与方案 A 一致）
- [x] 删除失败按 `401` / `403` / `429` 给可排障提示（这是与新建文件夹同源的病因组）
- [x] 与本地删除区分：行内删除按钮只删本地副本，左滑才作用于远端，两者互不代偿
- [x] **空路径防御** + **响应方法校验**（重定向会把 `DELETE` 改写成 `GET`，301 到带斜杠地址时尤甚）

**产出**：`WebDAVClient.java`, `MainActivity.java`

**测试**：真机手工验证（删文件、删目录、无权限、离线）

**演示**：左滑一个远端目录 → 确认 → 列表中消失，服务器上也确实没了

---

## 任务 25 — 上报鉴权改 HMAC + 端点可配 `[x]`

**目标**：把日志上报从「明文凭据 + 硬编码端点」改成「用户配置端点 + 签名鉴权」（R12）

**背景**：这是一次**安全事故的补救**。旧实现把「用户名 + 口令」明文写成 `RemoteLogger.ENDPOINTS` 常量，
而本仓库是公开的 —— 等于把两个收集端点的凭据一起公开，认证形同虚设。

**实现**
- [x] 上报端点改为**用户在 App 内手输**（菜单 → 设置上报端点）；源码不再含任何收集端地址
- [x] 漏填协议头时自动补 `http://`（v1.37）
- [x] 未配置端点时**直接跳过上报**，不连任何默认地址
- [x] v1.33 起改为 HMAC-SHA256 签名，取代 Basic Auth；令牌由用户手输、只存私有 SharedPreferences
- [x] 签名构造：`PUT\n path \n timestamp \n nonce \n sha256hex(body)`，随 4 个 `X-Davmusic-*` 头发送
- [x] 时间窗 ±300 秒 + nonce 去重（仅验签通过的 nonce 入表）
- [x] 时钟超窗回 `401` + `X-Davmusic-Error: clock_skew`，App 明确提示「设备时间不准」，**不静默失败**
- [x] 手动上报经 `UploadCallback` 用 Toast 告知结果
- [x] v1.34 起上报成功后**精确清理**该份内容，避免重复上传
- [x] 收敛单端点（v1.32 曾是双端点互为备份）

**产出**：`RemoteLogger.java`(565行)

**测试**：真机手工验证（未配置跳过、错令牌 401、手机时间不准 clock_skew、成功后不重复上传）

**演示**：把手机时间调错 → 手动上报 → 明确提示时间不准，而不是静默无反应

**关联**：任务 17 的实现细节在本任务中大幅演进；spec 关于上报的部分以本任务 + requirements 3.12 为准。

---

## 任务 26 — 排序方式（修改时间 / 名称）+ 真实时间解析 `[x]`

**目标**：列表可按名称或按修改时间排序，默认最新在前；切换排序时正在播的歌继续播，
播放队列同步重排，并把当前曲目定位到可见区域（R13.1–R13.8）

**实现**
- [x] `HttpDateParser`：解析 `getlastmodified`（RFC 1123 / RFC 850 / asctime / ISO-8601），
      **整串消费完才算成功**，失败返回 0 —— 修掉旧代码「读出来却丢弃、改设 now()」的假时间
- [x] `TrackSorter`：列表与队列共用的唯一比较器；时间倒序、未知时间沉底、
      中文 `Collator` + 数字自然序、时间相同按名称/路径兜底
- [x] 文件夹始终置顶，组内也按当前规则排序（含按时间）
- [x] `FileListAdapter.setFiles()` 每次渲染按偏好排序（后台静默刷新不会冲掉偏好）；
      `setSortMode()` 持久化到 `player_prefs` / `file_sort_mode`
- [x] 菜单「排序方式」（仅在已进入服务器时可见），排序纯本地、不发请求
- [x] `MusicPlayer.reorderPlaylist(comparator)`：只排现有队列，按 `isSameTrack` 重定位当前索引，
      完全不碰 MediaPlayer；预加载按**曲目身份**比对，同一首只改下标不重拉流，
      不同则 `cancelPreloadedTrack()` + `prepareNextTrack()`；随机模式保留已挂载的下一首
- [x] 排序后 `post` 到下一帧，用 `scrollToPositionWithOffset(当前曲目, 视口 1/3)` 定位；
      当前曲目不在本目录时回到列表顶部

**产出**：`TrackSorter.java`(166行), `HttpDateParser.java`(62行),
`WebDAVClient.java`, `FileListAdapter.java`, `MusicPlayer.java`, `MainActivity.java`,
`res/menu/main_menu.xml`, `res/values/strings.xml`

**测试**：v1.43 起 `TrackSorterTest`（11 例）+ `HttpDateParserTest`（10 例）接入 CI；旧“待出包”状态已过期。当前 v1.50 用户持续实机使用确认正常，本次未重新执行构建。

**演示**：播放一首歌 → 切「按名称」→ 歌不断、队列顺序跟着变、列表自动滚到正在播的那首

**设计取舍**（用户确认）：最新在前 / 修改时间（不是"添加时间"，WebDAV 不提供）/
文件夹也按时间排（但始终置顶）/ 偏好全局一份

---

## 任务 27 — 全屏歌词页（v1.44） `[x]`

**目标**：点迷你播放器进入全屏页面，按播放进度滚动显示 LRC 歌词

**实现**
- [x] `LrcParser`：解析 `[mm:ss.xx]`（含一位/三位小数、`[mm:ss:xx]`）、一行多标签、
      `[offset:±N]`、忽略元信息、按时间排序；编码回退 BOM → 严格 UTF-8 → GB18030
- [x] `LrcParser.siblingLrcPath`：`歌.mp3 → 歌.lrc`（同目录同名，v1 不支持模糊匹配）
- [x] `WebDAVClient.fetchBytes`：单次 GET 到内存，不落盘/不计数/不重试
- [x] `LyricsActivity` + `activity_lyrics.xml`：深色全屏、当前行居中、返回不中断播放
- [x] `LyricLineAdapter` + `item_lyric_line.xml`：固定行高，高亮行字号/颜色变化不改变布局
- [x] 顶栏遮挡修复（v1.45）：列表上下 96dp padding + `clipToPadding=true`，居中落点按内容区计算，首行不再被歌名压住；副标题改显示 `[ar:]` 歌手
- [x] 同步：80ms 轮询 `getCurrentPosition()`（不用 1 秒一次的 `onProgress`），
      行号变化才 `smoothScrollBy`；`onStart` 启动、`onStop` 停止
- [x] 切歌跟随；过期请求按 cacheKey 丢弃；`onDestroy` 注销监听
- [x] 缓存 `filesDir/lyrics`（.tmp + rename），「清空缓存」一并清除
- [x] `MainActivity` 迷你播放器点击入口（原先只是 toast 占位）
- [x] `LrcParserTest`：19 个用例（解析/偏移/排序/UTF-8/GBK/路径）

**演示**：CI `testDebugUnitTest` 通过；真机点迷你播放器 → 歌词随播放滚动

## 任务 28 — 歌词搜索与上传（v1.46） `[x]`

**目标**：没有歌词时能搜到并选一个版本，选中后本地生效并同步到服务器

**实现**
- [x] `LyricsQuery`：文件名 → 关键词（去扩展名、去 `10 `/`第10首` 前缀；不拆歌手）
- [x] `LyricsSearchClient`：LRCLIB `/api/search`，带 UA；只收带时间轴的条目；
      排除 `instrumental=true`，按当前歌曲时长接近度排序（当前时长未知则保留服务端顺序）；429 如实提示，无应用层轮询/退避重试
- [x] `LyricsActivity`：「暂无歌词 / 加载失败」时显示「搜索歌词」按钮 →
      关键词弹窗（可编辑）→ 结果列表选择 → 应用
- [x] `WebDAVClient.putBytes`：PUT + `If-None-Match: *`，已有同名文件不覆盖
- [x] 选中后先写本地缓存并显示，再上传；上传失败只提示，不影响本地与播放
- [x] 测试：`LyricsQueryTest`（6 例，纯 JVM）、`LyricsSearchClientTest`（8 例，Robolectric）

**演示**：CI `testDebugUnitTest` 通过；真机无歌词歌 → 搜索 → 选版本 → 歌词出现且服务器多出 `.lrc`

## 任务 29 — 歌词页播放控制条 + 双击定位（v1.47） `[x]`

**目标**：在歌词页也能拖进度、切歌；双击某句从该句时间点定位

**实现**
- [x] 抽出 `layout/view_player_bar.xml`：主界面与歌词页 `<include>` 同一份布局，控件 id 不变，
      主界面代码零改动
- [x] `PlayerBarController`：进度条（拖动中屏蔽自动回写、松手 seek）、播放/暂停、上一首/下一首、
      播放模式；监听 MusicPlayer，两处状态天然同步
- [x] 歌词页改为「上方歌词（weight=1）+ 底部播放条」；歌词居中按实际可用高度计算
- [x] 双击歌词：`GestureDetector` + `findChildViewUnder` 取行号 → `seekTo`，
      **只定位不自动播放**；立即高亮并屏蔽 ticker 约 1 秒
- [x] 正在 prepare / 时长未知时提示；目标 `>= duration - 300ms` 时按越界拒绝（保留尾部 300ms 安全余量）
- [x] `LrcParser.indexAt`：二分查找当前行（纯逻辑，测试 +1）

**演示**：CI `testDebugUnitTest` 通过；真机拖进度、切歌、双击某句定位

## 任务 30 — 系统媒体控制中心进度拖动（v1.48） `[x]`

**目标**：系统媒体控制中心/锁屏拖动进度后，真正更新播放位置（R9.9）

**实现**
- [x] `MusicService.MediaSessionCompat.Callback.onSeekTo(long)` 调用 `MusicPlayer.seekTo`；仅声明 `ACTION_SEEK_TO` 不会执行定位
- [x] 时长已知时夹到 `[0, duration]`，未知时至少限制为非负
- [x] seek 异步：移除上次延迟同步，400ms 后 `syncPlaybackState` 回写真实位置；原有每秒进度刷新保留

**产出**：`MusicService.java`（提交 `354663d`）

**验证**：代码路径与提交核对；v1.50 用户持续实机使用确认正常。本次未单独重测系统媒体 UI。

---

## 任务 31 — 跟随系统深色模式与列表文字修复（v1.49–v1.50） `[x]`

**目标**：避免只有菜单/对话框变深而列表仍浅，以及深色背景上黑字不可见

**实现**
- [x] 保留 DayNight 主题，新增 `values-night/colors.xml`，跟随系统夜间配置；没有应用内手动主题开关
- [x] 深色背景 `#121212`、表面 `#1E1E1E`，正文/图标/播放条/下载状态/播放高亮使用对应夜间颜色；网络状态虽有夜间资源，`MainActivity.updateNetworkStatus()` 仍直接引用系统 holo 红/绿，不夸大为全部接入
- [x] 主题补齐 `colorSurface` / `colorOnSurface` / `colorOnBackground` / `colorError`，对话框背景使用 surface
- [x] 16 个矢量图标改用 `@color/icon_normal`，列表 ripple 改用 `@color/ripple`；主界面及歌词搜索对话框使用 AppCompat AlertDialog
- [x] v1.50 将 `FileListAdapter` 文件夹及普通文件名的两处硬编码黑色改为 `R.color.text_primary`，保留下载/播放状态色优先级
- [x] 歌词内容区仍是固定深色布局；不把该页面误记为整页随系统切换

**产出**：`values-night/colors.xml`、`values/colors.xml`、`values/themes.xml`、矢量图标与列表 ripple、`MainActivity.java`、`LyricsActivity.java`、`FileListAdapter.java`（提交 `8ef5bf1`、`fba3714`）

**验证**：源码/资源与提交核对；用户已持续实机使用 v1.50 并明确确认正常。此次为 spec 对齐，不新增代码或冒充新一轮真机验收。

---

## 任务 32 — 目录快照原子写入（v1.51 交付预备） `[-]`

- [x] `AtomicSnapshotWriter` 同目录唯一临时文件、UTF-8、flush/sync/close、原子替换；原子移动不可用时失败关闭
- [x] 写入/发布失败保留旧目标；首次失败不产生目标；尝试清理本次临时文件，不直接覆盖、不非原子降级
- [x] `LocalCacheManager.saveSnapshot` 接入；读取改为一次打开完整读取，修复先 length 再 open 的并发截断竞争
- [x] 永久新增纯 JVM 7 例 + Robolectric 并发 JSON 往返 1 例；本地无 JUnit main 与永久用例共享检查
- [x] 本地 RED：旧真实直接覆盖模式写中 IOException 后目标变成 `{partial`；GREEN：7 场景 PASS，含 6 线程、450 次写 + 450 次读
- [x] 版本预备 `versionCode 151` / `versionName 1.51`；三份 spec 对齐；不修改搜索、离线索引、网络与 AGENTS
- [ ] 云端 JUnit / Robolectric、Android APK 构建与真机验证（本次未授权推送，未触发 CI）

**边界**：原子可见性并非突然断电后的目录项持久性；被杀可能遗留未发布临时文件；并发保存最后成功发布者生效。不改变删除/清空与在途保存的原有先后语义，不修离线键缺口。

**产出**：`AtomicSnapshotWriter.java`（49 行）、`LocalCacheManager.java`、`AtomicSnapshotWriterChecks.java`、`AtomicSnapshotWriterTest.java`、`LocalCacheManagerTest.java`、`app/build.gradle` 与三份 spec。

---

## 任务 33 — 本地快照跨服务器歌曲搜索播放 `[-]`

- [x] 设计与 R16 需求：覆盖已缓存目录而非远端全库；替代旧扫描提案，无数据库/递归/网络搜索。
- [x] SnapshotSearchEngine：音频/已配置服务器过滤、serverId + relativePath 去重、文件名去扩展名/trim/大小写无关/分词 AND、空查询不列全量，TrackSorter 全局排序。
- [x] SnapshotSearchController：单线程后台汇总与内存查询、generation/revision 防旧结果覆盖、关闭后拒收。
- [x] LocalCacheManager：快照 revision/监听、单份损坏跳过、空刷新覆盖、发布/清空/删除/读取并发隔离；清空 epoch 防止旧网络响应回写。
- [x] SearchActivity：主页面按钮、独立结果页、数量/加载/空提示、目录/服务器别名/下载状态/播放高亮；共享播放条与 adjustResize、主题资源。
- [x] 点击时复制结果队列，从点击曲目播放，复用 MusicPlayer/MusicService；离线无下载提示；清空/查询/退出不改队列。MainActivity 返回恢复滚动位置。
- [x] 发现 MusicPlayer.isSameTrack 仅按 href/relativePath 比较会混淆跨服务器同名；抽 TrackIdentity 先隔离归属并增加回归测试。
- [x] 本地 RED→GREEN：匹配期望 1 实际 0、过滤期望 2 实际 7、异步旧结果错误交付、存储 revision 未变化、跨服务器身份误判；实际通过结果见 docs/music-search-design.md。
- [ ] CI Android 编译/JUnit/Robolectric（新增 StorageTest 包装未在本机运行）、真机键盘/主题/原页面返回/跨服务器连续切歌验收。
- [x] 独立审查：生命周期/快照并发/队列身份；用户已授权 v1.52 提交推送与 GitHub Actions 出包。真机验收前不打 tag。

**验证命令**：`python3 scripts/test_music_search_jvm.py`、`python3 scripts/check_music_search.py`、`git diff --check`。纯 JVM stub 不代表 Android 框架测试；静态契约检查不代表 UI 运行。

## 依赖关系

```
任务1 (项目骨架)
  ├─→ 任务2 (WebDAVClient)
  │     ├─→ 任务4 (浏览UI)
  │     ├─→ 任务6 (下载)
  │     └─→ 任务9 (上传)
  ├─→ 任务3 (模型+工具)
  │     ├─→ 任务5 (音乐识别)
  │     ├─→ 任务6 (下载)
  │     ├─→ 任务7 (播放)
  │     └─→ 任务8 (快照)
  └─→ 任务10 (CI) ← 依赖全部代码任务
        └─→ 任务11 (测试)

任务13 (多服务器) ← 任务14 (根层级) ← 任务16 (定位)
任务12 (前台服务) ← 任务20 (播放健壮性)
任务8 (快照) ← 任务15 (离线索引) ← 任务21 (渲染修复)
任务12/13/14 ← 任务17 (远程日志) → 支撑 任务20/21 的根因定位
任务7 (播放) ← 任务22 (播放模式) ← 依赖 任务12 (前台服务/MediaSession 的切歌链路)
任务2 (WebDAVClient) ← 任务23 (新建文件夹 MKCOL)
任务2 (WebDAVClient) ← 任务24 (删除远端 DELETE)
任务17 (远程日志) → 任务25 (HMAC 鉴权与端点可配；RemoteLogger 使用独立 uploadClient，不依赖 WebDAVClient PUT)
任务2/4/7/22 → 任务26 (真实时间解析、列表排序与队列重排)
任务2/7 → 任务27 (全屏歌词) → 任务28 (搜索与上传，依赖任务2 的 putBytes)
任务7/22/27 → 任务29 (共享播放条与双击歌词定位)
任务7/12 → 任务30 (系统媒体控制中心 seek)
任务4/5/27/28/29 → 任务31 (系统深色色板、对话框/图标与列表文字)
任务27/28/29 → 任务11 (歌词逻辑测试增至 9 类 / 97 例)
任务8/11 → 任务32 (快照原子写入；测试增至 10 类 / 105 例，新增待 CI)
```

**可并行组**：任务 2 与任务 3 相互独立；任务 6、7、8、9 在 2+3 完成后可并行。

---

## 任务状态总览（v1.52 CI 交付预备，基于 `8327c06`）

| 分类 | 状态 |
|------|------|
| 脚手架与核心功能（1–9） | 任务 6 下载百分比/残留清理、任务 8 离线快照链路仍有缺口；任务 5 的封面关联/图片歌词列表展示为范围外 |
| CI 与产物（10） | 完成（可出包，真机可装） |
| 单元测试（11） | **部分完成**：13 个测试类 / 113 个 `@Test`；本次 SDK-free JUnit 实际执行 71 例 + 存储共享检查，Robolectric 未运行；`MusicPlayerTest`、`NetworkUtilsTest` 待补 |
| 真机迭代增强（12–31） | v1.50 用户持续实机使用确认正常；任务 13 删除服务器清缓存、任务 15 离线索引链路、任务 16 跨目录定位尚未完整实现，标为部分完成 |

**代码规模（v1.56）**：30 个生产 Java 文件 / 11692 物理行（仅生产 Java，按 `splitlines()` 含注释/空行）；测试执行数以精确 CI HTML 报告为准。旧任务产出行数为其当时快照，不冒充当前计数。
