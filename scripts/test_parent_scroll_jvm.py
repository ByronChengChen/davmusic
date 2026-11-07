#!/usr/bin/env python3
"""Host regression of actual MainActivity navigation/save/restore methods; no Android SDK.
RecyclerView, Bundle and network/UI dependencies are explicit stubs, not lifecycle proof.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/com/byron/davmusic/MainActivity.java').read_text()

def method(name):
    match = re.search(r'    (?:protected|private) (?:void|boolean|Bundle) ' + name + r'\(', source)
    assert match, name
    start = source.index('{', match.start())
    end, depth = start + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end]

# Share only existing explicit host stub definitions, never their test implementation.
fixture = (root / 'scripts/test_search_return_jvm.py').read_text()
program = fixture.split("program = r'''", 1)[1].split("'''", 1)[0]
program = program[:program.index(' /* METHODS */')]
program = program.replace('ReturnHarness', 'ParentHarness').replace('ArrayDeque<String> pathStack=new ArrayDeque<>()', 'ArrayList<String> pathStack=new ArrayList<>()')
program = program.replace('String getId(){return id;}', 'String getId(){return id;} boolean isComplete(){return true;} String getDisplayName(){return id;} String getUrl(){return id;}')
program = program.replace('class Bundle extends HashMap<String,Object> {', '''class Bundle extends HashMap<String,Object> implements android.os.Parcelable {
 void putParcelableArrayList(String k,ArrayList<Bundle> v){put(k,v);}
 @SuppressWarnings("unchecked") ArrayList<Bundle> getParcelableArrayList(String k){return (ArrayList<Bundle>)get(k);}
''')
program = program.replace('android.os.Parcelable getParcelable(String k){return (android.os.Parcelable)get(k);}', '@SuppressWarnings("unchecked") <T extends android.os.Parcelable> T getParcelable(String k){return (T)get(k);}')
program = program.replace('static class Adapter {int count;int getItemCount(){return count;}}', '''class Adapter {int count;int getItemCount(){return count;}
 void setFiles(List<WebDAVFile> files){count=files.size();restoreScrollPos(pendingScrollState);}}
 static class WebDAVFile {String path;WebDAVFile(String p){path=p;}String getRelativePath(){return path;}String getDisplayName(){return path;}String getHref(){return path;}String getServerId(){return "a";}boolean isCollection(){return true;}}
 static class R {static class string {static int msg_server_incomplete;}}
 String getString(int id){return "";} void toast(String s){}
 int loadToken;
 static class Client {boolean isConfigured(){return true;}} Client webDAVClient=new Client();
 static class Intent {Intent(Object a,Class<?> b){}} static class ServerManageActivity {}
 static class Launcher {void launch(Intent i){}} Launcher serverManageLauncher=new Launcher();
 void finish(){} void refreshOfflineFlag(){} void updateNetworkStatus(){} void updatePathDisplay(){}
 String cacheKeyOf(ServerProfile s,String p){return s.getId()+p;}
 void showServerRoot(){fileListAdapter.count=200;restoreScrollPos(pendingScrollState);}
 void data(){fileListAdapter.count=200;restoreScrollPos(pendingScrollState);recyclerView.drain();}
 void position(int p,int o){recyclerView.lm.state=new State(p,o);}
 void at(int p,int o,String m){check(recyclerView.lm.state.position==p && recyclerView.lm.state.offset==o,m+": got "+recyclerView.lm.state.position+","+recyclerView.lm.state.offset);}
''')
names = ['enterServer', 'exitToServerRoot', 'navigateToFolder', 'navigateUp', 'onSaveInstanceState', 'restoreNavState', 'restoreScrollPos']
for name in ['captureScrollState', 'resetPendingScroll', 'restoreParentScroll']:
    if re.search(r' (?:void|Bundle) '+name+r'\(', source):
        names.append(name)
program += '\n' + '\n'.join(re.findall(r'    private static final String STATE_.*?;', source)) + '\n' + '\n'.join(method(n) for n in names)
program += '\n' + method('loadCurrentPath').split('        final boolean online =', 1)[0] + '\n    }\n'
program += r'''
 static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 static Bundle save(ParentHarness a){Bundle b=new Bundle();a.onSaveInstanceState(b);return b;}
 public static void main(String[] args){
  ParentHarness a=new ParentHarness();a.data();a.position(18,-23);
  a.enterServer(new ServerProfile("a"));a.data();a.at(0,0,"fresh server must start at top");
  a.position(47,-29);a.navigateToFolder(new WebDAVFile("music"));a.data();a.at(0,0,"fresh folder must start at top");
  a.position(21,-13);a.navigateToFolder(new WebDAVFile("music/live"));a.data();
  a.navigateUp();a.data();a.at(21,-13,"nested parent exact position/offset");
  a.navigateUp();a.data();a.at(47,-29,"server root exact position/offset");
  a.navigateUp();a.data();a.at(18,-23,"server list exact position/offset");
  ParentHarness before=new ParentHarness();before.data();before.position(15,-7);
  before.enterServer(new ServerProfile("b"));before.data();before.position(61,-31);
  before.navigateToFolder(new WebDAVFile("album"));before.data();before.position(9,-19);
  ParentHarness recreated=new ParentHarness();recreated.restoreNavState(save(before));recreated.data();
  recreated.at(9,-19,"recreated current folder");recreated.navigateUp();recreated.data();
  recreated.at(61,-31,"recreated ancestor offset");recreated.navigateUp();recreated.data();
  recreated.at(15,-7,"recreated server list offset");
  ParentHarness empty=new ParentHarness();empty.restoreNavState(save(before));
  empty.fileListAdapter.setFiles(new ArrayList<>());empty.recyclerView.drain();empty.at(0,0,"empty data must not consume restore");
  empty.data();empty.at(9,-19,"empty-to-data delayed restore");
  ParentHarness stale=new ParentHarness();stale.restoreNavState(save(before));stale.restoreScrollPos(stale.pendingScrollState);
  stale.navigateToFolder(new WebDAVFile("other"));stale.recyclerView.drain();stale.at(0,0,"stale posted restore crossed navigation");
  ParentHarness deletedOwner=new ParentHarness();deletedOwner.servers.remove("b");
  deletedOwner.restoreNavState(save(before));deletedOwner.data();deletedOwner.at(0,0,"deleted owner must start root at top");
  System.out.println("Parent navigation JVM PASS");
 }
}
'''
scratch = Path(os.environ.get('TMPDIR', Path.home()/'.hermes/cache/scratch'))
scratch.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='parent-scroll-', dir=scratch) as tmp:
    p = Path(tmp)
    (p/'android/os').mkdir(parents=True)
    (p/'android/os/Parcelable.java').write_text('package android.os; public interface Parcelable {}')
    (p/'ParentHarness.java').write_text(program)
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', tmp, str(p/'android/os/Parcelable.java'), str(p/'ParentHarness.java')], check=True)
    subprocess.run(['java', '-cp', tmp, 'ParentHarness'], check=True)
