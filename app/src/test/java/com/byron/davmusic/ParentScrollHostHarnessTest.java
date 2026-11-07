package com.byron.davmusic;

import org.junit.Test;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * CI guard for the navigation scroll contract executed by scripts/*_jvm.py.
 *
 * The scripts compile the actual MainActivity save/restore/navigation methods against
 * explicit host stubs, because an Activity is not unit-testable in this project and the
 * SDK build must stay in CI. This test only checks that those real host runs pass.
 * It is not Android lifecycle, Parcelable encoding or RecyclerView layout proof.
 */
public class ParentScrollHostHarnessTest {
    @Test
    public void parentDirectoriesRestoreExactPositionInHostHarness() throws Exception {
        File script = locate("scripts/test_parent_scroll_jvm.py");
        assertTrue("missing " + script, script.isFile());
        run(script);
    }

    @Test
    public void existingSearchReturnContractStillPassesAfterScrollStateChanges() throws Exception {
        File script = locate("scripts/test_search_return_jvm.py");
        assertTrue("missing " + script, script.isFile());
        run(script);
    }

    private static File locate(String relative) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) return candidate;
            dir = dir.getParentFile();
        }
        return new File(relative);
    }

    private static void run(File script) throws Exception {
        List<String> command = Arrays.asList("python3", script.getAbsolutePath());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
    }
}
