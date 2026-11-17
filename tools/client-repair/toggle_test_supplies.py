"""Run with the candidate stopped: on/off, with byte-verified rollback."""
from pathlib import Path
import json, sys
import pefile
sys.path.insert(0, 'D:/GAME/work/PopTag-shop')
from idd_assets import load_index
from rewrite_fx_index import write_index

root = Path(__file__).resolve().parent
mode = sys.argv[1]
assert mode in ('on', 'off')
path = root / 'client/CA.exe'
data = bytearray(path.read_bytes())
pe = pefile.PE(data=data)
manifest = json.loads((root / 'port-audit/test-supplies/native-patch.json').read_text())
for row in manifest['patches']:
    offset = pe.get_offset_from_rva(row['address'] - 0x400000)
    before, after = bytes.fromhex(row['before']), bytes.fromhex(row['after'])
    current = data[offset:offset + len(before)]
    assert current in (before, after), f'Unrecognized patch bytes at {row["address"]:x}; refusing overwrite'
    data[offset:offset + len(before)] = after if mode == 'on' else before
asset_manifest = root / 'port-audit/test-supplies/asset-patch.json'
if asset_manifest.exists():
    asset = json.loads(asset_manifest.read_text())
    index, key = load_index(root/'client/fx.idx')
    assert index[asset['name']] in (asset['before'], asset['after']), 'Shield icon entry was changed by another tool'
    index[asset['name']] = asset['after'] if mode == 'on' else asset['before']
    write_index(root/'client/fx.idx', index, key)
path.write_bytes(data)
flag = root / 'test-supplies.enabled'
if mode == 'on': flag.write_text('Temporary unlimited needle, dragon fruit and automatic green shield.\n')
else: flag.unlink(missing_ok=True)
print('Test supplies:', mode)
