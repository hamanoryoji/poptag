"""Install candidate client after exact baseline checks; restore on any failure."""
from prepare_login_face_emote import DEST,ROOT,sha
from legacy_sprite_codec import convert_sprite
from idd_assets import load_index,read_asset
import json

def main():
    marker=DEST/'installed.json';assert not marker.exists()
    m=json.loads((DEST/'manifest.json').read_text(encoding='utf-8'));client=ROOT/'client';original={}
    for name in ['CA.exe','CharTE.idx','fx.idx']:
        original[name]=(client/name).read_bytes();assert original[name]==(DEST/(name+'.before')).read_bytes(),name
    candidate=(DEST/'CA.exe.candidate').read_bytes();assert sha(candidate)==m['exeSha256']
    allowed=set()
    for p in m['exePatches']:allowed.update(range(p['offset'],p['offset']+len(bytes.fromhex(p['before']))))
    assert len(original['CA.exe'])==len(candidate)
    assert all(a==b or i in allowed for i,(a,b) in enumerate(zip(original['CA.exe'],candidate)))
    for package,v in m['packages'].items():assert (client/f'{package}.idd').stat().st_size==v['originalBytes']
    try:
        for package,v in m['packages'].items():
            with (client/f'{package}.idd').open('ab') as f:f.write((DEST/f'{package}.append.bin').read_bytes())
            (client/f'{package}.idx').write_bytes((DEST/f'{package}.idx.candidate').read_bytes())
            old,_=load_index(DEST/f'{package}.idx.before');new,_=load_index(client/f'{package}.idx');assert list(old)==list(new)
            assert {n for n in old if old[n]!=new[n]}=={r['name'] for r in v['changes']}
            for r in v['changes']:
                raw=read_asset(client,new[r['name']],package);assert sha(raw)==r['sha256']
                rebuilt,pages=convert_sprite(raw);assert rebuilt==raw and not pages
        (client/'CA.exe').write_bytes(candidate);assert sha((client/'CA.exe').read_bytes())==m['exeSha256']
    except Exception:
        for name,b in original.items():(client/name).write_bytes(b)
        for package,v in m['packages'].items():
            with (client/f'{package}.idd').open('r+b') as f:f.truncate(v['originalBytes'])
        raise
    marker.write_text(json.dumps({'newProducts':m['counts'],'assetReadbackVerified':True,'loginCoordinateSites':len(m['exePatches']),'accountsChanged':False,'liveVerified':False},indent=2),encoding='utf-8')
    print(marker.read_text())

if __name__=='__main__':main()
