#!/usr/bin/env python3
"""Execute actual MainActivity save/restore methods on JVM with explicit host UI stubs.
Not an Android lifecycle/UI test: extracts method bodies verbatim, never simulates fixes.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/com/byron/davmusic/MainActivity.java').read_text()

def method(name):
    match = re.search(r'    (?:protected|private) (?:void|boolean) '+name+r'\(', source)
    assert match is not None, name
    start = source.index('{', match.start())
    depth = 1
    end = start + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end]

methods = '\n'.join(method(n) for n in ['onSaveInstanceState', 'restoreNavState', 'restoreScrollPos'])
constants = '\n'.join(re.findall(r'    private static final String STATE_.*?;', source))
program = r'''
import java.util.*;
@interface NonNull {}
class Bundle extends HashMap<String,Object> {
 void putString(String k,String v){put(k,v);} String getString(String k,String d){return (String)getOrDefault(k,d);}
 void putStringArrayList(String k,ArrayList<String> v){put(k,v);} @SuppressWarnings("unchecked") ArrayList<String> getStringArrayList(String k){return (ArrayList<String>)get(k);}
 void putBoolean(String k,boolean v){put(k,v);} boolean getBoolean(String k,boolean d){return (boolean)getOrDefault(k,d);}
 void putInt(String k,int v){put(k,v);} int getInt(String k,int d){return (int)getOrDefault(k,d);}
 void putParcelable(String k,android.os.Parcelable v){put(k,v);} android.os.Parcelable getParcelable(String k){return (android.os.Parcelable)get(k);}
}
class Base {protected void onSaveInstanceState(Bundle b){}}
public class ReturnHarness extends Base {
 static final String TAG="test";
 static class Logger {void i(String t,String s){}}
 static class ServerProfile {String id;ServerProfile(String id){this.id=id;}String getId(){return id;}}
 static class State implements android.os.Parcelable {int position,offset;State(int p,int o){position=p;offset=o;}}
 static class LinearLayoutManager {
  State state=new State(0,0); int restored;
  int findFirstVisibleItemPosition(){return state.position;}
  android.os.Parcelable onSaveInstanceState(){return state;}
  void onRestoreInstanceState(android.os.Parcelable s){state=(State)s;restored++;}
 }
 static class Recycler {LinearLayoutManager lm=new LinearLayoutManager();List<Runnable> posts=new ArrayList<>();
  LinearLayoutManager getLayoutManager(){return lm;}void post(Runnable r){posts.add(r);}
  void scrollToPosition(int p){lm.state=new State(p,0);}void drain(){List<Runnable> q=new ArrayList<>(posts);posts.clear();q.forEach(Runnable::run);}}
 static class Adapter {int count;int getItemCount(){return count;}}
 Logger rlog;String currentPath="/";ArrayDeque<String> pathStack=new ArrayDeque<>();
 boolean isOfflineMode,returningFromSearch; ServerProfile currentServer;
 android.os.Parcelable searchReturnState;Bundle pendingScrollState;
 Recycler recyclerView=new Recycler();Adapter fileListAdapter=new Adapter();
 Set<String> servers=new HashSet<>(Arrays.asList("a","b"));
 ServerProfile findServerById(String id){return servers.contains(id)?new ServerProfile(id):null;}
 /* METHODS */
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static ReturnHarness original(){ReturnHarness a=new ReturnHarness();a.currentServer=new ServerProfile("b");a.currentPath="/music/live/";
  a.pathStack.add("/");a.pathStack.add("/music/");a.returningFromSearch=true;
  a.searchReturnState=new State(47,-29);a.recyclerView.lm.state=(State)a.searchReturnState;return a;}
 static Bundle save(ReturnHarness a){Bundle b=new Bundle();a.onSaveInstanceState(b);return b;}
 public static void main(String[] args){
  ReturnHarness old=original();Bundle saved=save(old);ReturnHarness a=new ReturnHarness();a.restoreNavState(saved);
  check(a.currentServer!=null && "b".equals(a.currentServer.id),"recreation lost server b (must not choose first server/root)");
  check(a.currentPath.equals("/music/live/") && new ArrayList<>(a.pathStack).equals(Arrays.asList("/","/music/")),"lost path/back stack");
  check(a.returningFromSearch,"recreation lost search-return flag");
  ReturnHarness twice=new ReturnHarness();Bundle twiceSaved=save(a);twice.restoreNavState(twiceSaved);
  twice.fileListAdapter.count=100;twice.restoreScrollPos(twiceSaved);twice.recyclerView.drain();
  check(twice.recyclerView.lm.state.position==47 && twice.recyclerView.lm.state.offset==-29,"second recreation before data lost pending layout");
  a.restoreScrollPos(saved);a.recyclerView.drain();
  check(a.recyclerView.lm.restored==0,"restored before data loaded");
  a.fileListAdapter.count=100;a.restoreScrollPos(saved);a.recyclerView.drain();
  check(a.recyclerView.lm.state.position==47 && a.recyclerView.lm.state.offset==-29,"lost full layout position/offset after delayed data");
  a.restoreScrollPos(saved);a.recyclerView.drain();check(a.recyclerView.lm.restored==1,"scroll must be consumed once");
  ReturnHarness deleted=new ReturnHarness();deleted.servers.remove("b");deleted.restoreNavState(saved);
  check(deleted.currentServer==null && deleted.currentPath.equals("/") && deleted.pathStack.isEmpty(),"deleted server must safely fall back to root");
  check(!deleted.returningFromSearch && deleted.searchReturnState==null,"deleted server retained stale return state");
  ReturnHarness top=original();top.currentPath="/";top.searchReturnState=new State(0,-11);top.recyclerView.lm.state=(State)top.searchReturnState;Bundle topSaved=save(top);
  ReturnHarness topNew=new ReturnHarness();topNew.restoreNavState(topSaved);topNew.fileListAdapter.count=5;topNew.restoreScrollPos(topSaved);topNew.recyclerView.drain();
  check(topNew.currentServer!=null && topNew.recyclerView.lm.state.offset==-11,"server root / first-row pixel offset lost");
  ReturnHarness navigated=new ReturnHarness();navigated.restoreNavState(saved);navigated.currentPath="/other/";
  navigated.fileListAdapter.count=100;navigated.restoreScrollPos(saved);navigated.recyclerView.drain();check(navigated.recyclerView.lm.restored==0,"wrong directory consumed saved scroll");
  ReturnHarness raced=new ReturnHarness();raced.restoreNavState(saved);raced.fileListAdapter.count=100;raced.restoreScrollPos(saved);
  raced.currentServer=new ServerProfile("a");raced.recyclerView.drain();check(raced.recyclerView.lm.restored==0,"posted restore crossed server boundary");
  System.out.println("Search return JVM PASS (server/path/stack, search flag, delayed data, exact offset, once-only, deleted server, server root, navigation races)");
 }
}
'''.replace('/* METHODS */', constants+'\n'+methods)
scratch = Path(os.environ.get('TMPDIR', Path.home()/'.hermes/cache/scratch'))
scratch.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='search-return-', dir=scratch) as tmp:
    p=Path(tmp)
    (p/'android/os').mkdir(parents=True)
    (p/'android/os/Parcelable.java').write_text('package android.os; public interface Parcelable {}')
    (p/'ReturnHarness.java').write_text(program)
    subprocess.run(['javac','-encoding','UTF-8','-d',tmp,str(p/'android/os/Parcelable.java'),str(p/'ReturnHarness.java')],check=True)
    subprocess.run(['java','-cp',tmp,'ReturnHarness'],check=True)
