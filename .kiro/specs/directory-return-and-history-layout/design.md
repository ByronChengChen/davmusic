# 设计与验证

## 历史记录行
item_search_result 改为横向布局：左侧 weighted 纵向文字容器保留标题/位置/下载状态，右侧原删除按钮保留 48dp 触摸高度。按钮隐藏时文字占满可用宽度。SearchActivity 点击逻辑不变。SDK-free XML 合同先因 vertical 失败，修改后通过；新增 Robolectric 实际 measure/layout 几何断言，随云端测试执行。

## 返回父目录
MainActivity 保留仅对应当前导航祖先的 Bundle 栈，记录服务器 ID、目录路径和 LayoutManager Parcelable（含像素偏移）。进子目录前捕获，返回时弹出；服务器列表另存一份。复用 pendingScrollState 的数据到达后恢复机制，新的导航清除待恢复引用，使已 post 的旧恢复回调失效。空列表不提前消费恢复；首次进入目录仍回顶部。祖先和服务器列表状态随 Activity 保存/恢复，旧版本没有祖先状态时安全回退。

## 验证边界
- Host 脚本抽取生产导航/save/restore 方法，以显式 UI stub 运行，覆盖多级目录、服务器归属、延迟数据、偏移、重建及导航竞态；不是 Android 生命周期或 Parcelable 编码证明。
- 既有搜索返回/搜索更新/搜索存储测试需回归。
- GitHub Actions 执行 JUnit/Robolectric 并编译 APK；本机不运行 Gradle/Android 构建。
- 真机重点检查深层目录返回、服务器列表返回、搜索历史删除、浅深主题及长名称换行。
- 重命名不在本版本实现。
