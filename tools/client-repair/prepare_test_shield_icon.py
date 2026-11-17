"""Use the actual national automatic-shield sprite, with reversible aliasing."""
import sys, json, hashlib
from pathlib import Path
sys.path.insert(0, 'D:/GAME/work/PopTag-shop')
from idd_assets import load_index, read_asset

root = Path(__file__).resolve().parent
source = Path('D:/GAME/泡泡堂各个版本/国服 324 版')
audit = root/'port-audit/test-supplies'
manifest = audit/'asset-patch.json'
assert not manifest.exists()
index, key = load_index(root/'client/fx.idx')
national, _ = load_index(source/'Fx.idx')
entry = national['Item/ItemAutoShieldC']
sprite = read_asset(source, entry, 'Fx')
assert sprite[:8] == bytes.fromhex('0100000002000000')
before = index['Item/ShieldC']
idd = root/'client/fx.idd'
offset = idd.stat().st_size
after = dict(before, offset=offset, size=len(sprite), flags=entry['flags'], key=entry['key'])
(audit/'fx.idx.before-test-shield-icon').write_bytes((root/'client/fx.idx').read_bytes())
with idd.open('ab') as output: output.write(sprite)
manifest.write_text(json.dumps(dict(name='Item/ShieldC', sourceName='Item/ItemAutoShieldC',
    source=str(source), sourceSha256=hashlib.sha256(sprite).hexdigest(),
    before=before, after=after, appendedOffset=offset, appendedSize=len(sprite)), indent=2))
print('Prepared national green automatic-shield icon; index alias not enabled yet')
