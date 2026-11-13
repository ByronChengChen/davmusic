package com.byron.davmusic;

/** Android-free state machine for the active segment and current track. */
public final class RepeatController {
    private String trackKey;
    private RepeatRange active;
    public synchronized void select(String key, RepeatRange range) {
        if (key == null || key.isEmpty() || range == null) { deactivate(); return; }
        trackKey = key; active = range;
    }
    public synchronized void onTrackChanged(String key) { if (trackKey != null && !trackKey.equals(key)) deactivate(); }
    public synchronized void onExplicitStop() { deactivate(); }
    public synchronized void deactivate() { active = null; trackKey = null; }
    public synchronized boolean isActive() { return active != null; }
    public synchronized boolean isActiveFor(String key) { return active != null && trackKey != null && trackKey.equals(key); }
    public synchronized boolean isActiveRange(String key, RepeatRange range) { return isActiveFor(key) && active.equals(range); }
    public synchronized String getTrackKey() { return trackKey; }
    public synchronized RepeatRange getActive() { return active; }
}
