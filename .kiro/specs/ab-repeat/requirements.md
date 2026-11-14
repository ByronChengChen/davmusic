# AB repeat requirements

状态：v1.68（versionCode 168），用户已确认复读功能真机测试无问题。验收的 APK 对应提交 `13f2b6a`。

## User stories
- From LyricsActivity, capture the current position as A, then B; only valid intervals with A < B and both endpoints within the known current-track duration can be saved or started.
- Save multiple A/B intervals per track; select a saved interval and press start to activate it, and delete any saved interval. Entries display mm:ss and are scoped by server ID + relative path, never URL or credentials.
- The repeat loop seeks back to A at the B boundary without rebuilding the player, changing the playlist, or causing a one-second progress-listener seek-bar flash.
- Selecting a different track or explicitly stopping playback deactivates the active loop; saved intervals remain.

## Acceptance
- No one-second callback is used for boundary precision. While active, a player-owned high-frequency position checker checks the current media position and seeks to A at/after B.
- Checker lifecycle follows active loop and player state; it is removed on deactivation, explicit stop, track replacement, and player release.
- Persist only validated values, with a stable per-track identity, and tolerate invalid/corrupt persisted entries by ignoring them.
- 歌词页右上角更多菜单 →「复读」打开底部设置弹窗；包含当前区间/时间、设为 A、设为 B、保存、区间选择/删除及开始/停止复读。
- 配色采用 Material Design 3 语义角色，支持浅深色；主操作保留品牌红，次操作、选中容器和删除错误容器分层。文字对比度目标至少 4.5:1。
- 独立按钮任何情况下不得紧贴：顶部 A/B/保存之间、选择与右侧 × 之间、上下区间行之间均保留至少 8dp 显式间距，不依赖 drawable inset。
- 本次验收范围不包含有道笔记、查词/单词收藏，不能据复读验收将这些功能标为完成。
