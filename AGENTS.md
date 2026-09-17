# DavMusic — 项目约定（AI 助手必读）

> 本文件是本项目的**架构决策记录**。其中的取舍是维护者明确定下的，
> **不要"顺手优化"回去**——每一条都对应一次真实的踩坑或明确的性能权衡。
> 修改前请先读完对应章节。

---

## 1. 项目是什么

Android WebDAV 音乐播放器。**没有本地音乐**——所有音乐都在远程 WebDAV 上
（生产实例是 OpenList 挂载的移动云盘 `/cmcc/music/`），边流边播。

- 包名 `com.byron.davmusic`，namespace 同名
- `minSdk 26`（Android 8.0）/ `compileSdk 34` / `targetSdk 34`
- Java（不是 Kotlin），纯 `com.android.application` 插件，无第三方依赖框架
- 源码在 `app/src/main/java/com/byron/davmusic/`，共 15 个类

---

## 2. 🔴 核心架构：多服务器 = 「服务器即根目录」（方案 A）

**这是本项目的基石，已取代早期设计。**

根目录不再是"当前服务器的文件列表"，而是**列出所有已配置的服务器，当作文件夹**。

### 必须遵守的四条

1. **条目自带归属** — 每个 `WebDAVFile` 自带 `serverId` 字段。
   判断一个文件属于哪台服务器，**只看条目本身，不查全局状态**。
2. **缓存键带 serverId** — `WebDAVFile.getCacheKey()` 返回 `"serverId:相对路径"`。
   不同服务器上的同名路径绝不会互相污染。
3. **每服务器一个客户端实例** — 用 `WebDAVClient.forServer(profile)` 构造。
   `getInstance()` 是**遗留兼容接口**，新代码不要用。
4. **不需要"切换服务器"这个动作** — 因为所有服务器是并列的。

### ⛔ 已废弃、不要加回来的旧方案

早期实现是「**全局活动服务器** + 切换时清空队列和缓存」。
**该方案已整体废弃。** 如果你的改动引入了"当前选中的服务器"这类全局可变状态，
说明走错方向了——这正是方案 A 要消除的东西。

---

## 3. 🔴 网络策略：刻意"简单"，不要加复杂度

### 请求量靠内存缓存压，不靠重试逻辑

`LocalCacheManager` 的**同目录 5 分钟内存缓存**是本应用**唯一的关键防护**：

- 命中缓存 = **零请求**（不产生任何网络往返）
- 目录内容在 5 分钟内重复浏览时，请求量降到极低

### ⛔ 刻意不做：失败计数 / 退避重试 / 自动重试

代码里对此有明确注释（`MainActivity.java` 附近）：

> 不做退避/自动重试：本应用的关键防护是"同目录 5 分钟内存缓存"，
> 它已把请求量降到很低；再加一层退避只会增加复杂度，
> 还可能在误判时挡住正常请求。

**遇到 429 的正确行为是：弹提示给用户，仅此而已。**
不要"聪明地"加自动重试、指数退避、请求队列。**这是明确否掉的设计，不是遗漏。**

### 429 的提示逻辑

`MainActivity` 里有识别函数（判断异常串是否含 `429` / `too many` / `rate`），
命中时给用户明确提示。保持这个模式。

### 为什么这么敏感

上游 OpenList 有登录失败锁与限流；**客户端自动重试会把服务端锁死**
（真实事故：客户端重试导致用户被 ban，且清除后几分钟内再次被 ban）。
所以**克制**在这里是功能，不是妥协。

---

## 4. 版本号与交付流程

### 版本号规则（**每次交付必须递增**）

`app/build.gradle` 的 `defaultConfig` 里两处：

```gradle
versionCode 130        // 整数，每次 +1
versionName "1.30"     // 展示用
```

**「关于」页面显示 `versionName` + `BuildConfig.GIT_COMMIT`**，
维护者靠这个**辨认手上装的是哪个包**——所以：
**每次交付 APK 都必须递增版本号**，否则无法区分新旧包。

### 交付流程（顺序不要颠倒）

1. 改代码
2. 推送到 `main` → **GitHub Actions 自动构建**
3. 下载 artifact，**装机真机验证**
4. **验证通过后**，才 commit/打 tag

**不要跳过真机验证直接宣称完成。**

---

## 5. 构建：全程云端，本地不构建

**本地不需要 JDK / Android SDK，也不要在本地跑 `./gradlew`。**
一律走 GitHub Actions：

- 触发：`push` 到 `main`，或手动 `workflow_dispatch`
- 工作流：`.github/workflows/build.yml`
- JDK 17（temurin）+ Android SDK（`platform-tools platforms;android-34 build-tools;34.0.0`）
- 产物：`davmusic-debug-apk`（artifact）

### ⚠️ 两条已踩过的坑，不要改回去

1. **Android SDK 安装必须显式指定 packages。**
   `android-actions/setup-android@v3` 默认会尝试装 `tools` 包——
   **该包已从 sdkmanager 仓库移除**，会导致整步 `exit 1`。
   必须保留 `packages:` 那行显式列表。

2. **必须用仓库内的固定 keystore 签名。**
   默认 debug keystore 由每个 CI runner 临时生成，**各次构建签名不同**，
   导致 `Conflicting app signatures`，无法覆盖安装。
   仓库内 `keystore/davmusic.p12` 是**固定 debug 签名**（无安全风险），
   由 CI 通过 `-P` 参数传入，使每次构建签名一致、支持 `adb install -r` 覆盖安装并保留数据。

### 下载 artifact 的注意事项

**务必用 `curl -L` 并加重试**——不加 `-L` 或不重试会**下载到截断的 APK**。

### 推送代码

`git push` 走 443 端口**经常超时**。
**改用 GitHub API 推送**（`PUT /repos/{owner}/{repo}/contents/{path}`）更可靠。

---

## 6. 已知实现细节（改动时注意）

- **音乐流播放**：`MediaPlayer.setDataSource(Context, Uri, Map)` 重载，
  用这个才能在流请求上带 Basic Auth 头。普通的 `setDataSource(String)` 不行。
- **PROPFIND / XML 解析**：
  - XML 命名空间前缀**不固定**（`D:` / `d:` / 无前缀），解析必须容忍
  - 用 `<collection/>` 标记判断文件夹
  - **返回列表的第一个条目是目录自身**，要跳过
- **`MusicPlayer.authHeaders`** 等字段曾在重构中被误删导致编译失败，
  动这块时注意完整性。
- **`WebDAVClient` 的 OkHttpClient 是共享的**（连接池/线程池复用），
  不要改成每台服务器一个——那是浪费。

---

## 7. 服务端背景（排障时需要知道）

生产 WebDAV 是 **OpenList**（`/dav/`），**密码与 Apache 那套 WebDAV 不通用**。
混用会导致 `401`，并累积登录失败计数触发封禁。

排障线索：

| 现象 | 含义 |
|---|---|
| `401` | OpenList 凭据错误，**或**认证挑战——两者返回相同状态码 |
| `403` | OpenList 权限未配置（WebDAV 读取/管理 + 文件系统权限） |
| `429` | **可能是上游限流，也可能是 OpenList 自己的登录锁**——差别很大 |

**排障时注意：不要用循环试密码。** 约 4 次错误尝试即触发 OpenList 的 429 锁，
之后**连正确密码也会被拒**，反而失去判断依据。

---

## 8. 日志回传（真机调试用）

App 通过 `RemoteLogger` 把日志 POST 到服务端目录（`/davmusic-logs/`），
经 Caddy（`:9339`）落到服务器本地 `/home/ubuntu/davmusic-logs`。

**这是拿真机现场的手段**——出 bug 时先看这里，不要靠猜。

---

## 9. 工作方式约定

- **根因优先**：先取真实日志/证据，再改代码。不做"猜一个改一个"的往返。
- **改动最小化**：这是能跑的产品，避免顺手重构无关部分。
- **不要重新加回本文档中标记 ⛔ 的已废弃方案。**
  如果你认为某条约定需要改，**先提出来讨论**，不要直接改。
