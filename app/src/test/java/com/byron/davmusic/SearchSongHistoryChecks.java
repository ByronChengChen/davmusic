package com.byron.davmusic;

import java.io.File;
import java.lang.reflect.*;
import java.util.*;

/** Shared executable checks: real files and production store, no Android runtime required. */
public final class SearchSongHistoryChecks {
    static Object store(File file) throws Exception {
        try { return Class.forName("com.byron.davmusic.SearchSongHistoryStore").getDeclaredConstructor(File.class).newInstance(file); }
        catch (ClassNotFoundException e) { throw new AssertionError("search song history must persist clicked songs", e); }
    }
    static Object call(Object store, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = store.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(store, args);
    }
    static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    static WebDAVFile song(String id, String path) {
        WebDAVFile f = new WebDAVFile(); f.setServerId(id); f.setRelativePath(path); f.setDisplayName(path.substring(path.lastIndexOf('/') + 1)); return f;
    }
    static void restart(File dir) throws Exception {
        File target = new File(dir, "history"); Object store = store(target);
        ServerProfile server = new ServerProfile("a", "A", "https://example.invalid/dav/", "user", "secret");
        WebDAVFile song = song("a", "中文/a+b%20.mp3"); song.setHref("https://cdn.invalid/?token=secret");
        check((Boolean)call(store, "record", new Class[]{WebDAVFile.class, ServerProfile.class}, song, server), "record commits");
        List<?> loaded = (List<?>)call(store(target), "list", new Class[]{List.class}, Collections.singletonList(server));
        check(loaded.size() == 1, "restart retains song");
        WebDAVFile actual = (WebDAVFile)loaded.get(0);
        check(song.getCacheKey().equals(actual.getCacheKey()), "identity retained");
        check(actual.getHref() == null, "never restore old href");
        byte[] bytes = java.nio.file.Files.readAllBytes(target.toPath());
        String raw = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        check(!raw.contains("secret") && !raw.contains("https://") && !raw.contains("token"), "no credentials or URLs persisted");
    }
    public static void main(String[] args) throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("history-checks").toFile();
        restart(dir); System.out.println("PASS restart/identity/no-secret checks");
    }
}
