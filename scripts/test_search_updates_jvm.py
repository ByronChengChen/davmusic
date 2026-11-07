#!/usr/bin/env python3
"""Execute actual search callbacks/row-change methods with host stubs, not Android UI."""
from pathlib import Path
import os, re, subprocess, tempfile
root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/com/byron/davmusic/SearchActivity.java').read_text()
def method(name):
    m = re.search(r'    (?:public|private) void '+name+r'\(', source)
    assert m is not None, name
    start = source.index('{', m.start()); end = start + 1; depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}'); end += 1
    return source[m.start():end]
program = '''
import java.util.*;
class WebDAVFile {String key; WebDAVFile(String k){key=k;} String getCacheKey(){return key;}}
public class UpdateHarness {
 static class Cache {Set<String> keys=new HashSet<>();boolean isDownloaded(String k){return keys.contains(k);}}
 static class Adapter {
 int notifications; List<Integer> rows=new ArrayList<>();
 List<WebDAVFile> files=Arrays.asList(new WebDAVFile("a:same"),new WebDAVFile("b:same"),new WebDAVFile("c:other"));
 String playingKey; Map<String,Boolean> downloaded=new HashMap<>();Object statePayload=new Object(); Cache cache=new Cache();
 void notifyDataSetChanged(){notifications++;} void notifyItemChanged(int i,Object p){if(p==null)throw new AssertionError("missing payload");rows.add(i);}
 /*ADAPTER*/
 }
 Adapter adapter=new Adapter();
 /*METHODS*/
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){UpdateHarness h=new UpdateHarness();
 for(int i=0;i<120;i++)h.onProgress(i,180);
 check(h.adapter.notifications==0 && h.adapter.rows.isEmpty(),"progress invalidated results");
 h.onPlayStateChanged(true);h.onPlayStateChanged(false);
 check(h.adapter.notifications==0 && h.adapter.rows.isEmpty(),"pause/resume invalidated results");
 h.onTrackChanged(new WebDAVFile("a:same"));h.adapter.rows.clear();h.onTrackChanged(new WebDAVFile("b:same"));
 check(h.adapter.rows.equals(Arrays.asList(0,1)),"must update old/new cross-server rows only");
 h.adapter.rows.clear();h.onTrackChanged(new WebDAVFile("b:same"));check(h.adapter.rows.isEmpty(),"duplicate track notified");
 h.onTrackChanged(null);check(h.adapter.rows.equals(Arrays.asList(1)),"clear highlight wrong");
 h.adapter.rows.clear();h.adapter.refreshDownloadState();check(h.adapter.rows.isEmpty(),"unchanged downloads notified");
 h.adapter.cache.keys.add("b:same");h.adapter.refreshDownloadState();check(h.adapter.rows.equals(Arrays.asList(1)),"download completion wrong row");
 h.adapter.rows.clear();h.adapter.refreshDownloadState();check(h.adapter.rows.isEmpty(),"same download state notified");
 h.adapter.cache.keys.clear();h.adapter.refreshDownloadState();check(h.adapter.rows.equals(Arrays.asList(1)),"download removal wrong row");
 check(h.adapter.notifications==0,"blanket notification");
 System.out.println("Search updates JVM PASS: progress/pause silence, old/new cross-server highlights, null/duplicate track, changed-only download payloads");}
}
'''.replace('/*METHODS*/', '\n'.join(method(n) for n in ['onProgress','onPlayStateChanged','onTrackChanged'])).replace('/*ADAPTER*/','\n'.join(method(n) for n in ['refreshPlayingTrack','refreshDownloadState']))
with tempfile.TemporaryDirectory(prefix='search-updates-', dir=os.environ.get('TMPDIR',str(Path.home()/'.hermes/cache/scratch'))) as d:
    p=Path(d)/'UpdateHarness.java';p.write_text(program)
    subprocess.run(['javac','-d',d,str(p)],check=True)
    subprocess.run(['java','-cp',d,'UpdateHarness'],check=True)
