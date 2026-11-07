# 设计

SearchSongHistoryStore 为独立本地元数据 store，不依赖播放器。私有 filesDir/search-song-history.bin，版本化 DataInput/DataOutput UTF-8 字段记录，限制 50 条及文件 1 MiB，使用既有 AtomicSnapshotWriter fsync + 原子 rename。所有实例 IO 以类锁串行，每次操作读当前文件，防止旧实例清空后复活。写失败保留旧文件并返回 false，不阻塞正常播放。非法路径（空、绝对、点段、反斜杠、控制字符）拒绝；保留合法 +、空格、百分号等文件名。记录 scopeId（不含密码）以拒绝同 ID 改 URL/账户后误播；改别名/密码不丢失历史。解析当前配置后构造新的 WebDAVFile，不复制旧 href/URL/本地路径。

SearchActivity 维护 historyMode；空查询 invalidate 搜索世代并读历史，非空保持 SnapshotSearchController，回调同时防御空查询。结果 adapter 复用现有行状态增量更新，历史行额外显示删除按钮，顶部清空历史按钮只在空查询时显示。点击入口在任何队列变更前重新校验条目；历史分支先 resolve 当前身份再设 singletonList；搜索分支保持 adapter.files 快照及点击下标。通过网络/本地下载前置校验后记录并调用原播放逻辑。清空/删除只刷新历史 UI。配置变化经现有 listener 更新，resume 复核历史，destroy 清理不变。不改 MainActivity、MusicPlayer 或远端。

# 验证顺序
需求设计先落盘；store 行为先用真实文件纯 JVM tracer bullet 测试 RED→GREEN；Activity widget 行为测试先提交为红的 CI，再实现对应分支并以 CI 证明绿。CI 运行全部 testDebugUnitTest 后构建固定签名 APK。保存非敏感报告；下载精确 head_sha/run artifacts 并校验 API 大小/digest、ZIP CRC、APK manifest/dex/证书。
