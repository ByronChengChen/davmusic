"""SDK-free layout contracts; runtime geometry is covered by Robolectric in CI."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
ROOT = Path(__file__).resolve().parents[1]

class SearchHistoryLayoutTest(unittest.TestCase):
    def test_delete_is_beside_text_not_below_it(self):
        row = ET.parse(ROOT / 'app/src/main/res/layout/item_search_result.xml').getroot()
        self.assertEqual('horizontal', row.get(A + 'orientation'))
        text, expand, actions = list(row)
        self.assertEqual('vertical', text.get(A + 'orientation'))
        self.assertEqual('0dp', text.get(A + 'layout_width'))
        self.assertEqual('1', text.get(A + 'layout_weight'))
        self.assertEqual('ImageButton', expand.tag)
        self.assertEqual('@drawable/ic_history_expand', expand.get(A + 'src'))
        self.assertEqual('gone', expand.get(A + 'visibility'))
        self.assertEqual('@+id/searchHistoryActions', actions.get(A + 'id'))
        self.assertEqual('48dp', actions.get(A + 'layout_height'))
        self.assertEqual('gone', actions.get(A + 'visibility'))
        enter, delete = list(actions)
        self.assertEqual('@+id/searchHistoryEnterDirectory', enter.get(A + 'id'))
        self.assertEqual('@drawable/ic_history_folder', enter.get(A + 'src'))
        self.assertEqual('@+id/searchHistoryDelete', delete.get(A + 'id'))
        self.assertEqual('@drawable/ic_history_delete', delete.get(A + 'src'))
        self.assertEqual('48dp', delete.get(A + 'layout_height'))
        ids = [child.get(A + 'id') for child in text]
        self.assertEqual(['@+id/searchSongTitle', '@+id/searchSongLocation', '@+id/searchSongDownload'], ids)

if __name__ == '__main__':
    unittest.main()
