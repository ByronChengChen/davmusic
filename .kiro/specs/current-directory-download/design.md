# 设计

DirectoryDownloadTask 是无 Android 依赖的单次串行状态机。调用者在同一线程（主 Handler）串行调用 start/cancelRemaining/完成回调；Backend 提供缓存检查和异步单首下载。先过滤目录/非音频并用 cacheKey 去重，再顺序启动，完成后再推进。重复 start 无效；取消不终止进行中的网络流；汇总 remaining 仅包含取消剩余。所有结果只结算一次。

CachedTrackDownloader 使用 application Context，按条目 serverId 查 ServerStore，用现有 WebDAVClient.forServer/download 路径；下载到唯一临时文件，完整成功后 rename 到 cacheKey 的目标并 markFileDownloaded，失败删除临时文件。主界面和单首共用该路径，禁止并行重复任务。Activity 销毁时断开 listener 并取消剩余，避免持有 Activity；当前首仍能完成缓存登记。

DownloadTaskDialog 自定义内容和唯一取消剩余按钮，完成后按钮变为关闭，避免原生 AlertDialog 多按钮间距问题。菜单可重新打开任务进度；导航不取消；完成回调对当前列表重新按 cacheKey 刷新，不将旧文件写入新目录。完全不调用 MusicPlayer 队列 API。

验证：先写 JUnit 状态机测试和 Robolectric 对话框测试，再实现。主机仅运行允许的 Python/JVM 检查，Android 编译/全量单测均在 CI。APK 验证版本、提交号、zip、大小和 SHA256；真机检查弱网失败、取消/导航、离线播放、菜单与大字体。
