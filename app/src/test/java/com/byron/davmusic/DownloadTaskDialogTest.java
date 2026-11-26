package com.byron.davmusic;

import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class DownloadTaskDialogTest {
    @Test public void cancelButtonRetainsCurrentThenShowsSummary() {
        DirectoryDownloadTaskTest.Backend b=new DirectoryDownloadTaskTest.Backend();
        DirectoryDownloadTask t=new DirectoryDownloadTask(Arrays.asList(
                DirectoryDownloadTaskTest.song("a","1.mp3"),DirectoryDownloadTaskTest.song("a","2.mp3")),b);
        DownloadTaskDialog d=new DownloadTaskDialog(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_DavMusic),t);
        t.start(); d.show(); d.render();
        assertTrue(d.message.getText().toString().contains("1.mp3"));
        assertEquals("取消剩余",d.action.getText().toString());
        d.action.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(t.cancelled); assertTrue(d.dialog.isShowing());
        b.finish(true); d.render();
        assertTrue(d.message.getText().toString().contains("成功 1"));
        assertTrue(d.message.getText().toString().contains("未下载 1"));
        assertEquals("关闭",d.action.getText().toString());
        // One independent action only, with explicit 8dp top margin from the progress text.
        android.view.ViewGroup.MarginLayoutParams p=(android.view.ViewGroup.MarginLayoutParams)d.action.getLayoutParams();
        assertTrue(p.topMargin>=8*RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density);
        assertEquals(View.VISIBLE,d.action.getVisibility()); d.action.performClick(); assertFalse(d.dialog.isShowing());
    }
}
