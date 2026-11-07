---
version: alpha
name: DavMusic Quiet Music
description: Native Android music UI with restrained surfaces and a rose accent.
colors:
  primary: "#C92C48"
  secondary: "#6E6E73"
  neutral: "#F2F2F7"
  surface: "#FFFFFF"
typography:
  body-md:
    fontFamily: sans-serif
    fontSize: 16sp
rounded:
  md: 12dp
  lg: 16dp
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#FFFFFF"
    rounded: "{rounded.md}"
---

## Overview
先满足 requirements.md 的不变性，再修改资源。无需业务架构变更。浏览、搜索、管理、配置页面保留各自层级与尺寸，用统一颜色/字体/圆角而非重排布局取得一致性。歌词继续始终深色。

## Colors
| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| primary / button_normal / icon_active | #C92C48 | #C92C48 | 实心按钮；白字可读 |
| accent / item_playing_text | #C92C48 | #FF7188 | 文本按钮、可交互图标、播放高亮文字 |
| background | #F2F2F7 | #000000 | 分组页面底色 |
| surface / toolbar_background | #FFFFFF | #1C1C1E | 内容/菜单/弹窗/顶栏 |
| text_primary / toolbar_text | #1C1C1E | #F5F5F7 | 主文字 |
| text_secondary | #6E6E73 | #A1A1A6 | 辅助文字 |
| download_state_downloaded | @color/text_primary | @color/text_primary | 已下载/下载中非播放歌曲名保持正文可读性 |
| network_online | @color/text_secondary | @color/text_secondary | 在线标签、服务器成功/在线文字使用中性灰 |
| player_background | #FFFFFF | #2C2C2E | 共享播放条 |
| item_playing_background | #FCE8ED | #3A1822 | 当前播放行 |
| lyrics_background / lyrics_header | #161619 | #161619 | 固定深色歌词，保留现有夜间体验 |

primary 不再兼任顶栏背景。工具栏/ActionBar 使用独立中性表面 token；按钮继续 primary + white，FAB 不能误用 toolbar_text。系统状态栏背景跟随表面，values/values-night 的 windowLightStatusBar / windowLightNavigationBar 控制图标明暗。Material 和 AppCompat colorAccent/colorControlActivated 使用 accent，避免深色文本按钮过暗。

## Typography
Android sans-serif；工具栏 20sp medium、配置标题保留 24sp、普通行 16sp、元数据 12–14sp。迷你条和歌词动态字号保持原值，避免 78dp 播放条和 72dp 歌词行裁切。不下载 SF 字体。

## Layout
所有 layout_width/height、padding、margin、orientation、weight、层级、ID、include、visibility、maxLines、contentDescription 原样保留；主题中现有 minHeight/padding/margin 同样不缩减。不变性测试记录修改前所有非视觉 XML 属性。允许背景、字体属性、颜色、elevation 变化，不改变命中区域。

## Elevation & Depth
顶栏去重阴影；播放条保留边界但减弱到 1dp；对话框使用 16dp 圆角，按钮/输入框 12dp。以表面明度取代厚重阴影。播放条只换背景 shape，不增加容器。

## Shapes
新增 player_surface、dialog_surface、button_surface 原生 shape；AppCompat 按钮统一继承形状主题；现有向量路径不变，仅 tint 归一。通知图标固定白色不改。

## Components
- Theme.DavMusic 统一设置系统字体、ActionBar、Toolbar、AlertDialog、ProgressDialog、普通 Button、EditText、Material 输入框与按钮，保留 DayNight 及 NoActionBar 约定。
- Activity_main / server_manage 的显式 toolbar 配色与主题同步；所有菜单原位。
- 动态 Java 视觉写入仅 MainActivity 网络状态及滑删颜色使用资源（destructive_background 固定暗红，保证白字可读）；LyricLineAdapter 现有白色/半白色和字号保留。其他 Java 不动。
- 歌词页按钮去蓝色，标题/返回继续固定白色。共享播放条依旧随系统换肤，避免添加独立控制器或改变播放逻辑。
- 表单和动态 EditText 复用主题输入底色/字色；通过 inset/padding 原有规则保持尺寸。

## Do's and Don'ts
不换导航、不添加封面/标签/控件、不变按钮文案及次序；不改 MusicPlayer、MusicService、网络、缓存和排序。禁止将静态 XML 对比当作真实 inflate 或将未执行 CI 称为通过。

## Test design
v1.55：先扩充现有 Python palette/对比度契约与 Robolectric 真实资源/在线 TextView 断言，再将上述两个角色改为语义别名。审计全部 Java/XML 引用：network_wifi / network_mobile 及 teal 遗留色未使用，保留不动；不扩大为颜色清理。Python 只证明静态契约，真实资源解析及控件颜色由 CI 执行。

先将当前布局/菜单的非视觉树、保护文件 hash、Java 规范化视觉调用后的 hash 保存为静态基线；对目标 palette/对比度编写 RED。再实施 GREEN。CI 的 VisualThemeTest 真实 inflate 五页面、三弹窗、四行布局和共享条；两种配置分别测；附菜单及播放条 click 和 AlertDialog 交互测试。本地只执行 Python，不安装 SDK 或运行 Gradle。报告保留完整命令/输出和未执行 CI 列表。
