package com.byron.davmusic;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SnapshotSearchStorageTest {
    @Test public void snapshotsReplaceInvalidateSkipCorruptionAndFenceClear() throws Exception {
        SnapshotSearchStorageChecks.verify(LocalCacheManager.getInstance(RuntimeEnvironment.getApplication()));
    }
}
