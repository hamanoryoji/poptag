"""Install prepared client files; verify every asset and roll back on error."""
from prepare_cn324_shop_batch import DEST, ROOT, sha
from idd_assets import load_index,read_asset
import json

def main():
    marker=DEST/'installed.json';assert not marker.exists(),'Already installed'
    m=json.loads((DEST/'manifest.json').read_text(encoding='utf-8'));client=ROOT/'client'
    original={}
    for name in ['CA.exe','CharTE.idx','fx.idx']:
        original[name]=(client/name).read_bytes()
        assert original[name]==(DEST/(name+'.before')).read_bytes(),name+' changed after preparation'
    candidate=(DEST/'CA.exe.candidate').read_bytes()
    assert sha(candidate)==m['exeAfterSha256']
    positions={p['offset'] for p in m['exePatches']}
    assert len(original['CA.exe'])==len(candidate)
    assert {i for i,(a,b) in enumerate(zip(original['CA.exe'],candidate)) if a!=b}==positions
    sizes={p:m[k] for p,k in [('CharTE','charOriginalBytes'),('fx','fxOriginalBytes')]}
    for p,size in sizes.items():assert (client/(p+'.idd')).stat().st_size==size
    try:
        for package in sizes:
            with (client/(package+'.idd')).open('ab') as f:f.write((DEST/(package+'.append.bin')).read_bytes())
            (client/(package+'.idx')).write_bytes((DEST/(package+'.idx.candidate')).read_bytes())
        (client/'CA.exe').write_bytes(candidate)
        for package,key in [('CharTE','charChanges'),('fx','fxChanges')]:
            index,_=load_index(client/(package+'.idx'))
            before,_=load_index(DEST/(package+'.idx.before'))
            assert list(index)==list(before),'No numeric resource index changes permitted'
            assert {n for n in index if index[n]!=before[n]}=={r['name'] for r in m[key]}
            for row in m[key]:assert sha(read_asset(client,index[row['name']],package))==row['sha256']
        assert sha((client/'CA.exe').read_bytes())==m['exeAfterSha256']
    except Exception:
        for name,b in original.items():(client/name).write_bytes(b)
        for package,size in sizes.items():
            with (client/(package+'.idd')).open('r+b') as f:f.truncate(size)
        raise
    marker.write_text(json.dumps({'readbackVerified':True,'accountFilesChanged':False,'gameplayVerified':False,
        'newProducts':len(m['batch']),'shopAssets':len(m['fxChanges']),'charSprites':len(m['charChanges']),
        'exeSha256':sha(candidate)},indent=2),encoding='utf-8')
    print(marker.read_text())

if __name__=='__main__':main()
