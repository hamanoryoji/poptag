"""Install verified UI append with exact index rollback; client must be stopped."""
from prepare_remaining_ui import ROOT,OUT,sha
from idd_assets import load_index,read_asset
import json

def main():
    client=ROOT/'client';idx=client/'fx.idx';idd=client/'fx.idd'
    m=json.loads((OUT/'manifest.json').read_text(encoding='utf-8'))
    assert not (OUT/'installed.json').exists()
    assert sha(idx.read_bytes())==m['originalIdxSha256']
    assert idd.stat().st_size==m['originalIddSize']
    old,_=load_index(idx);new,_=load_index(OUT/'fx.idx.candidate')
    changed={r['name'] for r in m['changes']}
    assert list(old)==list(new) and all(old[n]==new[n] for n in old if n not in changed)
    assert [n for n in old if n.startswith('Map/')]==[n for n in new if n.startswith('Map/')]
    try:
        with idd.open('ab') as f:f.write((OUT/'fx.append.bin').read_bytes())
        idx.write_bytes((OUT/'fx.idx.candidate').read_bytes())
        for row in m['changes']:assert sha(read_asset(client,new[row['name']]))==row['sha256']
    except Exception:
        idx.write_bytes((OUT/'fx.idx.before').read_bytes())
        with idd.open('r+b') as f:f.truncate(m['originalIddSize'])
        raise
    result={'resources':len(changed),'frameGeometryPreserved':True,'mapOrderPreserved':True,'exeChanged':False,'liveVerified':False}
    (OUT/'installed.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result))
if __name__=='__main__':main()
