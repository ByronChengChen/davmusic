# Requirements — davmusic

> Android WebDAV 音乐播放器
>
> 本文档遵循 Kiro Spec 规范，使用 **EARS**（Easy Approach to Requirements Syntax）记法书写。
> 每条需求以 `WHEN [条件] THE SYSTEM SHALL [行为]` 的形式表达，可直接转化为测试用例。

---

## 1. 简介

`davmusic` 是一个 Android 应用，通过 WebDAV 协议访问远程音乐库，提供文件夹浏览、音乐扫描、下载缓存、在线/离线播放和上传能力。

**核心场景**：用户在移动网络下浏览自己存放在云盘（经 OpenList 转发的 WebDAV）中的音乐库，按需下载常听的歌曲；在网络不可用时，仍能浏览曾经访问过的文件夹，并播放已下载到本地的音乐。

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

### 3.1 服务器配置

**R1.1 首次启动强制配置**
- WHEN 应用首次启动且本地不存在服务器配置
- THE SYSTEM SHALL 显示服务器配置界面，并要求用户填写 WebDAV 地址、用户名、密码后才能进入主界面

**R1.2 配置持久化**
- WHEN 用户点击保存且连接测试成功
- THE SYSTEM SHALL 将服务器地址、用户名、密码持久化到 SharedPreferences

**R1.3 配置复用**
- WHEN 应用启动且本地已存在有效配置
- THE SYSTEM SHALL 跳过配置界面，直接进入文件夹浏览主界面

**R1.4 连接测试**
- WHEN 用户在配置界面点击"测试连接"
- THE SYSTEM SHALL 向配置的服务器地址发起 PROPFIND 请求，并在 UI 上显示成功或失败结果

**R1.5 配置修改**
- WHEN 用户在主界面选择"服务器设置"
- THE SYSTEM SHALL 打开配置界面并回填当前已保存的配置值

**R1.6 认证方式**
- WHEN 向 WebDAV 服务器发起任何请求
- THE SYSTEM SHALL 附带 HTTP Basic Auth 认证头（用户名 + 密码的 Base64 编码）

---

### 3.2 文件夹浏览

**R2.1 目录列表**
- WHEN 用户进入某个文件夹路径
- THE SYSTEM SHALL 发起 `PROPFIND` 请求（`Depth: 1`）并解析返回的 XML，展示该文件夹下的子项

**R2.2 子项分类展示**
- WHEN 展示文件夹内容
- THE SYSTEM SHALL 将子项分为"文件夹"与"文件"两类，并分别使用不同的图标与视觉样式

**R2.3 进入子文件夹**
- WHEN 用户点击列表中的文件夹项
- THE SYSTEM SHALL 切换到该文件夹路径并加载其内容

**R2.4 返回上级**
- WHEN 用户按下系统返回键且当前不在根目录
- THE SYSTEM SHALL 返回上一级文件夹；若已在根目录，则退出应用

**R2.5 面包屑导航**
- WHEN 用户位于任意非根目录
- THE SYSTEM SHALL 在顶部显示当前完整路径，并允许点击路径中的任意层级直接跳转

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

---

## 4. 非功能需求

**N1 网络超时**
- THE SYSTEM SHALL 将网络连接超时设为 15 秒、读取超时设为 60 秒

**N2 主线程保护**
- THE SYSTEM SHALL 在网络请求等耗时操作中不阻塞主线程，所有 UI 更新通过 `runOnUiThread` 执行

**N3 明文流量**
- THE SYSTEM SHALL 允许明文 HTTP 流量（因 WebDAV 服务可能部署在无 HTTPS 的环境）

**N4 权限最小化**
- THE SYSTEM SHALL 仅申请 `INTERNET`、`ACCESS_NETWORK_STATE` 及必要的媒体读取权限

**N5 版本兼容**
- THE SYSTEM SHALL 运行于 Android 8.0（API 26）及以上版本

**N6 存储位置**
- THE SYSTEM SHALL 将下载文件存放于 App 私有目录，卸载应用时随之清除

---

## 5. 范围外（第一版不做）

| 项 | 说明 |
|----|------|
| Room 数据库 | 第一版用 SharedPreferences + JSON 存储快照，不引入 Room |
| WorkManager | 下载使用 ExecutorService，不引入 WorkManager |
| MVVM 架构 | 第一版直接 Activity + 回调，不引入 ViewModel/LiveData |
| 后台播放服务 | 第一版不实现前台服务与通知栏控制，仅应用内播放 |
| 元数据编辑 | 不涉及 ID3 标签的读取与写入 |
| 多服务器 | 仅支持单服务器配置 |
| 音乐搜索 | 不提供跨文件夹搜索 |
