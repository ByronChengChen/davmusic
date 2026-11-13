package com.byron.davmusic;

/** Validated A/B segment in milliseconds. */
public final class RepeatRange {
    private final int startMs;
    private final int endMs;
    private RepeatRange(int startMs, int endMs) { this.startMs = startMs; this.endMs = endMs; }
    public static String trackKey(String serverId, String relativePath) {
        if (serverId == null || serverId.isEmpty() || relativePath == null || relativePath.isEmpty()
                || relativePath.contains("://") || relativePath.startsWith("http:") || relativePath.startsWith("https:")) return null;
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (serverId + "\u0000" + relativePath).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static RepeatRange create(int startMs, int endMs, int durationMs) {
        if (durationMs <= 0 || startMs < 0 || endMs < 0 || startMs >= durationMs || endMs > durationMs) return null;
        return startMs < endMs ? new RepeatRange(startMs, endMs) : null;
    }
    public int getStartMs() { return startMs; }
    public int getEndMs() { return endMs; }
    @Override public boolean equals(Object o) {
        return o instanceof RepeatRange && ((RepeatRange) o).startMs == startMs && ((RepeatRange) o).endMs == endMs;
    }
    @Override public int hashCode() { return 31 * startMs + endMs; }
    public boolean shouldLoopAt(int positionMs) { return positionMs >= endMs; }
    public int loopPosition() { return startMs; }
}
