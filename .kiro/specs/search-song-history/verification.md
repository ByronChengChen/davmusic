# v1.56 验证记录（可迁移，不含账号数据）

- 需求与设计先写入本目录；AGENTS.md 未改，本地未运行 Gradle/Android 构建。
- Store 首个 host RED：缺少历史持久化行为 AssertionError；实现后重启/身份/凭据排除检查 GREEN。
- Host JUnit Store：12 项通过。追加大小写敏感根路径测试先红（同 ID 改 Music→music 被旧 getScopeId 混淆），改为精确 URL/用户名 SHA-256 后绿；长 Unicode 50 项测试先红（512 KiB 上限导致读回为空），调整为 1 MiB（覆盖有界字段最坏编码）后绿。
- CI 42385188307 / 42385344445：测试夹具的 ShadowMediaPlayer 未配置 duration，在 Activity 初始化时报 NPE；修正独立 singleton 与空闲 player 状态，不改生产播放器、不删断言。
- 有效 widget RED：42385507977，141 tests / 7 failed；实际历史列表为空、控件缺失与旧行点击路径失败，搜索结果分支通过。
- 初次实现 GREEN：42385760704，所有测试及 APK 构建成功。最终交付以进一步配置/边界测试后的精确 run 与 APK 校验报告为准。
- 本地 Python 资源检查通过；host 静态测试 10 项通过。旧视觉刷新断言限定到用户批准新增的两个控件/五个字符串与新行为类，其余原控件/页面及非业务改动仍校验。
- 真机未验收，不打 tag。远端删除不可离线证明，不预检/不重试，沿原错误路径；音频解码/弱网/主题布局/覆盖安装保留配置待设备确认。
