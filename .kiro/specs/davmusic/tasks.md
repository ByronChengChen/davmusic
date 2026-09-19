# Tasks — davmusic

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
> 新增任务 12–21 覆盖 v1.18 → v1.30 的实际迭代（多服务器、前台服务、离线索引等）。
> 本清单已与代码 v1.30（15 个类 / 7634 行）对齐。

---

## 任务总览

| # | 任务 | 状态 | 对应需求 | 产出文件 |
|---|------|------|---------|---------|
| 1 | 项目基础搭建与服务器配置 | `[x]` | R1.1–R1.6 | 构建配置 + `ServerConfigActivity` |
| 2 | WebDAV 客户端核心功能 | `[x]` | R2.1, R2.9, N1 | `WebDAVClient` |
| 3 | 数据模型与工具类 | `[x]` | R3.1, R3.5 | `WebDAVFile`, `FileUtils`, `NetworkUtils` |
| 4 | 文件夹浏览 UI | `[x]` | R2.2–R2.8 | `MainActivity`, `FileListAdapter` |
| 5 | 音乐文件识别与列表展示 | `[x]` | R3.2–R3.4 | `FileListAdapter`, `item_file.xml` |
| 6 | 下载管理系统 | `[x]` | R4.1–R4.8 | 下载逻辑 + `LocalCacheManager` |
| 7 | 播放器核心功能 | `[x]` | R5.1–R5.7 | `MusicPlayer` + 迷你播放器 |
| 8 | 离线快照机制 | `[x]` | R6.1–R6.6 | `LocalCacheManager` 快照部分 |
| 9 | 上传功能 | `[x]` | R7.1–R7.5, R8.1 | `WebDAVClient.upload` + 上传入口 |
| 10 | 构建流水线与产物验证 | `[x]` | — | `.github/workflows/build.yml` |
| 11 | 单元测试补充 | `[ ]` | 见 `design.md` §8 | 待新增 |
| **12** | **播放器前台服务与媒体控制** | `[x]` | R9.1–R9.8 | `MusicService` |
| **13** | **多服务器管理** | `[x]` | R1.5, R1.7–R1.10 | `ServerStore`, `ServerProfile`, `ServerManageActivity`, `ServerListAdapter` |
| **14** | **服务器列表即根目录（三层导航）** | `[x]` | R2.0, R2.4, R2.4.1 | `MainActivity` |
| **15** | **本地离线索引与目录树重建** | `[x]` | R6.7–R6.9 | `LocalCacheManager` 索引部分 |
| **16** | **定位到当前歌曲** | `[x]` | R10.1–R10.3 | `MainActivity.locateCurrentTrack()` |
| **17** | **远程日志上报** | `[x]` | N9 | `RemoteLogger` |
| **18** | **请求节流与内存缓存** | `[x]` | N7, N8 | `WebDAVClient` |
| **19** | **固定签名（可覆盖安装保留数据）** | `[x]` | — | `app/build.gradle` keystore 配置 |
| **20** | **播放健壮性修复（缓冲/切歌/续播）** | `[x]` | R5.9, R5.10, R9.6 | `MusicPlayer`, `MusicService` |
| **21** | **列表与离线渲染修复** | `[x]` | R2.5, R6.10 | `MainActivity`, `FileListAdapter` |

> **说明**：任务 1–9 由 Kiro 执行 agent 一次完成（9 个 Java 文件 / 2783 行）。
> 任务 12–21 是后续在真机验证中迭代出来的实际工作，原 spec 未记录，本次补入。
> 任务 11（单元测试）仍未开始，是当前唯一的未完成项。

---

## 任务 1 — 项目基础搭建与服务器配置 `[x]`

**目标**：创建 Android 项目骨架，实现服务器配置界面与凭据存储

**实现**
- [x] 创建 Gradle 工程：AGP 8.5.2 / Gradle 8.7 / Java 17 / compileSdk 34 / minSdk 26
- [x] `gradle.properties` 含 `android.useAndroidX=true`（避免 AGP 8 构建失败）
- [x] Gradle Wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar` / `.properties`）
- [x] 引入 OkHttp 4.12.0（唯一第三方网络库）
- [x] `ServerConfigActivity`：三个输入框 + 测试连接 + 保存
- [x] SharedPreferences 持久化配置
- [x] `isConfigured(Context)` 静态判定，已配置则跳过本页（当前由 `ServerStore.list()` 替代）

**产出**：`build.gradle`, `app/build.gradle`, `gradle.properties`, `ServerConfigActivity.java`(242行), `activity_server_config.xml`

**测试**：`design.md` §8.2 `NetworkUtilsTest`；配置读写为手工验证项

**演示**：首次启动显示配置界面 → 输入地址凭据 → 测试连接成功 → 保存 → 进入主界面

**遗留问题**：`gradle-wrapper.jar` 当前为 0 字节占位文件，**必须在推送前替换**（否则 CI 的 `./gradlew` 无法运行）

---

## 任务 2 — WebDAV 客户端核心功能 `[x]`

**目标**：实现 PROPFIND 请求、XML 解析、Basic Auth 认证

**实现**
- [x] `WebDAVClient` 单例（`synchronized getInstance()`）
- [x] `listFolder(path, callback)`：PROPFIND + `Depth: 1`
- [x] XML 解析提取 `href` / `getcontentlength` / `getlastmodified` / `getcontenttype` / `getetag` / `resourcetype`
- [x] Basic Auth 请求头
- [x] 超时配置：connect 15s / read 60s
- [x] `testConnection()` 供配置页调用
- [x] 过滤 `.DS_Store` 与 `._` 前缀文件

**产出**：`WebDAVClient.java`(385行)

**测试**：`design.md` §8.2 `WebDAVResponseParserTest`（8 个用例，含中文路径与命名空间变体）

**演示**：配置页"测试连接"成功获取根目录文件列表

---

## 任务 3 — 数据模型与工具类 `[x]`

**目标**：定义核心模型，提供文件类型判定与格式化

**实现**
- [x] `WebDAVFile`：`href` / `displayName` / `contentLength` / `contentType` / `lastModified` / `etag` / `isCollection` / `downloadState` / `localPath`
- [x] `DownloadState` 枚举：`NOT_DOWNLOADED` / `DOWNLOADING` / `DOWNLOADED`
- [x] `FileUtils`：`isAudioFile` / `isImageFile` / `isLyricsFile` / `formatFileSize` / `formatDuration` / 路径操作
- [x] `NetworkUtils`：连通性检测 / 网络类型 / `getNetworkStatusString`

**产出**：`WebDAVFile.java`(125行), `FileUtils.java`(208行), `NetworkUtils.java`(217行)

**测试**：`design.md` §8.2 `FileUtilsTest`（10 个用例，覆盖中文文件名与大小写不敏感）

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

**产出**：`MainActivity.java`(731行), `FileListAdapter.java`(216行), `activity_main.xml`, `view_mini_player.xml`

**测试**：UI 交互为手工验证项（见 `design.md` §8.3）

**演示**：浏览文件夹结构 → 点击进入子文件夹 → 返回键回上级

---

## 任务 5 — 音乐文件识别与列表展示 `[x]`

**目标**：识别音频文件，展示文件信息，关联封面与歌词

**实现**
- [x] 9 种音频扩展名过滤：mp3 / m4a / flac / wav / wma / aac / ogg / ape / opus
- [x] 条目显示文件名 + 人性化大小
- [x] 封面图识别（`Cover.jpg` / `cover.jpg` / `folder.jpg`）
- [x] 歌词文件识别（`.lrc` / `.ttml`）
- [x] 图片与歌词分类展示，不与音频混淆

**产出**：`FileListAdapter.java`, `item_file.xml`, `FileUtils.java`

**测试**：`design.md` §8.2 `FileUtilsTest`

**演示**：音频列表显示文件名与大小，非音频文件分类展示

---

## 任务 6 — 下载管理系统 `[x]`

**目标**：实现下载、进度显示、状态持久化、视觉标记

**实现**
- [x] 下载按钮触发下载（OkHttp 异步 + 进度回调）
- [x] 实时进度条（`runOnUiThread` 更新）
- [x] 下载到 `getExternalFilesDir("music")`
- [x] **已完成条目文字变绿 `#4CAF50`**
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
- [x] `Handler` 每 500ms 回调更新进度
- [x] 迷你播放器 UI
- [x] `OnPlaybackListener` 状态回调

**产出**：`MusicPlayer.java`(331行, 22 个 public 方法), `view_mini_player.xml`

**测试**：`design.md` §8.2 `MusicPlayerTest`

**演示**：点击音频播放 → 底部显示迷你播放器 → 控制播放/暂停/切歌；断网后已下载的音乐仍可播放

---

## 任务 8 — 离线快照机制 `[x]`

**目标**：实现文件夹快照与离线浏览（**核心需求**）

**实现**
- [x] 每次成功列出文件夹后自动保存 JSON 快照
- [x] `loadSnapshot(path)` 读取快照
- [x] `hasSnapshot(path)` 判定是否存在
- [x] **离线降级链**：网络请求失败 → 读快照 → 无快照则提示
- [x] 网络状态检测与 UI 标识
- [x] 快照不序列化本地状态字段（避免"快照说已下载、实际已删"的不一致）
- [x] 在线访问时以服务器结果覆盖旧快照

**产出**：`LocalCacheManager.java`(328行) 快照部分 + `NetworkUtils.java` + `MainActivity.loadFolder()`

**测试**：`design.md` §8.2 `LocalCacheManagerTest`（快照往返、不含本地状态、覆盖更新）

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

## 任务 11 — 单元测试补充 `[ ]`

**目标**：按 `design.md` §8 实现测试用例

**实现**
- [ ] `FileUtilsTest`：10 个用例（扩展名判定、格式化）
- [ ] `WebDAVResponseParserTest`：8 个用例（XML 解析）
- [ ] `ServerProfileTest` / `ServerStoreTest`：8 个用例（JSON 往返、scopeId、活动服务器回退）
- [ ] `LocalCacheManagerTest`：9 个用例（快照、下载记录、作用域隔离、目录树重建）
- [ ] `MusicPlayerTest`：7 个用例（播放源选择、列表边界、切歌不误报）
- [ ] `NetworkUtilsTest`：2 个用例（网络检测）
- [ ] 建议重构：把 `WebDAVClient` 的 XML 解析抽成独立的 `parseResponse(String)` 方法以便测试

**前置条件**：`design.md` §8.4 建议引入 Robolectric 与 MockWebServer

**产出**：`app/src/test/java/com/byron/davmusic/*Test.java`

**演示**：`./gradlew test` 全部通过

**状态说明**：这是**当前唯一未完成的任务**。项目迄今依靠"云端构建 + 真机验证"闭环保证质量，
但缺少回归测试意味着每次改动都可能引入旧问题的回潮（v1.20–v1.27 的多个修复即属此类）。

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

**产出**：`MusicService.java`(595行) + `AndroidManifest.xml` service 声明

**测试**：真机手工验证（锁屏控制、耳机按键、后台续播）

**演示**：播放中锁屏 → 通知栏/锁屏出现媒体控制 → 双击耳机切歌 → 播完自动下一首

**踩坑记录**：
- `MediaSessionCompat` 包路径为 `android.support.v4.media.session`（旧版命名空间），不是 `androidx.media.session`

---

## 任务 13 — 多服务器管理 `[x]`

**目标**：支持配置多台 WebDAV 服务器，并隔离各自的缓存

**实现**
- [x] `ServerProfile`：id / 别名 / url / 凭据 + `getScopeId()` / `getDisplayName()` / JSON 往返
- [x] `ServerStore`：`list` / `getActive` / `add` / `update` / `setActive` / `remove` / `isAliasTaken`
- [x] **`pickActive()` 回退**：activeId 失配则退回第一台，避免跳回配置页
- [x] `ServerManageActivity`：服务器列表 + 新增/编辑/删除/设为活动
- [x] `ServerListAdapter`：展示别名 / 主机 / 活动标记
- [x] **`getScopeId()` 作用域**：缓存键 = `scopeId + path`，防跨服务器串扰
- [x] 删除服务器时清理其作用域下的缓存与快照
- [x] 旧版单服务器 key（`server_url` 等）兼容读取

**产出**：`ServerProfile.java`(155行), `ServerStore.java`(291行), `ServerManageActivity.java`(310行), `ServerListAdapter.java`(125行)

**测试**：`design.md` §8.2 `ServerProfileTest` / `ServerStoreTest`

**演示**：新增两台服务器 → 切换 → 各自浏览互不干扰

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

## 任务 15 — 本地离线索引与目录树重建 `[x]`

**目标**：离线时不只靠快照，已下载文件也能独立构成可浏览的目录树

**实现**
- [x] `indexOfflineFile(remotePath, displayName, size)`：下载成功即写索引
- [x] `removeOfflineIndex(...)`：删除本地文件时同步清理
- [x] `listLocalTree(currentPath)`：按远程路径归组重建目录树
- [x] `getOfflineFileCount()`：离线文件计数（关于页展示）
- [x] 离线降级链扩为三级：快照 → 本地索引 → 提示

**产出**：`LocalCacheManager.java`(575行) 索引部分

**测试**：`design.md` §8.2 `LocalCacheManagerTest`（目录树重建、索引同步）

**演示**：下载若干歌曲 → 断网 → 浏览从未在线访问过的目录（由索引重建）

---

## 任务 16 — 定位到当前歌曲 `[x]`

**目标**：从深层目录跳回当前播放曲目所在位置

**实现**
- [x] "更多"菜单 → "定位到当前歌曲"
- [x] 由当前曲目远程路径推出所在目录
- [x] 无播放曲目或未进入服务器时给出提示
- [x] 定位后滚动到该条目并高亮

**产出**：`MainActivity.locateCurrentTrack()`

**测试**：真机手工验证

**演示**：播放某曲 → 浏览到别处 → 菜单定位 → 回到该曲目所在目录并高亮

---

## 任务 17 — 远程日志上报 `[x]`

**目标**：在无法连 IDE 的真机场景下回收日志定位问题

**实现**
- [x] `RemoteLogger`：`i` / `w` / `e` 分级方法，带 TAG
- [x] 本地缓冲 + 批量上报，避免频繁网络请求
- [x] 覆盖播放/服务/媒体会话关键路径

**产出**：`RemoteLogger.java`(251行)

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

**产出**：`WebDAVClient.java` 缓存与节流部分

**测试**：真机手工验证（观察请求量与 429 表现）

**演示**：快速来回切换目录 → 请求量显著下降且不触发封禁

**决策记录**：这是一次**减法**。v1.23 引入的失败计数+退避在真机上造成"自锁"（本地认为在退避、服务器认为无请求），v1.24 回退为只保留内存缓存 + 提示。参见 `AGENTS.md` 的架构约定。

---

## 任务 19 — 固定签名（可覆盖安装保留数据）`[x]`

**目标**：升级安装时不丢数据

**实现**
- [x] `app/build.gradle` 配置固定 keystore
- [x] keystore 路径用 `rootProject.file()` 解析（app 模块相对路径会失败）
- [x] `.gitignore` 排除 keystore 与密码文件

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
- [x] 单一播放线程串行化

**产出**：`MusicPlayer.java`(1039行), `MusicService.java`

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

**产出**：`MainActivity.java`, `FileListAdapter.java`(320行)

**测试**：真机手工验证

**演示**：飞行模式反复进出目录 → 列表稳定、位置正确、恢复网络后自动刷新

**方法论**：这些问题全部是**先收真机日志再改**定位到的，不是凭猜测修改。

---

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
```

**可并行组**：任务 2 与任务 3 相互独立；任务 6、7、8、9 在 2+3 完成后可并行。

---

## 任务状态总览（代码 v1.30）

| 分类 | 状态 |
|------|------|
| 脚手架与核心功能（1–9） | 全部完成 |
| CI 与产物（10） | 完成（可出包，真机可装） |
| 单元测试（11） | **未开始 —— 唯一缺口** |
| 真机迭代增强（12–21） | 全部完成 |

**代码规模**：15 个类 / 7634 行（初版为 9 个类 / 2783 行）
