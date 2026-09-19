# Requirements — davmusic

> Android WebDAV 音乐播放器
>
> 本文档遵循 Kiro Spec 规范，使用 **EARS**（Easy Approach to Requirements Syntax）记法书写。
> 每条需求以 `WHEN [条件] THE SYSTEM SHALL [行为]` 的形式表达，可直接转化为测试用例。
>
> ⚠️ **动手前请先读 [`AGENTS.md`](../../../AGENTS.md)** —— 那是本项目的**架构决策记录**，
> 记录维护者明确定下的取舍，尤其注意标记 **⛔ 不要加回来** 的已废弃方案
> （如「全局活动服务器 + 切换清缓存」、「失败计数/退避重试」）。
> 本文档描述**需求是什么**；`AGENTS.md` 描述**为什么这样设计、什么不能改**。两者互补，冲突时以 `AGENTS.md` 为准。
>
> **修订记录**
> - 初版：第一版功能设计（单服务器、不含后台播放）
> - 本次修订（对应代码 v1.30）：**多服务器**、**服务器列表即根目录**、**前台服务与媒体控制**、
>   **按服务器隔离的缓存作用域**、**本地文件树离线索引**、**定位到当前歌曲** 已从「范围外」转为已实现需求，
>   需求组由 R1–R8 扩充为 R1–R10。范围外章节已相应裁剪。

---

## 1. 简介

`davmusic` 是一个 Android 应用，通过 WebDAV 协议访问远程音乐库，提供**多服务器管理**、文件夹浏览、音乐扫描、下载缓存、在线/离线播放、上传以及**后台播放与媒体控制**能力。

**核心场景**：用户在移动网络下浏览自己存放在云盘（经 OpenList 转发的 WebDAV）中的音乐库，按需下载常听的歌曲；在网络不可用时，仍能浏览曾经访问过的文件夹，并播放已下载到本地的音乐。用户可配置**多台** WebDAV 服务器（例如不同云盘或不同账号），在根层级切换；切换后各服务器的缓存与快照互不干扰。播放可在后台持续，并通过通知栏、锁屏与蓝牙耳机按键控制。

---

## 2. 术语

| 术语 | 含义 |
|------|------|
| WebDAV | 基于 HTTP 的文件访问协议，支持 PROPFIND / GET / PUT 等方法 |
| PROPFIND | WebDAV 列目录请求，返回 XML 格式的文件元数据 |
| 快照 (Snapshot) | 某文件夹被访问后，将其文件列表持久化到本地的副本 |
| 已下载 | 音乐文件的完整内容已保存到 App 私有存储 |
| 流式播放 | 不下载完整文件，边传输边播放（依赖服务端 Range 支持） |
| EARS | Easy Approach to Requirements Syntax，结构化需求记法 |

---

## 3. 需求

### 3.1 服务器配置（多服务器）

**R1.1 首次启动强制配置**
- WHEN 应用首次启动且本地不存在任何服务器配置
- THE SYSTEM SHALL 显示服务器配置界面，并要求用户填写 WebDAV 地址、用户名、密码后才能进入主界面

**R1.2 配置持久化**
- WHEN 用户点击保存且连接测试成功
- THE SYSTEM SHALL 将服务器地址、用户名、密码（含服务器别名与唯一 id）持久化到 SharedPreferences（`servers_json` 数组 + `active_server_id`）

**R1.3 配置复用**
- WHEN 应用启动且本地已存在有效配置
- THE SYSTEM SHALL 跳过配置界面，直接进入根层级（服务器列表）

**R1.4 连接测试**
- WHEN 用户在配置界面点击"测试连接"
- THE SYSTEM SHALL 向配置的服务器地址发起 PROPFIND 请求，并在 UI 上显示成功或失败结果

**R1.5 多服务器管理**
- WHEN 用户进入服务器管理界面
- THE SYSTEM SHALL 列出全部已保存服务器，并支持**新增、编辑、删除、设为活动**四种操作

**R1.6 认证方式**
- WHEN 向某台 WebDAV 服务器发起任何请求
- THE SYSTEM SHALL 使用该服务器自己的凭据构造 HTTP Basic Auth 认证头（用户名 + 密码的 Base64 编码）

**R1.7 活动服务器选择**
- WHEN 用户将某台服务器设为活动
- THE SYSTEM SHALL 将其 id 写入 `active_server_id`，并在主界面以该服务器为当前根

**R1.8 活动服务器回退**
- WHEN 活动 id 指向的服务器已被删除，或配置数据损坏
- THE SYSTEM SHALL 回退到列表中的第一台服务器，不得因"有服务器却选不出活动服务器"而跳回配置页

**R1.9 别名校验**
- WHEN 用户新增或编辑服务器时使用了与其它服务器重复的别名
- THE SYSTEM SHALL 提示别名已被占用并阻止保存

**R1.10 缓存作用域隔离**
- WHEN 服务器被添加或删除
- THE SYSTEM SHALL 为每台服务器派生稳定的作用域标识（scopeId），使下载映射、文件夹快照与离线索引按服务器隔离，不同服务器下相同路径互不串扰

---

### 3.2 根层级与文件夹浏览

**R2.0 服务器列表即根目录**
- WHEN 应用启动或用户返回至根层级
- THE SYSTEM SHALL 将服务器列表作为最顶层目录展示，每台服务器为一个条目；进入某台服务器后以其 WebDAV 根为当前路径

**R2.1 目录列表**
- WHEN 用户进入某个文件夹路径
- THE SYSTEM SHALL 发起 `PROPFIND` 请求（`Depth: 1`）并解析返回的 XML，展示该文件夹下的子项

**R2.2 子项分类展示**
- WHEN 展示文件夹内容
- THE SYSTEM SHALL 将子项分为"文件夹"与"文件"两类，并分别使用不同的图标与视觉样式

**R2.3 进入子文件夹**
- WHEN 用户点击列表中的文件夹项
- THE SYSTEM SHALL 切换到该文件夹路径并加载其内容

**R2.4 返回上级（分层回退）**
- WHEN 用户按下系统返回键
- THE SYSTEM SHALL 按层级回退：当前服务器内非根目录则返回上一级文件夹；已在服务器根目录则返回服务器列表（根层级）；已在服务器列表则退出应用

**R2.4.1 返回按钮**
- WHEN 用户处于某台服务器内（含该服务器根目录）
- THE SYSTEM SHALL 在顶部显示返回按钮，点击后上跳一层

**R2.5 面包屑导航**
- WHEN 用户位于任意非根目录
- THE SYSTEM SHALL 在顶部显示当前服务器名与完整路径，并允许点击路径中的任意层级直接跳转

**R2.6 手动刷新**
- WHEN 用户触发下拉刷新
- THE SYSTEM SHALL 重新向服务器请求当前文件夹内容并更新列表

**R2.7 加载状态**
- WHEN 正在请求文件夹内容
- THE SYSTEM SHALL 显示加载指示器；请求完成后隐藏

**R2.8 请求错误处理**
- WHEN 文件夹请求因网络异常或服务器错误而失败
- THE SYSTEM SHALL 显示可读的错误提示，且不导致应用崩溃

**R2.9 隐藏系统文件**
- WHEN 解析服务器返回的目录列表
- THE SYSTEM SHALL 过滤掉 `.DS_Store`、`._*` 等系统元数据文件

**R2.10 请求期服务器快照**
- WHEN 用户在一次请求进行途中切换了服务器
- THE SYSTEM SHALL 以发起该请求时所绑定的服务器实例处理其回调，避免把 A 服务器的结果写入 B 服务器的作用域

---

### 3.3 音乐文件识别

**R3.1 音频格式识别**
- WHEN 展示文件夹中的文件
- THE SYSTEM SHALL 识别以下扩展名为音频文件：`mp3`、`m4a`、`flac`、`wav`、`wma`、`aac`、`ogg`、`ape`、`opus`（大小写不敏感）

**R3.2 文件信息展示**
- WHEN 展示一个音频文件条目
- THE SYSTEM SHALL 显示其文件名与文件大小（人性化格式，如 `7.4 MB`）

**R3.3 封面关联**
- WHEN 某文件夹内存在 `cover.jpg` / `Cover.jpg` / `folder.jpg`
- THE SYSTEM SHALL 将其识别为该文件夹内音频的封面图

**R3.4 歌词关联**
- WHEN 某音频文件存在同名的 `.lrc` 或 `.ttml` 文件
- THE SYSTEM SHALL 将其识别为该音频的歌词文件

**R3.5 图片与歌词分类**
- WHEN 展示非音频文件
- THE SYSTEM SHALL 将图片（`jpg`/`jpeg`/`png`/`webp`）与歌词（`lrc`/`ttml`）分类展示，不与音频混淆

---

### 3.4 下载管理

**R4.1 下载触发**
- WHEN 用户点击未下载音频条目的下载按钮
- THE SYSTEM SHALL 开始将该文件从 WebDAV 服务器下载到 App 私有目录

**R4.2 下载进度**
- WHEN 下载正在进行中
- THE SYSTEM SHALL 实时显示下载进度（进度条 + 百分比）

**R4.3 已下载视觉标记**
- WHEN 某音频文件已完成下载
- THE SYSTEM SHALL 将该条目的文字颜色显示为绿色（`#4CAF50`），以区别于未下载条目

**R4.4 未下载默认样式**
- WHEN 某音频文件未下载
- THE SYSTEM SHALL 以默认文字颜色展示该条目

**R4.5 下载状态持久化**
- WHEN 应用重启
- THE SYSTEM SHALL 从本地记录中恢复所有已下载文件的状态，仍以绿色标记

**R4.6 删除本地缓存**
- WHEN 用户对已下载条目执行删除操作
- THE SYSTEM SHALL 删除本地文件并清除下载记录，条目恢复为未下载样式

**R4.7 下载到私有目录**
- WHEN 保存下载文件
- THE SYSTEM SHALL 存储在 `getExternalFilesDir()` 下，不需要外部存储权限

**R4.8 下载失败处理**
- WHEN 下载过程中发生网络错误
- THE SYSTEM SHALL 终止下载、清理不完整的临时文件，并提示用户

---

### 3.5 播放

**R5.1 播放来源选择**
- WHEN 用户点击某个音频文件播放
- THE SYSTEM SHALL 判断：若该文件已下载则播放本地文件；否则流式播放在线 URL

**R5.2 在线流式播放**
- WHEN 播放未下载的音频
- THE SYSTEM SHALL 通过 HTTP 请求该文件的 WebDAV URL，并携带 Authorization 头

**R5.3 播放列表**
- WHEN 用户点击某文件夹内的一个音频文件
- THE SYSTEM SHALL 以该文件夹内的全部音频文件构建播放列表，并把被点击项设为当前曲目

**R5.4 播放控制**
- WHEN 播放器处于活动状态
- THE SYSTEM SHALL 提供播放/暂停、上一首、下一首、进度拖动等控制

**R5.5 播放进度显示**
- WHEN 音频正在播放
- THE SYSTEM SHALL 显示当前播放位置与总时长，并同步更新进度条

**R5.6 迷你播放器**
- WHEN 有曲目被加载
- THE SYSTEM SHALL 在界面底部显示迷你播放器，包含曲目名、播放/暂停按钮、上一首/下一首按钮

**R5.7 状态变更通知**
- WHEN 播放状态（曲目切换、播放/暂停、进度）发生变化
- THE SYSTEM SHALL 通过回调通知 UI 更新

**R5.8 播放进度与时长显示**
- WHEN 音频正在播放
- THE SYSTEM SHALL 同时显示已播时长与总时长，且两者均不被布局裁剪

**R5.9 缓冲区容错**
- WHEN 播放进入缓冲状态
- THE SYSTEM SHALL 保持稳定不崩溃，缓冲结束后继续播放

**R5.10 切歌静默**
- WHEN 用户切换曲目
- THE SYSTEM SHALL 不因新旧数据源交替过程中的中间态而弹出误报错误提示

---

### 3.6 离线能力

**R6.1 文件夹快照**
- WHEN 用户成功访问并加载某个文件夹
- THE SYSTEM SHALL 将该文件夹的完整文件列表（路径、名称、大小、类型、修改时间、ETag、是否文件夹）序列化为 JSON 并持久化到本地

**R6.2 离线浏览**
- WHEN 网络不可用且用户进入曾被访问过的文件夹
- THE SYSTEM SHALL 从本地快照读取并展示该文件夹的内容，而不是显示错误

**R6.3 离线播放已下载音乐**
- WHEN 网络不可用且用户播放已下载的音频
- THE SYSTEM SHALL 通过本地文件路径正常播放，不产生网络请求

**R6.4 网络状态感知**
- WHEN 网络连接状态发生变化
- THE SYSTEM SHALL 检测当前在线/离线状态并在 UI 上显示标识

**R6.5 无快照时的离线提示**
- WHEN 网络不可用且用户进入从未访问过的文件夹
- THE SYSTEM SHALL 提示"该文件夹无本地快照，需联网加载"

**R6.6 快照覆盖更新**
- WHEN 用户在线重新访问某文件夹且服务器端内容有变化
- THE SYSTEM SHALL 用最新结果覆盖该文件夹的本地快照

**R6.7 本地文件离线索引**
- WHEN 用户成功下载一个音频文件
- THE SYSTEM SHALL 将其远程路径、显示名与大小记入本地离线索引，使该文件在离线时也能出现在所在目录的列表中

**R6.8 本地文件树浏览**
- WHEN 网络不可用且用户浏览某个路径
- THE SYSTEM SHALL 能由本地离线索引中已下载的文件（按远程路径归组）重建出目录树并展示

**R6.9 混合展示**
- WHEN 某目录在线获取成功但同时存在已下载文件
- THE SYSTEM SHALL 以服务器结果为准，并标注哪些条目已下载，不出现重复条目

**R6.10 网络恢复自动切回**
- WHEN 网络由离线恢复为在线
- THE SYSTEM SHALL 自动重新加载当前目录，从快照/本地索引切回在线结果

---

### 3.7 上传

**R7.1 上传入口**
- WHEN 用户位于某个文件夹并点击上传按钮
- THE SYSTEM SHALL 打开系统文件选择器

**R7.2 上传执行**
- WHEN 用户选定了本地文件
- THE SYSTEM SHALL 通过 HTTP `PUT` 将文件上传到当前 WebDAV 目录

**R7.3 上传进度**
- WHEN 上传正在进行
- THE SYSTEM SHALL 显示上传进度

**R7.4 上传后刷新**
- WHEN 上传成功完成
- THE SYSTEM SHALL 刷新当前文件夹列表，使新文件出现

**R7.5 上传失败处理**
- WHEN 上传失败
- THE SYSTEM SHALL 显示错误提示，且不影响应用其他功能

---

### 3.8 上传 —— 格式与限制

**R8.1 格式验证**
- WHEN 用户选择了非音频格式的文件
- THE SYSTEM SHALL 提示该格式不受支持，并阻止上传

**R8.2 上传后覆盖同路径**
- WHEN 上传目标路径已存在同名文件
- THE SYSTEM SHALL 以 PUT 覆盖并通过刷新列表反映结果

---

### 3.9 后台播放与媒体控制

**R9.1 前台服务保活**
- WHEN 用户开始播放
- THE SYSTEM SHALL 通过 `startForegroundService()` 启动前台服务，使播放不因切后台或锁屏而中断

**R9.2 通知栏媒体控制**
- WHEN 播放处于活动状态
- THE SYSTEM SHALL 在通知栏显示媒体通知，包含曲目信息与播放/暂停、上一首、下一首控制

**R9.3 通知渠道**
- WHEN 应用首次创建媒体通知
- THE SYSTEM SHALL 创建低重要性（IMPORTANCE_LOW，不响铃不震动）的通知渠道，并允许锁屏可见

**R9.4 锁屏与蓝牙按键控制**
- WHEN 用户通过锁屏、线控或蓝牙耳机按键发起控制
- THE SYSTEM SHALL 由 MediaSession 接收并映射为对应播放动作：单击播放/暂停、双击下一首、三击上一首

**R9.5 进度同步到媒体控制中心**
- WHEN 音频正在播放
- THE SYSTEM SHALL 周期性（1 秒）刷新 MediaSession 的 PlaybackState 位置，使系统进度条与"已播/剩余"持续更新

**R9.6 播完自动续播**
- WHEN 一首曲目播放完毕
- THE SYSTEM SHALL 自动播放播放列表中的下一首，该行为在锁屏/后台状态下同样成立

**R9.7 线程契约**
- WHEN 对播放器执行任何操作
- THE SYSTEM SHALL 在单一播放线程上串行执行，避免多线程访问造成状态错乱

**R9.8 服务释放**
- WHEN 播放停止且不再需要保活
- THE SYSTEM SHALL 调用 `stopSelf()` 结束服务并释放播放器资源

---

### 3.10 定位到当前歌曲

**R10.1 定位入口**
- WHEN 用户在"更多"菜单选择"定位到当前歌曲"
- THE SYSTEM SHALL 跳转到当前播放曲目所在的目录

**R10.2 未播放时的提示**
- WHEN 用户执行定位但当前无播放中的曲目，或尚未进入任何服务器
- THE SYSTEM SHALL 给出可读提示而不执行跳转

**R10.3 定位后的滚动**
- WHEN 定位成功
- THE SYSTEM SHALL 将列表滚动到该曲目并高亮，使其对用户可见

---

## 4. 非功能需求

**N1 网络超时**
- THE SYSTEM SHALL 将网络连接超时设为 15 秒、读取超时设为 60 秒

**N2 主线程保护**
- THE SYSTEM SHALL 在网络请求等耗时操作中不阻塞主线程，所有 UI 更新通过 `runOnUiThread` 执行

**N3 明文流量**
- THE SYSTEM SHALL 允许明文 HTTP 流量（因 WebDAV 服务可能部署在无 HTTPS 的环境）

**N4 权限最小化**
- THE SYSTEM SHALL 申请且仅申请以下权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`POST_NOTIFICATIONS`（Android 13+ 通知）、`READ_MEDIA_AUDIO`（上传选文件所需）

**N5 版本兼容**
- THE SYSTEM SHALL 运行于 Android 8.0（API 26）及以上版本

**N6 存储位置**
- THE SYSTEM SHALL 将下载文件存放于 App 私有目录，卸载应用时随之清除

**N7 请求节流**
- WHEN 服务器返回 429（限流）
- THE SYSTEM SHALL 仅向用户提示，不引入自动失败计数与退避重试机制（避免与服务器限制自锁）

**N8 请求合并**
- WHEN 短时间内对同一资源重复发起加载
- THE SYSTEM SHALL 通过内存缓存减少重复请求，且缓存不跨服务器作用域混用

**N9 诊断可观测性**
- THE SYSTEM SHALL 提供远程日志上报能力（`RemoteLogger`），用于在真机上定位锁屏/后台等难以复现的问题

**N10 数据一致性**
- WHEN 快照或离线索引与服务器实际内容不一致
- THE SYSTEM SHALL 以服务器在线结果为最终准据

**N11 输出目录规范**
- THE SYSTEM SHALL 按 `<歌手>/<专辑>/` 的结构组织音乐库目录（配合服务端）

---

## 5. 范围外（当前版本不做）

| 项 | 说明 |
|----|------|
| Room 数据库 | 仍用 SharedPreferences + JSON 存储快照与配置，不引入 Room |
| WorkManager | 下载使用 ExecutorService，不引入 WorkManager |
| MVVM 架构 | 仍为 Activity + 回调，不引入 ViewModel/LiveData |
| 元数据编辑 | 不涉及 ID3 标签的读取与写入 |
| 音乐搜索 | 不提供跨文件夹搜索 |
| 下载队列 | 不支持多文件排队与断点续传 |
| 播放模式 | 不支持随机/单曲循环切换（顺序播放 + 循环） |
