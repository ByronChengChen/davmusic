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
        text, delete = list(row)
        self.assertEqual('vertical', text.get(A + 'orientation'))
        self.assertEqual('0dp', text.get(A + 'layout_width'))
        self.assertEqual('1', text.get(A + 'layout_weight'))
        self.assertEqual('@+id/searchHistoryDelete', delete.get(A + 'id'))
        self.assertEqual('48dp', delete.get(A + 'layout_height'))
        ids = [child.get(A + 'id') for child in text]
        self.assertEqual(['@+id/searchSongTitle', '@+id/searchSongLocation', '@+id/searchSongDownload'], ids)
        self.assertEqual('gone', delete.get(A + 'visibility'))

if __name__ == '__main__':
    unittest.main()
