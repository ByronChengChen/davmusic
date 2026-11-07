package com.byron.davmusic;

import java.util.*;

/** Filename matching over an already loaded snapshot; never performs IO. */
public final class SnapshotSearchEngine {
    private SnapshotSearchEngine() {}

    public static List<WebDAVFile> collect(List<WebDAVFile> files, Map<String, String> owners) {
        Map<String, WebDAVFile> unique = new LinkedHashMap<>();
        for (WebDAVFile file : files) {
            if (file == null || file.isCollection() || !file.isAudio()
                    || !owners.containsKey(file.getServerId())
                    || file.getRelativePath() == null || file.getRelativePath().isEmpty()) continue;
            file.setServerName(owners.get(file.getServerId()));
            unique.put(file.getCacheKey(), file);
        }
        return new ArrayList<>(unique.values());
    }

    public static String title(WebDAVFile file) {
        String name = file.getDisplayName() == null ? "" : file.getDisplayName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static List<WebDAVFile> search(List<WebDAVFile> files, String query, int mode) {
        List<WebDAVFile> result = new ArrayList<>();
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return result;
        String[] tokens = normalized.split("\\s+");
        for (WebDAVFile file : files) {
            String name = title(file).toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String token : tokens) if (!name.contains(token)) { matches = false; break; }
            if (matches) result.add(file);
        }
        result.sort(TrackSorter.comparator(mode));
        return result;
    }
}
