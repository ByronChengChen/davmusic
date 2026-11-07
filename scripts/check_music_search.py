#!/usr/bin/env python3
"""Static integration contracts; not an Android compile or device UI test."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
r = Path(__file__).resolve().parents[1]
a = '{http://schemas.android.com/apk/res/android}'
java = r / 'app/src/main/java/com/byron/davmusic'
res = r / 'app/src/main/res'
for p in res.rglob('*.xml'):
    ET.parse(p)
manifest = ET.parse(r / 'app/src/main/AndroidManifest.xml')
activity = next((n for n in manifest.iter('activity') if n.get(a+'name') == 'com.byron.davmusic.SearchActivity'), None)
assert activity is not None, 'SearchActivity manifest registration missing'
assert activity.get(a+'windowSoftInputMode') == 'adjustResize'
assert activity.get(a+'exported') == 'false'
layout = ET.parse(res / 'layout/activity_search.xml')
assert any(n.get('layout') == '@layout/view_player_bar' for n in layout.iter('include'))
source = (java / 'SearchActivity.java').read_text()
for token in ['PlayerBarController', 'controller.close()', 'removeSnapshotListener', 'unregisterOnSharedPreferenceChangeListener', 'removePlaybackListener', 'new ArrayList<>(adapter.files)', 'musicPlayer.setPlaylist', 'musicPlayer.playFile', 'MusicService.start']:
    assert token in source, token
for token in ['listFolder(', 'WebDAVClient.getInstance(', 'reorderPlaylist(']:
    assert token not in source, token
main = (java / 'MainActivity.java').read_text()
assert 'SearchActivity.class' in main
# Targeted source contract, not Java scope analysis or an Android compilation:
# onCreate's completion log still consumes the boolean navigation-restore result.
on_create = main[main.index('protected void onCreate('):main.index('private void initViews(')]
assert re.search(r'\bboolean\s+restored\s*=\s*restoreNavState\(savedInstanceState\)\s*;', on_create), \
    'onCreate log references restored: keep its local boolean restoreNavState result'
# Wiring checks supplement the executable save/restore JVM regression; not a behavior proof.
for token in ['STATE_SERVER_ID', 'STATE_SEARCH_RETURN', 'putParcelable(STATE_LAYOUT',
              'registerAdapterDataObserver', 'restoreScrollPos(pendingScrollState)',
              'onRestoreInstanceState(layout)']:
    assert token in main, ('recreation return integration', token)
assert 'postDelayed(() -> restoreScrollPos' not in main, 'must await adapter data, not a timer'
assert 'snapshotEpoch != cacheManager.getSnapshotEpoch()' in main, 'late clear response must not republish UI/cache'
assert 'saveSnapshotIfCurrent(cacheKey, files, snapshotEpoch)' in main
assert 'loadToken++;' in main[main.index('private void clearCache()'):main.index('private void showAboutDialog()')]
# All new resource references resolve, including aliases from the shared layout.
resources = {}
for p in (res / 'values').glob('*.xml'):
    for n in ET.parse(p).getroot():
        resources.setdefault(n.tag, set()).add(n.get('name'))
for p in [res / 'layout/activity_search.xml', res / 'layout/item_search_result.xml', res / 'menu/main_menu.xml']:
    for kind, name in re.findall(r'@(string|color|drawable|layout)/([\w_]+)', p.read_text()):
        assert name in resources.get(kind, set()) or (res/kind/(name+'.xml')).exists(), (p, kind, name)
print('PASS: XML parsed; search manifest/layout/resource/lifecycle/queue integration contracts')
