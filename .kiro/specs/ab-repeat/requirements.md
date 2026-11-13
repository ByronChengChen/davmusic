# AB repeat requirements

## User stories
- From LyricsActivity, capture the current position as A, then B; only valid intervals with A < B and both endpoints within the known current-track duration can be saved or started.
- Save multiple A/B intervals per track; select a saved interval and press start to activate it, and delete any saved interval. Entries display mm:ss and are scoped by server ID + relative path, never URL or credentials.
- The repeat loop seeks back to A at the B boundary without rebuilding the player, changing the playlist, or causing a one-second progress-listener seek-bar flash.
- Selecting a different track or explicitly stopping playback deactivates the active loop; saved intervals remain.

## Acceptance
- No one-second callback is used for boundary precision. While active, a player-owned high-frequency position checker checks the current media position and seeks to A at/after B.
- Checker lifecycle follows active loop and player state; it is removed on deactivation, explicit stop, track replacement, and player release.
- Persist only validated values, with a stable per-track identity, and tolerate invalid/corrupt persisted entries by ignoring them.
- Lyrics screen controls: current time/duration, set A, set B, save, saved interval rows with select and delete, and start/stop repeat.
