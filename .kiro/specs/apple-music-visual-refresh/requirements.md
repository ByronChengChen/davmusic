# DavMusic v1.55：Apple Music 气质的纯视觉换肤

## 目标与边界
在现有 Android 原生界面中采用克制的浅/深色表面、红粉强调色、清晰字体层级和柔和圆角。不是复制 Apple Music 产品，不使用 Apple 标志、SF 专有字体、专有图标或从 APK 提取的素材。字体使用 Android 系统 sans-serif。

页面、入口、按钮、菜单顺序、文案、可见性、控件 ID、导航、事件及触摸范围全部保持；不添加底部标签栏、专辑封面、播放页或新功能。不改变播放、网络请求、队列、缓存、排序、持久化、签名、应用 ID、权限。保留歌词页固定深色的现有产品行为，其余页面跟随系统浅深色。

## 逐页面覆盖 / 验收矩阵
| 页面/组件 | 实际实现 | 必须保留 | 视觉验收 |
|---|---|---|---|
| 初始服务器配置 | activity_server_config / ServerConfigActivity | 地址、账号、密码显隐、测试及保存/禁用状态、说明 | 中性色标题、圆角输入框、红色操作 |
| 浏览根目录与服务器目录 | activity_main / MainActivity | 路径、返回、网络状态、刷新列表、上传 FAB、迷你条 | 浅/深中性顶栏、红色图标、无蓝色遗留 |
| 文件行 | item_file / FileListAdapter | 文件名/大小/时长、下载/删除、进度、长按、滑删 | 原有尺寸、清楚的红粉播放高亮、状态颜色可读 |
| 搜索 | activity_search / item_search_result / SearchActivity | 返回、输入、清空、覆盖提示、进度、结果/状态、播放条 | 同一表面与字体层级，结果更新逻辑不变 |
| 歌词 | activity_lyrics / item_lyric_line / LyricLineAdapter | 返回、标题/副标题、状态、搜索歌词、逐行/双击、播放条 | 固定炭黑、白色歌词、红色搜索，72dp 行高及动态字号不变 |
| 服务器管理 | activity_server_manage / item_server / ServerManageActivity | 列表、空态、添加、编辑/长按、删除、隐藏徽标 | 与浏览页相同中性顶栏和按钮 |
| 服务器编辑 | dialog_server_edit | 四字段、测试结果/测试按钮、保存取消 | 同色系表面、输入及按钮 |
| 歌词搜索及结果选择 | dialog_lyrics_search / LyricsActivity | 查询、说明、搜索、取消、版本顺序 | 主题化弹窗、输入、列表 |
| 上传 | dialog_upload_progress / MainActivity | 进度、文字、计数、取消及选择来源 | 红色进度、主题化弹窗 |
| 主菜单 | main_menu | 搜索、新建文件夹、刷新、服务器、排序、定位、设置、清缓存、端点、令牌、上报、关于的原顺序/显示策略 | 中性菜单表面、可见图标 |
| 动态弹窗 | MainActivity / ServerManageActivity | 服务器操作/删除、文件详情/重命名/分享、删除确认、新建目录、上传来源、清缓存、关于、排序、端点及令牌输入、连接/下载 ProgressDialog | AppCompat 对话框及旧 ProgressDialog 统一主题，按钮红色 |
| 系统栏/通知/图标 | themes / drawables / MusicService | 原有通知按钮、应用图标形状和动作 | 系统栏浅深可读；通知保持系统白色蒙版；启动图标仅换背景颜色 |

## 验证标准
1. 可执行 Python 资源检查：全量 XML 解析、浅深色 token 对齐、正文/次要文字及强调文字对比度、菜单/布局语义和结构基线不变、Java 非视觉逻辑不变。
2. CI 的 Robolectric：真实 inflate 所有布局、真实菜单顺序、共享播放条控件/点击回调、浅深主题颜色及对话框按钮。源码字符串检查仅称静态约束，不称 Android 运行测试。
3. 本地严禁 Gradle、Android 编译/APK 构建；本轮已获提交 main / 云端构建授权。交付版本 155 / 1.55。
4. APK 构建和真机浅深色、窄屏/大字体、输入法与播放手势验证通过前，不声称视觉验收或可安装性已完成。

## v1.55 去绿微调（用户已确认）
- 收敛为红粉 + 黑白灰：播放高亮、主操作、进度维持红粉；错误、离线、删除保留警示红。
- 已下载（以及复用同一名称颜色的下载中）歌曲名使用 text_primary，不降为次要灰文字；在线状态使用 text_secondary。依靠已有图标/文字区分状态，不新增控件。
- 仅修改浅/深 colors.xml 中实际使用的两个角色及版本、测试和本文档；不改功能、布局、按钮、事件或歌词固定深色策略。未使用的旧颜色不清理。
