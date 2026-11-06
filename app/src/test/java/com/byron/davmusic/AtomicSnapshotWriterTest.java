package com.byron.davmusic;

import org.junit.Test;

/** Pure JVM regression cases; the same checks also run without JUnit via their main method. */
public class AtomicSnapshotWriterTest {
    @Test public void newSnapshotPreservesUtf8() throws Exception {
        AtomicSnapshotWriterChecks.check("new");
    }
    @Test public void replacementRemovesOldTrailingBytes() throws Exception {
        AtomicSnapshotWriterChecks.check("replace");
    }
    @Test public void failedOverwritePreservesOldFileAndCleansTemporary() throws Exception {
        AtomicSnapshotWriterChecks.check("failedOverwrite");
    }
    @Test public void failedFirstSavePublishesNothingAndCleansTemporary() throws Exception {
        AtomicSnapshotWriterChecks.check("failedFirst");
    }
    @Test public void oldFileRemainsReadableUntilPublish() throws Exception {
        AtomicSnapshotWriterChecks.check("beforePublish");
    }
    @Test public void failedMovePreservesDestinationAndCleansTemporary() throws Exception {
        AtomicSnapshotWriterChecks.check("moveFailure");
    }
    @Test public void concurrentWritersAndReadersOnlySeeCompleteSnapshots() throws Exception {
        AtomicSnapshotWriterChecks.check("concurrent");
    }
}
