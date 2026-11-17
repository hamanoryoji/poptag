from prepare_npc_level_restore import ROOT,OUT
from prepare_remaining_ui import sha
from idd_assets import load_index,read_asset
import json

def main():
    client=ROOT/'client';exe=client/'CA.exe';idx=client/'fx.idx';idd=client/'fx.idd'
    m=json.loads((OUT/'manifest.json').read_text());test=json.loads((OUT/'native-test-result.json').read_text(encoding='utf-8-sig'))
    assert test['passed']>=27 and not test['gameProcessTouched']
    assert not (OUT/'installed.json').exists()
    assert sha(exe.read_bytes())==m['beforeSha256'] and sha(idx.read_bytes())==m['originalIdxSha256']
    assert idd.stat().st_size==m['originalIddSize']
    candidate=(OUT/'CA.exe.candidate').read_bytes();assert sha(candidate)==m['candidateSha256']
    try:
        with idd.open('ab') as f:f.write((OUT/'fx.append.bin').read_bytes())
        idx.write_bytes((OUT/'fx.idx.candidate').read_bytes());exe.write_bytes(candidate)
        index,_=load_index(idx);assert sha(read_asset(client,index['Etc/LevelIcons']))==m['iconSha256']
        for row in m['patches']:
            at=row['offset'];assert candidate[at:at+len(bytes.fromhex(row['after']))].hex()==row['after']
    except Exception:
        exe.write_bytes((OUT/'CA.exe.before').read_bytes());idx.write_bytes((OUT/'fx.idx.before').read_bytes())
        with idd.open('r+b') as f:f.truncate(m['originalIddSize'])
        raise
    (OUT/'installed.json').write_text(json.dumps({'maxLevel':720,'medalFrames':92,'npcBoostsReverted':True,'npcDifficultyBadgeMapping':[3,10,52,109,163],'isolatedAssertions':test['passed'],'liveVerified':False},indent=2))
    print((OUT/'installed.json').read_text())
if __name__=='__main__':main()
