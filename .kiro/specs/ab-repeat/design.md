# AB repeat design

## Ownership
`MusicPlayer` owns the active repeat controller and boundary checker because it owns the MediaPlayer and must survive LyricsActivity destruction. The page is a client: it reads the controller and calls `setRepeatRange` / `clearRepeatRange`; it does not own playback. `RepeatRange` and identity/key logic are Android-free. `RepeatRangeStore` persists JSON in app-private SharedPreferences, keyed by an encoded serverId + relativePath; URLs are rejected as identities.

## Playback safety
Run a dedicated 25ms Handler callback while repeat is active and MediaPlayer is playing. Read `getCurrentPosition()` and, when position >= B, seek to A. Keep the checker separate from the existing one-second progressUpdater and do not notify progress listeners on loop seeks. Use one lifecycle-controlled runnable and ensure only one pending callback; a short seek guard prevents repeated seekTo calls while an asynchronous seek is still settling. `playFile`/track identity change and explicit `stop()` clear active selection before stopping/rebuilding; pause removes polling and resume restarts it. MediaPlayer remains in place, so queue, preload, audio focus, and track notifications are untouched. The boundary may have device/codec scheduling latency and is not sample-accurate.

## UI and persistence
Add a compact repeat panel above the shared player bar in LyricsActivity. Show live time from existing 80ms ticker; buttons set A/B, save a draft interval, and start/stop the selected interval. Rows show endpoints, a selected state, and a separate delete action. Selecting a saved row only selects it; the visible “开始复读” action activates it. Activation requires known duration, valid bounds, and prepared playback. Track changes refresh rows, clear draft endpoints and clear the active loop. Store entries as integer milliseconds only under identity `serverId + relativePath`, with no URL/credentials; the key is a URL-safe Base64 encoding of the pair.

## Test strategy
JUnit tests cover range validation, the inclusive loop boundary, identity fencing (including spaces in names and URL rejection), and controller lifecycle. A Robolectric widget test inflates the real screen and asserts the repeat controls/layout. CI is the only place Gradle/Robolectric tests run; local work is limited to static and host JVM checks per AGENTS.md.
