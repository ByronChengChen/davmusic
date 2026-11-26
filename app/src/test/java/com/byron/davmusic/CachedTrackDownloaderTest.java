package com.byron.davmusic;

import android.content.Context;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class CachedTrackDownloaderTest {
    Context context;
    @Before public void setup() { context=RuntimeEnvironment.getApplication(); }
    void transfer(int status, String body, long expected, boolean success) throws Exception {
        try (ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10000);
            String id="transfer-"+System.nanoTime();
            ServerStore.add(context,new ServerProfile(id,"test","http://127.0.0.1:"+server.getLocalPort()+"/","u","p"));
            WebDAVFile f=DirectoryDownloadTaskTest.song(id,"a+b.mp3"); f.setContentLength(expected);
            File dest=LocalCacheManager.getInstance(context).getDownloadDestination(f.getCacheKey());
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<String> request=worker.submit(()-> {
                try (Socket socket=server.accept()) {
                    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    String first=reader.readLine(), line;
                    while ((line=reader.readLine())!=null && !line.isEmpty()) {}
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 "+status+" Test\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n"+body).getBytes(StandardCharsets.UTF_8));
                    return first;
                }
            });
            try {
                boolean[] done={false}, ok={false};
                new CachedTrackDownloader(context).download(f,(result,error)-> {done[0]=true; ok[0]=result;});
                long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while (!done[0] && System.nanoTime()<limit) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10); }
                assertTrue("callback delivered",done[0]); assertEquals(success,ok[0]);
                assertEquals(success,LocalCacheManager.getInstance(context).isDownloaded(f.getCacheKey()));
                if (success) assertEquals(body,Files.readString(dest.toPath())); else assertFalse(dest.exists());
                assertTrue(request.get(2,TimeUnit.SECONDS).contains("a%2Bb.mp3"));
                assertEquals(0,dest.getParentFile().listFiles((dir,name)->name.endsWith(".part")).length);
            } finally { worker.shutdownNow(); LocalCacheManager.getInstance(context).removeDownloadedFile(f.getCacheKey()); ServerStore.remove(context,id); }
        }
    }
    @Test public void successPublishesCompleteFileAndEncodedPath() throws Exception { transfer(200,"audio",5,true); }
    @Test public void http429FailsOnceAndCleansPartial() throws Exception { transfer(429,"limited",0,false); }
    @Test public void sizeMismatchIsNotCached() throws Exception { transfer(200,"short",99,false); }
    @Test public void missingOwnerDoesNotUseAnotherServer() {
        WebDAVFile f=DirectoryDownloadTaskTest.song("missing-owner","1.mp3"); boolean[] called={false};
        new CachedTrackDownloader(context).download(f,(ok,error)-> { assertFalse(ok); assertTrue(error.contains("服务器")); called[0]=true; });
        assertTrue(called[0]);
    }
}
