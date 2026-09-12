# Tasks — davmusic

> Android WebDAV 音乐播放器 · 实施任务清单
>
> 状态标记：`[ ]` 未开始 · `[-]` 进行中 · `[x]` 已完成
>
> 任务来源：Kiro `kiro_planner` agent 生成的原始计划（10 项），已按实际实现状态标注。

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
| 10 | 构建流水线与产物验证 | `[-]` | — | `.github/workflows/build.yml` |
| 11 | 单元测试补充 | `[ ]` | 见 `design.md` §7 | 待新增 |

> **说明**：代码产出阶段（任务 1–9）已由 Kiro 执行 agent 一次完成，共 9 个 Java 文件 / 2783 行。
> 任务 10 尚未跑通（需修复 wrapper 后推送云端构建）。
> 任务 11 为 `design.md` §7 列出的测试用例，第一版尚未实现。

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
- [x] `isConfigured(Context)` 静态判定，已配置则跳过本页

**产出**：`build.gradle`, `app/build.gradle`, `gradle.properties`, `ServerConfigActivity.java`(242行), `activity_server_config.xml`

**测试**：`design.md` §7.2 `NetworkUtilsTest`；配置读写为手工验证项

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

**测试**：`design.md` §7.2 `WebDAVResponseParserTest`（8 个用例，含中文路径与命名空间变体）

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

**测试**：`design.md` §7.2 `FileUtilsTest`（10 个用例，覆盖中文文件名与大小写不敏感）

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

**测试**：UI 交互为手工验证项（见 `design.md` §7.3）

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

**测试**：`design.md` §7.2 `FileUtilsTest`

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

**测试**：`design.md` §7.2 `LocalCacheManagerTest`（下载标记部分）

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

**测试**：`design.md` §7.2 `MusicPlayerTest`

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

**测试**：`design.md` §7.2 `LocalCacheManagerTest`（快照往返、不含本地状态、覆盖更新）

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

## 任务 10 — 构建流水线与产物验证 `[-]`

**目标**：通过 GitHub Actions 云端构建出可安装的 APK

**实现**
- [x] 创建 `.github/workflows/build.yml`
  - `ubuntu-latest` + JDK 17 (temurin) + `android-actions/setup-android@v3`
  - `gradle/actions/setup-gradle@v4` + `./gradlew assembleDebug --no-daemon`
  - `upload-artifact@v4`（`name: davmusic-debug-apk`，`if-no-files-found: error`）
- [x] 创建 `.gitignore`（含 `*.jar` 规则 + `!gradle/wrapper/gradle-wrapper.jar` 例外）
- [ ] **修复 `gradle-wrapper.jar`（当前 0 字节，需替换为有效 43KB 版本）**
- [ ] 跑预推送检查清单（7 项失败模式）
- [ ] 创建 GitHub 仓库并推送
- [ ] 触发构建并等待成功
- [ ] 下载 APK 并验证（`file` / 结构 / MD5）

**产出**：`.github/workflows/build.yml`, `.gitignore`

**测试**：云端构建成功 = 集成验证通过

**演示**：actions 页面显示绿色 ✓ → 下载 artifact → 真机安装运行

**已知风险**：根据既往经验，agent 生成的 Android 脚手架通常需要 2–4 轮云端构建才能通过。已识别的历史失败模式（已在预推送清单中覆盖）：
1. 缺 `gradle.properties` → `checkDebugAarMetadata` 失败
2. wrapper 不完整或 `gradle-wrapper.jar` 为空
3. `.gitignore` 的 `*.jar` 吞掉 wrapper jar
4. 手写 `gradlew` 缺 `JAVA_HOME` 解析 → `exit 127`
5. 自适应图标把 `<color>` 引用为 `@drawable/`
6. 引用了不存在的 `proguard-rules.pro`
7. Java 文件缺 `import` → `cannot find symbol`

---

## 任务 11 — 单元测试补充 `[ ]`

**目标**：按 `design.md` §7 实现测试用例

**实现**
- [ ] `FileUtilsTest`：10 个用例（扩展名判定、格式化）
- [ ] `WebDAVResponseParserTest`：8 个用例（XML 解析）
- [ ] `LocalCacheManagerTest`：6 个用例（快照与下载记录）
- [ ] `MusicPlayerTest`：5 个用例（播放源选择、列表边界）
- [ ] `NetworkUtilsTest`：2 个用例（网络检测）
- [ ] 建议重构：把 `WebDAVClient` 的 XML 解析抽成独立的 `parseResponse(String)` 方法以便测试

**前置条件**：`design.md` §7.4 建议引入 Robolectric 与 MockWebServer

**产出**：`app/src/test/java/com/byron/davmusic/*Test.java`

**演示**：`./gradlew test` 全部通过

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
```

**可并行组**：任务 2 与任务 3 相互独立；任务 6、7、8、9 在 2+3 完成后可并行。
