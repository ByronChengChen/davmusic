# AB repeat design

## Ownership
`MusicPlayer` owns the active repeat controller and boundary checker because it owns the MediaPlayer and must survive LyricsActivity destruction. The page is a client: it reads the controller and calls `setRepeatRange` / `clearRepeatRange`; it does not own playback. `RepeatRange` and identity/key logic are Android-free. `RepeatRangeStore` persists JSON in app-private SharedPreferences, keyed by an encoded serverId + relativePath; URLs are rejected as identities.

## Playback safety
Run a dedicated 25ms Handler callback while repeat is active and MediaPlayer is playing. Read `getCurrentPosition()` and, when position >= B, seek to A. Keep the checker separate from the existing one-second progressUpdater and do not notify progress listeners on loop seeks. Use one lifecycle-controlled runnable and ensure only one pending callback; a short seek guard prevents repeated seekTo calls while an asynchronous seek is still settling. `playFile`/track identity change and explicit `stop()` clear active selection before stopping/rebuilding; pause removes polling and resume restarts it. MediaPlayer remains in place, so queue, preload, audio focus, and track notifications are untouched. The boundary may have device/codec scheduling latency and is not sample-accurate.

## UI and persistence
LyricsActivity stays visually uncluttered: the header exposes one overflow button, whose menu offers “复读”. Selecting it opens a themed repeat-settings dialog; the A/B, save, saved interval selection/deletion, and start/stop controls live only in that dialog. The dialog can be dismissed without discarding the draft A/B points or saved intervals. The existing 80ms ticker refreshes repeat status only while the dialog is visible. Track changes refresh the open dialog, clear draft endpoints, and clear the active loop. Store entries as integer milliseconds only under identity `serverId + relativePath`, with no URL/credentials; the key is a URL-safe Base64 encoding of the pair.

## Test strategy
JUnit tests cover range validation, the inclusive loop boundary, identity fencing (including spaces in names and URL rejection), and controller lifecycle. A Robolectric widget test inflates the real screen and asserts the repeat controls/layout. CI is the only place Gradle/Robolectric tests run; local work is limited to static and host JVM checks per AGENTS.md.
