"""No Android/Gradle: execute the real pure-Java scope/result policy."""
import pathlib, subprocess, tempfile, unittest, os
ROOT = pathlib.Path(__file__).resolve().parents[1]
class DirectoryDeleteHostTest(unittest.TestCase):
    def test_real_java_policy(self):
        source = ROOT/'app/src/main/java/com/byron/davmusic/DirectoryDeletion.java'
        self.assertTrue(source.exists(), 'directory deletion policy is not implemented')
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as d:
            harness = pathlib.Path(d)/'DeletionHarness.java'
            harness.write_text('''package com.byron.davmusic;
import java.util.*;
public class DeletionHarness {
 public static void main(String[] args) {
  List<String> keys=Arrays.asList("a:album/a.mp3","a:album/sub/b.mp3","b:album/a.mp3","a:album2/x.mp3","a:root.mp3","a:album/cover.jpg","album/old.mp3");
  if (!DirectoryDeletion.select(keys,"a","/album/").equals(Arrays.asList("a:album/a.mp3"))) throw new AssertionError("scope");
  if (!DirectoryDeletion.select(keys,"a","/").equals(Arrays.asList("a:root.mp3"))) throw new AssertionError("root");
  if (!DirectoryDeletion.select(keys,"","/album").isEmpty()) throw new AssertionError("owner");
  DirectoryDeletion.Result r=DirectoryDeletion.run(Arrays.asList("one","two","three","four"), new HashSet<>(Arrays.asList("two")), k -> k.equals("three") ? DirectoryDeletion.Outcome.FAILED : k.equals("four") ? DirectoryDeletion.Outcome.MISSING : DirectoryDeletion.Outcome.DELETED);
  if(r.deleted!=1 || r.skipped!=1 || r.failed!=1 || r.missing!=1) throw new AssertionError("result");
  System.out.println("scope/root/server/audio/result: PASS");
 }
}''')
            # Compile real FileUtils; only android.util.Log is a host stub.
            stub=pathlib.Path(d)/'Log.java'
            stub.write_text('package android.util; public class Log { public static int e(String a,String b,Throwable c) { return 0; } }')
            subprocess.run(['javac','-d',d,str(source),str(ROOT/'app/src/main/java/com/byron/davmusic/FileUtils.java'),str(stub),str(harness)],check=True)
            subprocess.run(['java','-cp',d,'com.byron.davmusic.DeletionHarness'],check=True)
if __name__ == '__main__': unittest.main()
