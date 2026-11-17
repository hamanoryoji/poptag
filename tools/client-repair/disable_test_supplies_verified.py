"""Restore the previously verified practice-only needle hooks, with exact-byte audit."""
from pathlib import Path
import hashlib,json,sys,runpy
import pefile
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,r'D:/GAME/work/PopTag-shop')
from idd_assets import load_index

def main():
    out=ROOT/'port-audit/end-test-supplies';out.mkdir(exist_ok=False)
    exe=ROOT/'client/CA.exe';fx=ROOT/'client/fx.idx'
    before=exe.read_bytes();index,_=load_index(fx);oldindex=dict(index)
    (out/'CA.exe.before').write_bytes(before);(out/'fx.idx.before').write_bytes(fx.read_bytes())
    manifest=json.loads((ROOT/'port-audit/test-supplies/native-patch.json').read_text())
    pe=pefile.PE(data=before);expected=bytearray(before);allowed=set()
    for row in manifest['patches']:
        off=pe.get_offset_from_rva(row['address']-0x400000);old=bytes.fromhex(row['before']);new=bytes.fromhex(row['after'])
        assert before[off:off+len(old)] in (old,new),hex(row['address'])
        # Preserve the existing .local section access flags: later unrelated
        # gameplay adapters can use its data. Flags do not enable test supplies.
        if row['address']!=0x40034c:
            expected[off:off+len(old)]=old
        allowed.update(range(off,off+len(old)))
    sys.argv=['toggle_test_supplies.py','off']
    runpy.run_path(str(ROOT/'toggle_test_supplies.py'),run_name='__main__')
    restored=bytearray(exe.read_bytes());flags=pe.get_offset_from_rva(0x34c)
    restored[flags:flags+4]=before[flags:flags+4];exe.write_bytes(restored)
    after=exe.read_bytes();assert after==expected
    assert all(i in allowed for i,(a,b) in enumerate(zip(before,after)) if a!=b)
    current,_=load_index(fx);asset=json.loads((ROOT/'port-audit/test-supplies/asset-patch.json').read_text())
    assert current[asset['name']]==asset['before']
    assert list(current)==list(oldindex)
    assert all(current[k]==oldindex[k] for k in current if k!=asset['name'])
    assert not (ROOT/'test-supplies.enabled').exists()
    (out/'verified.json').write_text(json.dumps({'nativePatchRangesRestored':len(manifest['patches'])-1,
        'beforeSha256':hashlib.sha256(before).hexdigest(),'afterSha256':hashlib.sha256(after).hexdigest(),
        'originalPracticeNeedleHooksRestored':True,'automaticTestShieldHookRemoved':True,
        'existingSectionPermissionsPreserved':True,'resourceEntriesAndMapOrderPreserved':True,'testSuppliesMarkerRemoved':True},indent=2))
    print('Verified 9 restored native ranges; practice-only needle retained; test shield removed; other resources and section flags preserved.')

if __name__=='__main__':main()
