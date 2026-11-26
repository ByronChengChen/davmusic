package com.byron.davmusic;

import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class MainDirectoryDownloadTest {
    @Test public void duplicateMenuClickReopensTaskWithoutNewRequestAndDestroyStopsRemaining() throws Exception {
        ServerProfile owner=ServerStore.add(RuntimeEnvironment.getApplication(),
                new ServerProfile("lifecycle-test","test","http://127.0.0.1:9/","u","p"));
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create();
        MainActivity a=controller.get();
        DirectoryDownloadTaskTest.Backend b=new DirectoryDownloadTaskTest.Backend();
        DirectoryDownloadTask t=new DirectoryDownloadTask(Arrays.asList(
                DirectoryDownloadTaskTest.song("a","1.mp3"),DirectoryDownloadTaskTest.song("a","2.mp3")),b);
        Field f=MainActivity.class.getDeclaredField("downloadTask");f.setAccessible(true);f.set(a,t);
        t.start();
        // Exercise the actual menu action with a running task, before any network/navigation state lookup.
        Method m=MainActivity.class.getDeclaredMethod("downloadCurrentDirectory");m.setAccessible(true);
        m.invoke(a); m.invoke(a); assertEquals(1,b.calls.size());
        controller.destroy(); assertNull(t.listener); assertTrue(t.cancelled);
        ServerStore.remove(RuntimeEnvironment.getApplication(),owner.getId());
        b.finish(true); assertTrue(t.finished);assertEquals(1,b.calls.size());assertEquals(1,t.remaining());
    }
}
