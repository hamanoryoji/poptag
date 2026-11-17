"""Append validated legacy sprites while the local candidate is stopped."""
from prepare_legacy_shop_sprites import DEST,ROOT
from legacy_sprite_codec import convert_sprite
from idd_assets import load_index,read_asset
import json,hashlib

m=json.loads((DEST/'manifest.json').read_text(encoding='utf-8'))
assert not m['errors'] and not (DEST/'installed.json').exists()
client=ROOT/'client';indexPath=client/'CharTE.idx';idd=client/'CharTE.idd'
before=indexPath.read_bytes();assert before==(DEST/'CharTE.idx.before').read_bytes()
assert idd.stat().st_size==m['originalBytes']
old,_=load_index(indexPath);new,_=load_index(DEST/'CharTE.idx.candidate')
assert list(old)==list(new)
assert {n for n in old if old[n]!=new[n]}=={r['name'] for r in m['changes']}
append=(DEST/'CharTE.append.bin').read_bytes()
assert len(append)==m['appendBytes']
try:
    with idd.open('ab') as f:f.write(append)
    indexPath.write_bytes((DEST/'CharTE.idx.candidate').read_bytes())
    for r in m['changes']:
        raw=read_asset(client,new[r['name']],'CharTE')
        assert hashlib.sha256(raw).hexdigest()==r['sha256']
        rebuilt,pages=convert_sprite(raw)
        assert rebuilt==raw and not pages,'No modern page markers may survive conversion'
except Exception:
    indexPath.write_bytes(before)
    with idd.open('r+b') as f:f.truncate(m['originalBytes'])
    raise
(DEST/'installed.json').write_text(json.dumps({'verifiedSprites':len(m['changes']),'decodedModernPages':sum(len(x['pages']) for x in m['changes']),
    'appendBytes':len(append),'preservedModelAndFrameIndexes':True,'liveVerified':False},indent=2),encoding='utf-8')
print((DEST/'installed.json').read_text())
