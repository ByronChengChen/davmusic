# AB repeat tasks

- [x] Requirements and architecture design; define ownership, persistence, loop lifecycle, and test scope.
- [x] Add JVM-first tests for range bounds, identity, and controller lifecycle; add a Robolectric widget layout/control assertion.
- [x] Implement pure range/controller/store classes and execute host JVM cases.
- [x] Integrate precise repeat boundary checker into MusicPlayer without progress notifications; add explicit stop/track/pause/release lifecycle handling.
- [x] Add repeat controls/list to LyricsActivity with saved interval, selection, activation, and deletion actions.
- [x] Run static checks and verify final repository status.
- [x] 底部半透明复读弹窗及 Material Design 3 语义浅深色配色；不影响全局按钮主题。
- [x] 顶部按钮、选择与 ×、上下区间行均设置 8dp 间距；写入 UI 硬性规范并补动态行回归测试。
- [x] v1.68 云端 CI：run 43554497840，提交 13f2b6a，158 项测试、0 失败；LyricsRepeatWidgetTest 6 项全部通过，Android APK 构建成功。
- [x] 用户明确确认复读功能真机测试无问题。
- [x] 根据验收完成需求、设计及任务文档收尾。
