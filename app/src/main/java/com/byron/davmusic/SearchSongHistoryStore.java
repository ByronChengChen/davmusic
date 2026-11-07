package com.byron.davmusic;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Bounded click history. No URLs, credentials, playback state or remote operations. */
final class SearchSongHistoryStore {
    private static final int MAGIC = 0x444d4801;
    private static final int LIMIT = 50;
    private static final long MAX_BYTES = 512 * 1024;
    private final File target;
    SearchSongHistoryStore(File target) { this.target = target; }
    private static final class Entry {
        String id, path, name, mime, scope;
        Entry(String id, String path, String name, String mime, String scope) {
            this.id = id; this.path = path; this.name = name; this.mime = mime; this.scope = scope;
        }
        WebDAVFile file(ServerProfile server) {
            WebDAVFile f = new WebDAVFile(); f.setOwner(server); f.setRelativePath(path);
            f.setDisplayName(name); f.setContentType(mime); return f;
        }
    }
    private List<Entry> read() {
        List<Entry> entries = new ArrayList<>();
        if (!target.isFile() || target.length() > MAX_BYTES) return entries;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(target.toPath())))) {
            if (in.readInt() != MAGIC) return entries;
            int count = in.readInt(); if (count < 0 || count > LIMIT) return entries;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Entry e = new Entry(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
                if (valid(e.id, e.path) && !e.name.isEmpty() && !e.scope.isEmpty()
                        && e.file(null).isAudio() && seen.add(e.id + ":" + e.path)) entries.add(e);
            }
            if (in.read() != -1) entries.clear();
        } catch (IOException | RuntimeException ignored) { entries.clear(); }
        return entries;
    }
    private boolean write(List<Entry> entries) {
        try {
            AtomicSnapshotWriter.write(target, output -> {
                DataOutputStream out = new DataOutputStream(output);
                out.writeInt(MAGIC); out.writeInt(entries.size());
                for (Entry e : entries) {
                    out.writeUTF(e.id); out.writeUTF(e.path); out.writeUTF(e.name);
                    out.writeUTF(e.mime); out.writeUTF(e.scope);
                }
                out.flush();
            });
            return true;
        } catch (IOException | RuntimeException ignored) { return false; }
    }
    private static boolean valid(String id, String path) {
        if (id == null || id.isEmpty() || id.length() > 256 || path == null || path.isEmpty()
                || path.length() > 2048 || path.startsWith("/") || path.contains("\\")) return false;
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) return false;
        return true;
    }
    boolean record(WebDAVFile f, ServerProfile server) {
        synchronized (SearchSongHistoryStore.class) {
            if (f == null || server == null || !server.isComplete() || !Objects.equals(f.getServerId(), server.getId())
                    || !valid(f.getServerId(), f.getRelativePath()) || f.isCollection() || !f.isAudio()
                    || f.getDisplayName() == null || f.getDisplayName().isEmpty() || f.getDisplayName().length() > 2048) return false;
            List<Entry> entries = read();
            entries.removeIf(e -> e.id.equals(f.getServerId()) && e.path.equals(f.getRelativePath()));
            entries.add(0, new Entry(f.getServerId(), f.getRelativePath(), f.getDisplayName(),
                    f.getContentType() != null && f.getContentType().startsWith("audio/") ? "audio/*" : "", server.getScopeId()));
            while (entries.size() > LIMIT) entries.remove(entries.size() - 1);
            return write(entries);
        }
    }
    List<WebDAVFile> list(List<ServerProfile> servers) {
        synchronized (SearchSongHistoryStore.class) {
            List<WebDAVFile> files = new ArrayList<>();
            for (Entry e : read()) for (ServerProfile server : servers)
                if (e.id.equals(server.getId()) && server.isComplete() && e.scope.equals(server.getScopeId())) {
                    files.add(e.file(server)); break;
                }
            return files;
        }
    }
    WebDAVFile resolve(WebDAVFile file, List<ServerProfile> servers) {
        for (WebDAVFile current : list(servers))
            if (current.getCacheKey().equals(file.getCacheKey())) return current;
        return null;
    }
    boolean remove(WebDAVFile file) {
        synchronized (SearchSongHistoryStore.class) {
            List<Entry> entries = read();
            entries.removeIf(e -> e.id.equals(file.getServerId()) && e.path.equals(file.getRelativePath()));
            return write(entries);
        }
    }
    boolean clear() { synchronized (SearchSongHistoryStore.class) { return write(Collections.emptyList()); } }
}
