# DavMusic

一个把 **WebDAV 当音乐库**的 Android 播放器 —— 音乐不落在手机上，直接流式播放你云盘里的歌。

[![Build Android APK](https://github.com/ByronChengChen/davmusic/actions/workflows/build.yml/badge.svg)](https://github.com/ByronChengChen/davmusic/actions/workflows/build.yml)

---

## 这是什么

我用 OpenList 把移动云盘挂载成 WebDAV，音乐按 `歌手/专辑/曲目` 整理在云上。
但安卓上没有称手的 WebDAV 音乐播放器 —— 要么只能下载后播放，要么不支持多服务器，
要么就是界面里塞满了我不要的东西。

于是写了这个。**核心就一件事：像浏览本地文件夹一样浏览云盘，点一下就开始播。**

- 无需下载，边流边播
- 支持多台 WebDAV 服务器并存
- 保留目录层级，歌单结构 = 云盘结构
- 能用就行，不做多余的事

## 功能

**浏览与播放**

- 浏览 WebDAV 目录树，文件夹 / 音频文件用不同图标区分
- 支持格式：`.mp3` `.m4a` `.flac` `.wav` `.wma` `.aac` `.ogg` `.ape` `.opus`
- 后台播放 + 锁屏播放（前台服务保活，带常驻通知）
- 播放控制：播放/暂停、上一首/下一首、进度拖动、随机/循环

**多服务器**

根目录直接列出所有已配置的服务器，当作文件夹用 —— 不用先"切换服务器"再浏览。
每个条目自带归属，跨服务器的同名路径不会互相干扰。

**本地缓存**

- 可将目录内容缓存到本地，弱网或离线时也能浏览
- 已下载的文件标记为本地可用，优先播本地
- 远端目录快照 + 下载映射分别维护
- **同目录 5 分钟内存缓存**：短时间内重复浏览同一目录不产生任何网络请求

**文件操作**

- 上传文件到当前目录（通过系统文件选择器，无需存储权限）
- 删除远端 / 本地文件
- 查看文件详细信息
- 清空缓存

**其他**

- 「关于」页显示版本号 + git 提交号，便于辨认手上装的是哪个包
- 运行日志可回传到服务端，便于真机排障
- 全中文界面，竖屏

## 环境要求

| 项 | 值 |
|---|---|
| 最低版本 | Android 8.0 (API 26) |
| 目标版本 | Android 14 (API 34) |
| 语言 | Java |
| 服务端 | 任意 WebDAV 服务器（开发时用 [OpenList](https://github.com/OpenListTeam/OpenList) 挂载移动云盘） |

**权限说明：** 只要网络相关权限（INTERNET / ACCESS_NETWORK_STATE）+ 前台服务与唤醒锁
（用于后台播放）。**不申请存储权限** —— 下载写入 App 私有目录，上传走系统文件选择器由系统代读。

## 构建

**本地不需要 JDK / Android SDK —— 构建全程在 GitHub Actions 上完成。**

推送到 `main` 分支会自动触发构建，也可以在 Actions 页面手动触发
（`workflow_dispatch`）。

构建产物为 `davmusic-debug-apk`：

1. 打开 [Actions](https://github.com/ByronChengChen/davmusic/actions) 页面
2. 点进最新的成功构建
3. 在页面底部 **Artifacts** 下载 `davmusic-debug-apk`

> 下载 artifact 请用 `curl -L` 并加重试，否则可能拿到截断的 APK。

使用仓库内的**固定签名**（`keystore/davmusic.p12`，debug 用途），
因此各次构建签名一致，`adb install -r` 可直接覆盖安装并保留数据。

## 项目结构

```
app/src/main/java/com/byron/davmusic/
├── ServerConfigActivity.java    启动入口，服务器配置
├── MainActivity.java            主界面：目录浏览、播放控制、文件操作
├── ServerManageActivity.java    服务器管理：新增 / 删除 / 编辑
├── WebDAVClient.java            WebDAV 协议（PROPFIND / GET / PUT / DELETE）
├── WebDAVFile.java              文件条目模型（自带 serverId）
├── ServerProfile.java           服务器配置模型
├── ServerStore.java             服务器清单持久化
├── MusicPlayer.java             播放器封装（含流式播放鉴权）
├── MusicService.java            前台服务，后台保活
├── LocalCacheManager.java       本地缓存与快照
├── FileListAdapter.java         主列表适配器
├── ServerListAdapter.java       服务器列表适配器
├── FileUtils.java               文件类型判断等工具
├── NetworkUtils.java            网络状态
└── RemoteLogger.java            日志回传
```

## 设计取舍

本项目刻意保持简单。有几处是**明确否掉**的方案，不是遗漏：

**多服务器用「服务器即根目录」，不用「全局活动服务器」**
早期做法是维护一个"当前选中服务器"，切换时清空队列和缓存。
但这样每个条目都要查全局状态才知道自己属于谁，且切换动作会打断正在播的歌。
现在根目录就是服务器列表，条目自带 `serverId`，缓存键为 `serverId:路径` ——
没有"当前服务器"这个概念，也就不需要切换。

**不做失败计数 / 退避重试**
关键防护是「同目录 5 分钟内存缓存」，它已经把请求量压得很低。
再叠一层退避只会增加复杂度，还可能在误判时挡住正常请求。
遇到 429 的正确行为是**弹提示给用户**，仅此而已。

> 上游 OpenList 有限流和登录失败锁。客户端自动重试会把服务端锁死 ——
> 这在实际使用中真实发生过。所以这里的克制是功能，不是妥协。

**不申请存储权限**
下载写 App 私有目录，上传走系统文件选择器。少一个权限，少一层麻烦。

## 开发约定

架构决策、踩坑记录、构建注意事项都写在 **[AGENTS.md](./AGENTS.md)** 里。
动手改代码之前请先读它 —— 尤其是标记 **⛔ 不要加回来** 的已废弃方案。

## 已知限制

- 原生 `MediaPlayer` 对 `wma` / `ape` 支持不完整，部分文件可能无法播放
- 纯 Java + Android 原生组件，无第三方 UI/网络框架（有意为之）
- 仅适配手机竖屏

## License

个人项目，未附许可证。
