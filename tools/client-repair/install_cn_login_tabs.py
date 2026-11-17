"""Install the verified login-only renderer adapter with exact rollback."""
from prepare_cn_login_tabs import DEST,ROOT
from idd_assets import load_index,read_asset
import json,hashlib,pefile,struct

sha=lambda b:hashlib.sha256(b).hexdigest()
def main():
    m=json.loads((DEST/'manifest.json').read_text(encoding='utf-8'))
    assert not (DEST/'installed.json').exists()
    client=ROOT/'client';exe=client/'CA.exe';index=client/'fx.idx';idd=client/'fx.idd'
    before=exe.read_bytes();idx_before=index.read_bytes()
    assert before==(DEST/'CA.exe.before').read_bytes() and idx_before==(DEST/'fx.idx.before').read_bytes()
    assert idd.stat().st_size==m['fxOriginalBytes']
    candidate=(DEST/'CA.exe.candidate').read_bytes();p=pefile.PE(data=before);q=pefile.PE(data=candidate)
    assert q.FILE_HEADER.NumberOfSections==p.FILE_HEADER.NumberOfSections+1
    assert q.sections[-1].Name==b'.cnlogin'
    # The entire previous executable remains identical apart from the section
    # header/count/size metadata and this one login drawing call.
    allowed=set(range(m['headerOffset'],m['headerOffset']+40))|set(range(m['siteOffset'],m['siteOffset']+5))
    for field,size in [('NumberOfSections',2)]:
        o=p.FILE_HEADER.get_field_absolute_offset(field);allowed.update(range(o,o+size))
    o=p.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage');allowed.update(range(o,o+4))
    assert all(a==b or i in allowed for i,(a,b) in enumerate(zip(before,candidate)))
    old,_=load_index(index);new,_=load_index(DEST/'fx.idx.candidate')
    assert all(new[k]==v for k,v in old.items()),'Existing asset entries must remain unchanged'
    assert set(new)-set(old)=={r['name'] for r in m['assets']}
    assert [n for n in old if n.startswith('Map/')]==[n for n in new if n.startswith('Map/')]
    try:
        with idd.open('ab') as f:f.write((DEST/'fx.append.bin').read_bytes())
        index.write_bytes((DEST/'fx.idx.candidate').read_bytes());exe.write_bytes(candidate)
        for row in m['assets']:assert sha(read_asset(client,new[row['name']]))==row['sha256']
        assert exe.read_bytes()==candidate
    except Exception:
        exe.write_bytes(before);index.write_bytes(idx_before)
        with idd.open('r+b') as f:f.truncate(m['fxOriginalBytes'])
        raise
    (DEST/'installed.json').write_text(json.dumps({'exeSha256':sha(candidate),'verifiedResources':len(m['assets']),
        'oldResourcesAndMapOrderPreserved':True,'changedNativeCall':hex(m['site']),'authenticationChanged':False,
        'nativeIsolatedAssertions':28,'liveVerified':False},indent=2),encoding='utf-8')
    print((DEST/'installed.json').read_text())

if __name__=='__main__':main()
