# 当前目录删除本地下载（1.70 / 170，待云端及真机验证）

## 需求与边界
- 溢出菜单在“下载当前目录歌曲”之后新增“删除当前目录已下载歌曲（仅本地）”；服务器列表隐藏。
- 只当前 serverId、当前目录直接音频子项，不递归。根据本地下载映射构造快照，包含远端目录列表已不再显示的缓存，不发起目录请求，更绝不远端 DELETE。
- 二次确认明确目录、数量、不递归、不影响远端；取消零副作用。确认时重新核验下载冲突和播放保护。
- 汇总删除成功、播放/预加载跳过、删除失败、已不存在的数量。失败保留文件映射/离线索引，缺失文件清理陈旧映射。
- 删除不改队列、排序、歌词、目录快照及网络缓存；下载标记重算，并使用已有下载监听通知搜索页。

## 169 审计与设计
DirectoryDownloadTask.cancelRemaining 仅阻止后续项，不取消当前网络传输；CachedTrackDownloader 的主线程成功回调还会 rename 和 markFileDownloaded，Activity 销毁后仍会完成。故本功能不以取消代替互斥：存在任何 activeKeys（含其他 Activity/销毁后传输）或当前未完成任务时拒绝整批删除；确认之后再检查。同步主线程批次与 activeKeys 锁互斥，锁内完成文件删除/映射清理，避免迟到发布复活。

LocalCacheManager 旧 removeDownloadedFile 先删映射且忽略 File.delete 结果；新增安全返回结果接口，先验证普通文件删除成功再删映射，并同步移除同 cacheKey 离线索引，通知下载监听。候选按 map key 的完整 serverId 前缀及直接父路径筛选，不回退无归属旧键。只允许缓存 music 目录内预期哈希目标，拒绝异常/共享指向，避免删除其他缓存。

MusicPlayer.resolvePlayUrl 优先映射；队列不是列表。主线程读取当前曲目、preloadedPosition、preparingPosition 与待回收实例的身份，保护这些 cacheKey（含暂停/准备中）；不取消预加载、不重拉流、不 release、不停止播放器。pendingRelease 必须保存取消前的身份直至回收。所有持有状态均只读获取。

确认和汇总使用自定义横向按钮区，按钮间显式 marginEnd=8dp，不能依赖系统 AlertDialog 默认间距。

## 测试先行与验收
先编写纯 JVM 范围/汇总测试及 Python host harness，观察缺失实现失败；再实现。Robolectric 覆盖真实文件、持久映射/离线索引、失败目录、异常目标、跨服务器、取消/确认、下载冲突、播放器准备/预加载/待回收边界、菜单旧动作和按钮间距。本地禁止 Gradle，允许 Python、javac/java、静态资源契约；Android 编译及 Robolectric 必须另经授权云端运行，不能把本地静态通过称为 Android 验证。
