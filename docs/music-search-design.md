# 本地快照歌曲搜索（v1.52 CI 交付预备，未真机验收）

## 边界
- 数据只来自 `filesDir/snapshots/*.json`：所有当前配置服务器的已浏览目录。不是全库，不扫描远端、不递归、不建数据库，不用旧 offline_index。
- `LocalCacheManager` 负责持久快照与递增 revision；同一监视器串行发布/删除/清空/汇总，沿用 AtomicSnapshotWriter。目录刷新覆盖整份快照，空目录同样覆盖。
- 请求发起时捕获快照清空 epoch，清空后旧网络响应不可回写（清空并非禁止未来新浏览）。服务器删除后即时按当前清单过滤；不删除其历史磁盘文件，不改变播放队列。

## 搜索与并发
- `SnapshotSearchEngine` 纯逻辑：过滤音频/已删除服务器，按 serverId + relativePath 去重；只搜不带最后扩展名的文件名，trim、Locale.ROOT 小写、空白分词 AND；空输入返回空。
- `SnapshotSearchController` 单工作线程后台汇总，revision 不变时复用内存，仅做关键词匹配。查询/源变更/销毁递增 generation，交付时再次校验 generation 与 revision，旧工作不能覆盖新状态。
- 排序唯一使用 TrackSorter；后台使用比较器时需消除共享 Collator 的线程不安全。
- Activity 只持有搜索状态，不写主页面目录/排序/滚动。销毁注销快照、配置、播放监听，关闭控制器并取消 UI 回调。
- MainActivity 在 savedInstanceState 保存 serverId、目录/返回栈、搜索返回标记及完整 LayoutManager Parcelable（含首行像素偏移）。先按 id 恢复服务器再加载；服务器已删除则丢弃旧导航/滚动，安全退回服务器根列表。AdapterDataObserver 在数据填入后 post 恢复，保留未就绪状态，不再用 300ms 定时猜测；消费一次，发布前复核服务器/目录，普通导航使旧待恢复状态失效。

## 播放与 UI
- 独立 SearchActivity：搜索/清空/返回、覆盖范围说明、加载/数量/空提示；结果歌曲名、父目录、当前服务器别名、实时下载状态；高亮用跨服务器身份，非 href。
- 点击时复制完整已排序可见结果交给 MusicPlayer.setPlaylist，从点击下标 playFile，启动现有 MusicService。输入、清空、退出都不动队列。
- 播放复用 MusicPlayer 本地优先/所属服务器取流；点击前离线且未下载明确拒绝，不改队列；陈旧远端正常播放器报错。不新增重试/服务/权限。
- 底部 include view_player_bar + PlayerBarController；垂直权重列表与 adjustResize 使键盘上方保留播放条；所有颜色来自既有深浅主题资源。

## 实际本地验证记录

- `python3 scripts/test_music_search_jvm.py`：`OK (71 tests)`；随后 `Storage checks PASS (replacement, empty refresh, corruption, revision, clear epoch, concurrent clear)`。编译真实生产逻辑与 LocalCacheManager，Android Context/Preferences/Log 为明示 host stub，org.json 为真实 JVM 库；存储使用真实临时文件。没有运行 Robolectric/MediaPlayer/UI。
- `python3 scripts/check_music_search.py`：`PASS: XML parsed; search manifest/layout/resource/lifecycle/queue integration contracts`。
- JavaParser 3.26.3 / JAVA_17 解析整个 app/src：44 个 Java 源文件通过（语法检查，不是 Android attribution/编译）。直接无 SDK javac 因缺少 AudioManager 接口报旧代码 lambda attribution 错误，故未把它冒充语法失败或 Android 编译。
- `python3 scripts/test_search_return_jvm.py`：`Search return JVM PASS`。从真实 MainActivity 原样提取保存/恢复方法在纯 JVM 执行；明确 stub Bundle/RecyclerView/LayoutManager，不运行 Android 生命周期或 Parcelable 编解码。覆盖 server/path/stack、搜索返回标记、慢数据、完整位置/偏移、一次消费、二次重建、已删除服务器、服务器 `/` 根及导航竞态。初次 RED 实测 `AssertionError: recreation lost server b (must not choose first server/root)`，修复后 GREEN。
- `git diff --check`：无输出、退出 0。
- RED 实测：文件名匹配期望 1 得到 0；聚合过滤期望 2 得到 7；异步三个场景旧回调错误交付；存储发布未递增 revision；跨服务器相同 href/路径被误判同首。各失败后实现并重跑到 GREEN。

## 验证与交付
用户已授权升版 versionCode 152 / versionName 1.52、提交推送与 GitHub Actions 编译出包；独立审查及本地回归已通过。不跑本地 Gradle/Android SDK，不在真机验收前打 tag。CI 以实际运行报告为准；键盘/主题/主页面返回/跨服务器连续播放仍待真机验收。
