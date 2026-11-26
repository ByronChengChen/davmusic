package com.byron.davmusic;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.lang.reflect.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class DirectoryDeletionUiTest {
    private ServerProfile fixtureOwner;
    @Before public void configuredServerBeforeActivityCreate() {
        fixtureOwner=ServerStore.add(RuntimeEnvironment.getApplication(),
                new ServerProfile("delete-fixture","test","http://127.0.0.1:9/","u","p"));
    }
    @After public void removeFixtureServer() {
        ServerStore.remove(RuntimeEnvironment.getApplication(),fixtureOwner.getId());
    }
    private void field(Object a,String name,Object value) throws Exception {
        Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);f.set(a,value);
    }
    @Test public void cancelDoesNothingAndConfirmUsesLocalSnapshotWithExplicitGap() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create();
        MainActivity a=controller.get();
        ServerProfile owner=new ServerProfile("delete-ui","test","http://127.0.0.1:9/","u","p");
        field(a,"currentServer",owner); field(a,"currentPath","/album");
        LocalCacheManager cache=LocalCacheManager.getInstance(a);
        java.io.File f=cache.getDownloadDestination("delete-ui:album/a.mp3");
        java.nio.file.Files.write(f.toPath(),new byte[]{1}); cache.markFileDownloaded("delete-ui:album/a.mp3",f);
        Method m=MainActivity.class.getDeclaredMethod("deleteCurrentDirectoryDownloads");m.setAccessible(true);
        m.invoke(a);
        AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
        Button cancel=dialog.findViewById(android.R.id.button2);
        ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)cancel.getLayoutParams();
        assertTrue(lp.getMarginEnd() >= Math.round(8*a.getResources().getDisplayMetrics().density));
        cancel.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertTrue(f.exists());
        m.invoke(a); dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
        dialog.<Button>findViewById(android.R.id.button1).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(f.exists()); assertFalse(cache.isDownloaded("delete-ui:album/a.mp3"));
        controller.destroy();
    }
    @Test public void confirmationRechecksTransferConflict() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create();
        MainActivity a=controller.get(); field(a,"currentServer",new ServerProfile("conflict","test","http://127.0.0.1:9/","u","p"));
        LocalCacheManager cache=LocalCacheManager.getInstance(a);
        java.io.File f=cache.getDownloadDestination("conflict:a.mp3");java.nio.file.Files.write(f.toPath(),new byte[]{1});cache.markFileDownloaded("conflict:a.mp3",f);
        Method m=MainActivity.class.getDeclaredMethod("deleteCurrentDirectoryDownloads");m.setAccessible(true);m.invoke(a);
        AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
        Field active=CachedTrackDownloader.class.getDeclaredField("activeKeys");active.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.Set<String> keys=(java.util.Set<String>)active.get(null);
        try { keys.add("another:busy.mp3"); dialog.<Button>findViewById(android.R.id.button1).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(f.exists()); }
        finally { keys.remove("another:busy.mp3");cache.deleteDownloadedCopy("conflict:a.mp3");controller.destroy(); }
    }
}
