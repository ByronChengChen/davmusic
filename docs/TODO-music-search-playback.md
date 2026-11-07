# 音乐搜索与播放 — 本地快照方案（v1.52 CI 交付预备）

旧的「递归扫描整台服务器、并发 3、4000 目录/10 分钟、ScanService、library_index.json」提案已被本方案替代，**不再作为待实现任务**。本次不增加扫描服务、权限、数据库或服务端私有搜索接口。

## 已确认并编码的范围

- 搜索所有当前已配置服务器的**持久目录快照**中的音频，跨目录/跨服务器；只覆盖浏览过并缓存的目录，不是完整远端音乐库。
- 仅文件名部分匹配：去最后扩展名、忽略大小写和首尾空白、空白分词 AND；空查询不列全量，不搜目录/服务器别名/标签/拼音。
- 独立 SearchActivity，主页面搜索按钮进入；输入/清空/返回、加载/数量/空提示，结果显示歌曲名、父目录、当前服务器别名、下载状态、正在播放高亮。使用现有深浅色资源、view_player_bar 和 PlayerBarController。
- TrackSorter 的全局名称/修改时间排序；条目按 serverId + relativePath 去重，删除的服务器过滤，不混淆同名服务器路径。
- 只有点击结果才复制完整排序结果建立 MusicPlayer 队列，从该歌曲播放。搜索词变化、清空和退出不改队列、不自动播放。返回不切原目录/排序，并恢复滚动。
- MusicPlayer 本地优先，否则所属服务器取流；点击时离线无本地文件明确提示，不改原队列。快照不保证远端还存在，播放错误沿用原路径，不加重试。
- 后台汇总快照、内存匹配；generation/revision 防迟到结果覆盖；刷新空目录也替换快照，新增/刷新/删除/清空/服务器变更失效更新，损坏单份跳过。
- 原子写使用已存在 AtomicSnapshotWriter；LocalCacheManager 串行发布/清空/删除/读取，清空 epoch 防止旧目录网络回调重新写入。本次不顺手修旧 offline_index/离线目录读取键问题。

## 实现与状态边界

见 [music-search-design.md](music-search-design.md)；需求 R16 与任务 33 已写入 `.kiro/specs/davmusic/`。

## 验证

- `python3 scripts/test_music_search_jvm.py`：真实生产 Java + 明示 Android host stub，JUnit 与真实文件系统共享检查；不是 Android 编译或 Robolectric。
- `python3 scripts/check_music_search.py`：XML 解析、资源引用、manifest、播放队列/生命周期静态契约；不是 UI 实测。
- `git diff --check`。
- 已授权 versionCode 152 / versionName 1.52 提交推送与 GitHub Actions 出包；不跑本地 Gradle/Android SDK、不改服务、不提前打 tag。

## 尚待验收

- [x] 独立代码审查：快照清空/发布并发、Activity 生命周期与过期结果、跨服务器队列身份。
- [ ] 已授权的 CI Android 编译、JUnit/Robolectric（包括 StorageTest）。
- [ ] 真机：主页面原目录/排序/滚动返回不变；键盘不挡共享播放条；深浅主题。
- [ ] 真机：跨服务器同名歌曲高亮、点击结果后连续切歌；输入/清空/退出不改队列；离线有/无下载、陈旧远端错误。
- [ ] 真机：搜索期间刷新空目录/清空缓存/删除服务器，不能出现旧结果回写。

**状态明确：v1.52 CI 交付预备，CI 结果以实际运行报告为准，真机项未验证，不宣称完成验收。**
