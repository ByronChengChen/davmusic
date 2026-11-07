# v1.54 本地验证报告

## 已实现
- 按 requirements.md → design.md → 编写资源验收测试并观察 RED → 实施 → GREEN 的顺序完成。
- 白/分组灰、纯黑/炭黑表面，红粉强调色、原生系统字体、12dp 按钮/输入框圆角、16dp 弹窗，降低顶栏/播放条阴影。
- 顶栏背景与 primary 解耦，避免白底白字及 FAB 黑字；ActionBar/Toolbar 菜单 overlay、返回/新建/搜索图标、系统栏明暗与浅深主题对齐。
- 保留所有布局树、控件 ID、菜单顺序、尺寸/边距/触摸区域、可见性、文字、include；Java 只改 MainActivity 三处颜色资源。歌词仍固定深色；业务逻辑未改。
- 版本从 153 / 1.53 增至 154 / 1.54。未改 AGENTS.md、manifest、strings、CI 工作流、签名、权限、应用 ID。

## 实际执行证据
完整命令及原始输出在 reports/：
- host-red.txt：实施前运行 8 个资源/静态契约方法，目标 palette、歌词蓝色、版本等出现预期失败。初次整套运行也含缺失新 token 引起的 KeyError 和旧 ARGB 色格式失败；这不是 Android 测试 RED。目标 palette 独立断言也已实际失败（#FFFFFF != #F2F2F7、#121212 != #000000）。
- host-green.txt：最终 `python3 -m unittest discover -s tools -p test_visual_refresh.py -v`，**9 tests，OK，exit 0**。
- 静态覆盖：**60 XML、13 layouts、1 menu、浅/深各 53 color tokens**。XML 解析/本地资源引用、布局语义与几何/菜单结构相对 3a192b0 不变、保护文件及 Java 非视觉逻辑、颜色对齐、11 对文本/背景 × 2 模式均达到 4.5:1、版本仅两处变更。
- jvm-regression.txt：使用 javac/java 17 在 TMPDIR 编译实际 AtomicSnapshotWriter + 原有 AtomicSnapshotWriterChecks，**7 个真实文件系统场景全 PASS**（创建、替换、两类写失败、发布前读取、move 失败、并发）。这是未修改的缓存核心的额外回归，不是 UI 测试。
- diff-check.txt：`git diff --check`，exit 0。

## 已编写、未运行
`VisualThemeTest.java` 六个 Robolectric 测试：两种主题真实 inflate 全部 13 布局并测量、菜单 12 项顺序、三页面共享播放条控件点击/seek 事件分发、密码字段与保存初始禁用状态、两主题服务器弹窗确认/取消。它不替代完整 Activity 播放回归：播放条测试挂接的是测试 listener，仅证明控件可交互及回调分发，不证明生产播放动作。

**未运行任何本地 Gradle/Android 构建，也未 commit/push/触发远端。** 因而新增 Robolectric 类没有编译或执行证据；Android 资源链接、完整 Java 符号解析、运行时主题属性/弹窗 window、APK 安装、设备外观均尚未验证。

## 获授权后的首次云端执行
- 已获本轮提交/推送/构建授权，首次 run： https://github.com/ByronChengChen/davmusic/actions/runs/42382123360 （d45bb522cf9a9466f42d6f1a533f9699dddc5133）。
- 资源链接、生产与测试 Java 编译成功；123 tests / 1 failed。HTML 确认弹窗点击后计数仍为 0，且提示 Main looper 有未执行 runnable：AppCompat 弹窗按钮经 Handler 异步分发，测试立即断言过早。
- 仅在测试点击后 idle main looper，保留原确认次数断言；生产代码不变。后续成功 run / APK 校验在仓库外的交付验证报告记录，避免为结果文档重复触发构建。
- host-red.txt 仅清理两行尾随空格，不改变原失败内容。

## 获授权后的云端门禁
在现有 GitHub runner（JDK17 + Android34 SDK）先执行：

```sh
python3 -m unittest discover -s tools -p 'test_visual_refresh.py' -v
./gradlew testDebugUnitTest --no-daemon --stacktrace
```

单独定位新测试：

```sh
./gradlew testDebugUnitTest --tests com.byron.davmusic.VisualThemeTest --no-daemon --stacktrace
```

测试通过后按现有 `.github/workflows/build.yml` 的固定签名参数 `assembleDebug`，检查真实单元测试 HTML 报告。下载 APK 后验证 ZIP/包名/版本/签名连续性再命名 `DavMusic154.apk`。不得将旧 HEAD 的 workflow_dispatch 产物当成本次改动 APK。

## 真机验证清单
1. 主界面、搜索、服务器管理、配置页及全部弹窗/菜单：分别浅深色，图标/按钮/输入文字可读，无蓝色遗留；歌词主体仍固定深色。
2. 360dp 窄屏与大字体：78dp 共享播放条、72dp 歌词行、顶栏标题及输入法下表单不裁切；原有尺寸未改不等于新主题渲染已验收。
3. 浏览/搜索/歌词的原播放模式、上一首、播放暂停、下一首及 seek；菜单全部入口顺序不变，下载/删除/上传/服务器编辑仍按原方式操作。
4. 系统主题切换后页面重建与持续播放；通知和锁屏控制不变，无需新增功能验收。
