"""Install prevalidated local assets with exact index checks and rollback.

Run only with this candidate stopped. No account, executable or map script is
changed. Existing item ids and asset index order stay intact.
"""
from pathlib import Path
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
from fix_map_object_indices import ROOT
from idd_assets import load_index, read_asset
from rewrite_fx_index import write_index

shop = ROOT / 'port-audit/shop-port'
maps = ROOT / 'port-audit/boss-map-localization'
manifest = json.loads((shop / 'import-manifest.json').read_text(encoding='utf-8'))
localization = json.loads((maps / 'manifest.json').read_text(encoding='utf-8'))
assert not (shop / 'installed-assets.json').exists(), 'Already installed; do not append twice'
client = ROOT / 'client'
char_path, fx_path = client / 'CharTE.idx', client / 'fx.idx'
char_before, fx_before = char_path.read_bytes(), fx_path.read_bytes()
assert char_before == (shop / 'CharTE.idx.before').read_bytes(), 'CharTE index changed after preparation'
char_idx, _ = load_index(char_path)
char_after, _ = load_index(shop / 'CharTE.idx.candidate')
changed_names = {r['name'] for r in manifest['assetChanges']}
assert list(char_idx) == list(char_after)
assert {n for n in char_idx if char_idx[n] != char_after[n]} == changed_names
char_size = (client / 'CharTE.idd').stat().st_size
assert char_size == manifest['originalCharTEBytes']
append = (shop / 'CharTE.append.bin').read_bytes()
assert len(append) == manifest['appendBytes']
for row in manifest['assetChanges']:
    a = row['after']; offset = a['offset'] - char_size
    assert row['before'] == char_idx[row['name']]
    assert hashlib.sha256(append[offset:offset+a['size']]).hexdigest() == row['sourceSha256']
fx_idx, fx_key = load_index(fx_path)
name = localization['asset']; old = fx_idx[name]
assert old == localization['before']
assert hashlib.sha256(read_asset(client, old)).hexdigest() == localization['beforeSha256']
text = (maps / 'ClientString.zh-CN.bin').read_bytes()
assert struct.unpack_from('<I', text)[0] == len(text)-4
tree = ET.fromstring(text[4:].decode('latin1'))
entries = [n.findtext('key') for n in tree.findall('Elem')]
for k in localization['translations']:
    assert entries.count(k) == 1
assert hashlib.sha256(text).hexdigest() == localization['afterSha256']
fx_size = (client / 'fx.idd').stat().st_size
new = {**old, 'offset': fx_size, 'size': len(text)}
fx_idx[name] = new
(maps / 'fx.idx.before').write_bytes(fx_before)
try:
    with (client / 'CharTE.idd').open('ab') as f:
        f.write(append)
    char_path.write_bytes((shop / 'CharTE.idx.candidate').read_bytes())
    with (client / 'fx.idd').open('ab') as f:
        f.write(text)
    write_index(fx_path, fx_idx, fx_key)
    current, _ = load_index(char_path)
    for row in manifest['assetChanges']:
        assert hashlib.sha256(read_asset(client, current[row['name']], 'CharTE')).hexdigest() == row['sourceSha256']
    current, _ = load_index(fx_path)
    assert read_asset(client, current[name]) == text
except Exception:
    char_path.write_bytes(char_before)
    fx_path.write_bytes(fx_before)
    for package, size in [('CharTE', char_size), ('fx', fx_size)]:
        with (client / (package+'.idd')).open('r+b') as f:
            f.truncate(size)
    raise
(shop / 'installed-assets.json').write_text(json.dumps({
    'charTEOriginalBytes': char_size, 'charTEAppendedBytes': len(append),
    'fxOriginalBytes': fx_size, 'fxAppendedBytes': len(text),
    'charTEIndexSha256': hashlib.sha256(char_path.read_bytes()).hexdigest(),
    'fxIndexSha256': hashlib.sha256(fx_path.read_bytes()).hexdigest(),
    'mapStringBefore': old, 'mapStringAfter': new,
    'changedSprites': len(changed_names), 'readbackVerified': True,
    'accountFilesChanged': False, 'gameplayVerified': False,
}, indent=2), encoding='utf-8')
print('Installed and read back', len(changed_names), 'CN057 sprites and six Chinese map-selection descriptions')
