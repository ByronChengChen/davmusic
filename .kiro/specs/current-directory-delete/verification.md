# 验证记录

## 本地（禁止 Gradle）
- 测试先行：tools.test_directory_delete 初始实际失败（缺少 DirectoryDeletion），实现后纯 JVM 范围/汇总通过。
- `python3 -m unittest discover -s tools -v`：12 项通过（含 XML、菜单旧结构/相邻新项、版本契约；这些是静态检查，不是 Android UI 实测）。
- `scripts/test_music_search_jvm.py` 增加 DirectoryDeletion 依赖及永久 JUnit 类；原脚本首次暴露缺少新增依赖，已修正。
- 父目录返回、搜索返回、搜索更新三个纯 JVM harness 均通过。
- 新增 DirectoryDeletionTest / CacheTest / PlaybackTest / UiTest；其中 Robolectric 只允许云端运行。

## 已知保守边界
- 当前/暂停歌曲、已挂载及在途预加载、待回收预加载跳过。取消后的 prepare 身份保持到实际回调释放；异常无回调时可能持续保守跳过，不擅自释放播放器或断流。
- 本地批次在主线程同步执行，不读音频内容；超大目录和低速存储的耗时需真机验证。
- 旧无 serverId 缓存不猜归属，不包含；异常或共享映射拒删并计失败。
- 删除文件后映射/索引使用现有 SharedPreferences apply 机制，进程被强杀的崩溃原子性并非新引入保证。

## 云端及真机
用户追加授权必要 commit/push 与 GitHub Actions 出包；不打 tag。云端精确 SHA、报告与 APK 校验结果由交付记录补充；真机尚未验收。
