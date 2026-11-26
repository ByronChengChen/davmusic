package com.byron.davmusic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Local-only identity policy; never uses href, a remote client, or recursive path prefixes. */
final class DirectoryDeletion {
    enum Outcome { DELETED, MISSING, FAILED }
    interface Remover { Outcome remove(String key); }
    static final class Result {
        int deleted, missing, failed, skipped;
        String summary() {
            return "已删除 " + deleted + " 首；播放/预加载跳过 " + skipped
                    + " 首；失败 " + failed + " 首；已不存在 " + missing + " 首";
        }
    }
    static List<String> select(Collection<String> keys, String serverId, String directory) {
        List<String> result = new ArrayList<>();
        if (serverId == null || serverId.isEmpty()) return result;
        String parent = directory == null ? "" : directory;
        while (parent.startsWith("/")) parent = parent.substring(1);
        while (parent.endsWith("/")) parent = parent.substring(0, parent.length()-1);
        String prefix = serverId + ":";
        for (String key : keys) {
            if (!key.startsWith(prefix)) continue;
            String rel = key.substring(prefix.length());
            int slash = rel.lastIndexOf('/');
            String actual = slash < 0 ? "" : rel.substring(0, slash);
            if (actual.equals(parent) && FileUtils.isAudioFile(rel)) result.add(key);
        }
        java.util.Collections.sort(result);
        return result;
    }
    static Result run(List<String> keys, Set<String> protectedKeys, Remover remover) {
        Result result = new Result();
        for (String key : keys) {
            if (protectedKeys.contains(key)) { result.skipped++; continue; }
            Outcome outcome;
            try { outcome = remover.remove(key); }
            catch (Exception e) { outcome = Outcome.FAILED; }
            if (outcome == Outcome.DELETED) result.deleted++;
            else if (outcome == Outcome.MISSING) result.missing++;
            else result.failed++;
        }
        return result;
    }
}
