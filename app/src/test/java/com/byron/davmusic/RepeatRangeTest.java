package com.byron.davmusic;

import org.junit.Test;
import static org.junit.Assert.*;

public class RepeatRangeTest {
    @Test public void rejectsInvalidRangesAndHonorsEndBoundary() {
        assertNull(RepeatRange.create(500, 500, 1000));
        assertNull(RepeatRange.create(-1, 500, 1000));
        assertNull(RepeatRange.create(0, 1001, 1000));
        assertNull(RepeatRange.create(600, 500, 1000));
        RepeatRange range = RepeatRange.create(10, 1000, 1000);
        assertNotNull(range);
        assertEquals(10, range.getStartMs());
        assertEquals(1000, range.getEndMs());
        assertTrue(range.shouldLoopAt(1000));
        assertFalse(range.shouldLoopAt(999));
        assertEquals(10, range.loopPosition());
    }
    @Test public void identityIsScopedByServerAndRelativePathNotUrl() {
        assertEquals(RepeatRange.trackKey("s1", "/a/b.mp3"), RepeatRange.trackKey("s1", "/a/b.mp3"));
        assertNotEquals(RepeatRange.trackKey("s1", "/a/b.mp3"), RepeatRange.trackKey("s2", "/a/b.mp3"));
        assertNotEquals(RepeatRange.trackKey("s1", "/a/b.mp3"), RepeatRange.trackKey("s1", "/a/c.mp3"));
        assertNull(RepeatRange.trackKey("s1", "https://user:pass/a.mp3"));
        assertNotNull(RepeatRange.trackKey("s1", "/rihan/The Tide Is High (Get the Feeling).mp3"));
    }
    @Test public void controllerDeactivatesOnTrackChangeAndStop() {
        RepeatController c = new RepeatController();
        RepeatRange first = RepeatRange.create(100, 300, 1000);
        c.select("trackA", first);
        assertTrue(c.isActiveFor("trackA"));
        assertTrue(c.isActiveRange("trackA", first));
        assertTrue(c.isActiveRange("trackA", RepeatRange.create(100, 300, 1000)));
        assertFalse(c.isActiveRange("trackA", RepeatRange.create(100, 301, 1000)));
        c.onTrackChanged("trackB");
        assertFalse(c.isActive());
        c.select("trackB", RepeatRange.create(100, 300, 1000));
        c.onExplicitStop();
        assertFalse(c.isActive());
    }
}
