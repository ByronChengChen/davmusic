package com.byron.davmusic;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SnapshotSearchEngineTest {
    static WebDAVFile song(String server, String path) {
        WebDAVFile f = new WebDAVFile();
        f.setServerId(server); f.setRelativePath(path);
        f.setDisplayName(path.substring(path.lastIndexOf('/') + 1));
        return f;
    }

    @Test public void samePathOrHrefOnDifferentServersNeverMeansSameTrack() {
        WebDAVFile a = song("a", "Song.mp3"), b = song("b", "Song.mp3");
        a.setHref("/dav/Song.mp3"); b.setHref("/dav/Song.mp3");
        assertFalse(TrackIdentity.same(a, b));
        assertTrue(TrackIdentity.same(a, song("a", "Song.mp3")));
        WebDAVFile other = song("a", "Other.mp3");
        other.setHref("/dav/Song.mp3");
        assertFalse("owned entries use their relative identity, not ambiguous href", TrackIdentity.same(a, other));
    }

    @Test public void aggregationFiltersAndDeduplicatesByOwnerAndPath() {
        WebDAVFile a = song("a", "one/Song.mp3");
        WebDAVFile b = song("b", "one/Song.mp3");
        WebDAVFile folder = song("a", "two/folder.mp3"); folder.setCollection(true);
        List<WebDAVFile> raw = Arrays.asList(a, a, b, folder, song("gone", "Song.mp3"),
                song("a", "notes.txt"), song("", "old.mp3"));
        Map<String,String> owners = new HashMap<>(); owners.put("a", "A"); owners.put("b", "B");
        List<WebDAVFile> loaded = SnapshotSearchEngine.collect(raw, owners);
        assertEquals(2, loaded.size());
        assertEquals("A", loaded.get(0).getServerName());
        assertEquals("B", loaded.get(1).getServerName());
        assertNotEquals(loaded.get(0).getCacheKey(), loaded.get(1).getCacheKey());
    }

    @Test public void sortingUsesGlobalComparatorAndResultIsIndependent() {
        List<WebDAVFile> files = new ArrayList<>(Arrays.asList(song("a", "Track 10.mp3"), song("b", "Track 2.mp3")));
        files.get(0).setLastModified(500);
        List<WebDAVFile> names = SnapshotSearchEngine.search(files, "track", TrackSorter.MODE_NAME);
        assertEquals("Track 2.mp3", names.get(0).getDisplayName());
        assertEquals("Track 10.mp3", SnapshotSearchEngine.search(files, "track", TrackSorter.MODE_TIME).get(0).getDisplayName());
        files.clear(); assertEquals(2, names.size());
    }

    @Test public void filenameTokensIgnoreCaseButNotDirectoryOrExtension() {
        List<WebDAVFile> files = Arrays.asList(song("a", "Music/Blue SKY.mp3"));
        assertEquals(1, SnapshotSearchEngine.search(files, "  sky BLUE  ", TrackSorter.MODE_NAME).size());
        assertTrue(SnapshotSearchEngine.search(files, "mp3", 1).isEmpty());
        assertTrue(SnapshotSearchEngine.search(files, "Music", 1).isEmpty());
        assertTrue(SnapshotSearchEngine.search(files, "blue missing", 1).isEmpty());
        assertTrue(SnapshotSearchEngine.search(files, " \t ", 1).isEmpty());
    }
}
