"""Host-only resource/static contracts. NOT Android inflation or behavior tests.
Run from repository root: python3 -m unittest discover -s tools -p 'test_visual_refresh.py' -v
Baseline is the v1.53 commit, not the mutable working tree.
"""
import pathlib
import re
import subprocess
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
BASE = '3a192b0'
A = '{http://schemas.android.com/apk/res/android}'
VISUAL = {'background', 'backgroundTint', 'textColor', 'textColorHint', 'tint',
          'titleTextColor', 'subtitleTextColor', 'textSize', 'textStyle',
          'fontFamily', 'letterSpacing', 'elevation', 'progressTint', 'indeterminateTint'}


def original(path):
    return subprocess.check_output(['git', 'show', f'{BASE}:{path}'], cwd=ROOT)


def tree_contract(node):
    return (node.tag, sorted((k, v) for k, v in node.attrib.items()
                            if k.split('}')[-1] not in VISUAL),
            [tree_contract(c) for c in node])


def colors(folder):
    raw = {e.attrib['name']: e.text for e in ET.parse(RES / folder / 'colors.xml').getroot()}
    def resolve(name):
        value = raw[name]
        assert value is not None, name
        return resolve(value.split('/', 1)[1]) if value.startswith('@color/') else value
    return {name: resolve(name) for name in raw}


def luminance(color):
    c = color.lstrip('#')
    assert len(c) == 6, color
    values = [int(c[i:i+2], 16) / 255 for i in (0, 2, 4)]
    linear = [v/12.92 if v <= .04045 else ((v+.055)/1.055)**2.4 for v in values]
    return sum(v*w for v, w in zip(linear, (.2126, .7152, .0722)))


def contrast(a, b):
    x, y = sorted([luminance(a), luminance(b)])
    return (y+.05)/(x+.05)


class VisualRefreshContracts(unittest.TestCase):
    def test_target_palette(self):
        for folder, bg, fg, accent in [('values', '#F2F2F7', '#1C1C1E', '#C92C48'),
                                        ('values-night', '#000000', '#F5F5F7', '#FF7188')]:
            with self.subTest(folder=folder):
                c = colors(folder)
                self.assertEqual(bg, c['background'])
                self.assertEqual(fg, c['text_primary'])
                self.assertEqual(accent, c['accent'])
                self.assertEqual('#C92C48', c['primary'])
                self.assertEqual(c['text_primary'], c['download_state_downloaded'])
                self.assertEqual(c['text_secondary'], c['network_online'])
                self.assertEqual(c['error'], c['network_offline'])
                self.assertEqual(accent, c['player_progress_foreground'])
                self.assertEqual(accent, c['item_playing_text'])

    def test_all_xml_well_formed(self):
        for p in RES.rglob('*.xml'):
            with self.subTest(file=str(p)):
                ET.parse(p)

    def test_local_resource_references_resolve(self):
        names = {}
        for p in RES.rglob('*.xml'):
            node = ET.parse(p).getroot()
            if p.parent.name.startswith('values'):
                for child in node:
                    names.setdefault(child.tag, set()).add(child.get('name'))
            else:
                names.setdefault(p.parent.name.split('-')[0], set()).add(p.stem)
        for p in RES.rglob('*.xml'):
            for kind, name in re.findall(r'@(?!(?:android):)(color|drawable|layout|style|bool|string)/([\w.]+)', p.read_text()):
                with self.subTest(file=p.name, resource=(kind, name)):
                    self.assertIn(name, names.get(kind, set()))

    def test_layout_structure_actions_and_touch_geometry_unchanged(self):
        for folder in ['layout', 'menu']:
            for p in (RES / folder).glob('*.xml'):
                with self.subTest(file=p.name):
                    self.assertEqual(tree_contract(ET.fromstring(original(str(p.relative_to(ROOT))))),
                                     tree_contract(ET.parse(p).getroot()))

    def test_every_light_color_has_night_value(self):
        self.assertEqual(set(colors('values')), set(colors('values-night')))

    def test_accessible_text_pairs(self):
        for folder in ['values', 'values-night']:
            c = colors(folder)
            pairs = [('text_primary','background'), ('text_secondary','background'),
                     ('text_primary','surface'), ('text_secondary','surface'),
                     ('text_primary','player_background'), ('text_secondary','player_background'),
                     ('item_playing_text','item_playing_background'), ('white','button_normal'),
                     ('accent','surface'), ('toolbar_text','toolbar_background'),
                     ('white','destructive_background')]
            pairs += [('download_state_downloaded', 'background'),
                      ('download_state_downloaded', 'surface'),
                      ('network_online', 'background'), ('network_online', 'surface')]
            for fg, bg in pairs:
                with self.subTest(mode=folder, pair=(fg, bg)):
                    self.assertGreaterEqual(contrast(c[fg], c[bg]), 4.5)

    def test_protected_files_and_nonvisual_java_unchanged(self):
        for path in ['AGENTS.md', 'app/src/main/AndroidManifest.xml',
                     'app/src/main/res/values/strings.xml', '.github/workflows/build.yml']:
            self.assertEqual(original(path), (ROOT / path).read_bytes(), path)
        for p in (ROOT/'app/src/main/java').rglob('*.java'):
            before = original(str(p.relative_to(ROOT))).decode()
            after = p.read_text()
            if p.name == 'MainActivity.java':
                before = before.replace('android.R.color.holo_red_dark', 'R.color.network_offline')
                before = before.replace('android.R.color.holo_green_dark', 'R.color.network_online')
                # Swipe-delete is a destructive surface, not a network-status label.
                before = before.replace('backPaint.setColor(ContextCompat.getColor(this, R.color.network_offline))',
                                        'backPaint.setColor(ContextCompat.getColor(this, R.color.destructive_background))')
            self.assertEqual(before, after, str(p))

    def test_version_only_gradle_change(self):
        p = 'app/build.gradle'
        expected = original(p).decode().replace('versionCode 153', 'versionCode 155').replace('versionName "1.53"', 'versionName "1.55"')
        self.assertEqual(expected, (ROOT/p).read_text())

    def test_lyrics_no_fixed_blue(self):
        root = ET.parse(RES/'layout/activity_lyrics.xml').getroot()
        button = next(e for e in root.iter() if e.get(A+'id') == '@+id/searchLyricsButton')
        self.assertEqual('@color/button_normal', button.get(A+'backgroundTint'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
