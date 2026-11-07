#!/usr/bin/env python3
"""SDK-free tests against real production Java; Android platform calls are explicit host stubs.
Requires JDK 17, downloads JUnit/Hamcrest/org.json to TMPDIR, never runs Gradle.
Not Robolectric, MediaPlayer, Android UI, or an APK build.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import urllib.request

root = Path(__file__).resolve().parents[1]
scratch = Path(os.environ.get('TMPDIR', Path.home()/'.hermes/cache/scratch'))
scratch.mkdir(parents=True, exist_ok=True)
h = scratch/'davmusic-search-jvm'
h.mkdir(exist_ok=True)
for name, url in {
    'junit.jar': 'https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar',
    'hamcrest.jar': 'https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
    'json.jar': 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',
}.items():
    if not (h/name).exists():
        urllib.request.urlretrieve(url, h/name)
stubs = {
    'android/content/Context.java': '''package android.content; import java.io.File;
public class Context {public static final int MODE_PRIVATE=0;
public Context getApplicationContext(){return this;}
public SharedPreferences getSharedPreferences(String n,int m){return new SharedPreferences();}
public File getFilesDir(){File f=new File(System.getProperty("test.root"),"files");f.mkdirs();return f;}
public File getCacheDir(){File f=new File(System.getProperty("test.root"),"cache");f.mkdirs();return f;}
public File getExternalFilesDir(String s){return getFilesDir();}}''',
    'android/content/SharedPreferences.java': '''package android.content;
public class SharedPreferences {public int getInt(String k,int d){return d;}
public String getString(String k,String d){return d;} public Editor edit(){return new Editor();}
public static class Editor {public Editor putInt(String k,int v){return this;}
public Editor putString(String k,String v){return this;} public Editor remove(String k){return this;}
public void apply(){}}}''',
    'android/util/Log.java': '''package android.util; public class Log {
public static int e(String t,String m,Throwable e){return 0;}
public static int d(String t,String m){return 0;} public static int w(String t,String m){return 0;}}''',
    'android/os/Environment.java': 'package android.os; public class Environment {}',
    'com/byron/davmusic/StorageRunner.java': '''package com.byron.davmusic;
public class StorageRunner {public static void main(String[] args)throws Exception {
SnapshotSearchStorageChecks.verify(LocalCacheManager.getInstance(new android.content.Context()));
System.out.println("Storage checks PASS (replacement, empty refresh, corruption, revision, clear epoch, concurrent clear)");}}''',
}
for path, text in stubs.items():
    p = h/path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)
main = root/'app/src/main/java/com/byron/davmusic'
test = root/'app/src/test/java/com/byron/davmusic'
production = ['WebDAVFile', 'FileUtils', 'ServerProfile', 'TrackSorter', 'SnapshotSearchEngine',
              'SnapshotSearchController', 'TrackIdentity', 'AtomicSnapshotWriter', 'LocalCacheManager',
              'HttpDateParser', 'LrcParser', 'LyricsQuery']
tests = ['SnapshotSearchEngineTest', 'SnapshotSearchControllerTest', 'TrackSorterTest',
         'FileUtilsTest', 'HttpDateParserTest', 'LrcParserTest', 'LyricsQueryTest', 'AtomicSnapshotWriterTest']
sources = [str(h/p) for p in stubs] + [str(main/(n+'.java')) for n in production]
sources += [str(test/(n+'.java')) for n in tests + ['SnapshotSearchStorageChecks', 'AtomicSnapshotWriterChecks']]
cp = ':'.join(str(h/n) for n in ['junit.jar', 'hamcrest.jar', 'json.jar'])
subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', cp, '-d', str(h/'out')] + sources, check=True)
with tempfile.TemporaryDirectory(dir=h) as d:
    command = ['java', '-Djava.io.tmpdir='+d, '-Dtest.root='+d, '-cp', str(h/'out')+':'+cp]
    subprocess.run(command + ['org.junit.runner.JUnitCore'] + ['com.byron.davmusic.'+n for n in tests], check=True)
    subprocess.run(command + ['com.byron.davmusic.StorageRunner'], check=True)
