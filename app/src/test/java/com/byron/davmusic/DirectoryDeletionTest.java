package com.byron.davmusic;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class DirectoryDeletionTest {
    @Test public void scopeIsDirectParentAndExactOwnerWithAudioOnly() {
        List<String> keys=Arrays.asList("a:album/a.mp3","b:album/a.mp3","a:album/sub/a.mp3","a:album2/a.mp3","a:album/cover.jpg","album/legacy.mp3","a:root.OPUS");
        assertEquals(Arrays.asList("a:album/a.mp3"),DirectoryDeletion.select(keys,"a","/album/"));
        assertEquals(Arrays.asList("a:root.OPUS"),DirectoryDeletion.select(keys,"a","/"));
        assertTrue(DirectoryDeletion.select(keys,null,"/").isEmpty());
    }
    @Test public void skipDoesNotInvokeRemoverAndFailuresContinue() {
        List<String> called=new ArrayList<>();
        DirectoryDeletion.Result r=DirectoryDeletion.run(Arrays.asList("playing","ok","fail","missing"),Collections.singleton("playing"),k->{
            called.add(k); if(k.equals("fail")) throw new SecurityException();
            return k.equals("missing")?DirectoryDeletion.Outcome.MISSING:DirectoryDeletion.Outcome.DELETED;
        });
        assertEquals(Arrays.asList("ok","fail","missing"),called);
        assertEquals(1,r.deleted);assertEquals(1,r.skipped);assertEquals(1,r.failed);assertEquals(1,r.missing);
    }
}
