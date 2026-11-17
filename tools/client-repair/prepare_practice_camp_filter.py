"""Expose the existing Camp group in solo practice, preserving NPC filtering."""
from pathlib import Path
import pefile,hashlib,json

ROOT=Path(__file__).resolve().parent
DEST=ROOT/'port-audit/practice-camp-filter'

def main():
    assert not DEST.exists()
    DEST.mkdir()
    path=ROOT/'client/CA.exe';original=path.read_bytes()
    pe=pefile.PE(data=original,fast_load=True)
    offset=pe.get_offset_from_rva(0x52ff34-0x400000)
    # 52FF2A: mode16 still jumps to Camp exclusion at 52FF36.
    # 52FF34: mode15 used to fall through to that same exclusion.
    assert original[offset:offset+2]==bytes.fromhex('7511')
    assert original[offset-6:offset]==bytes.fromhex('7406837d0c0f')
    candidate=bytearray(original);candidate[offset]=0xeb
    assert [i for i,(a,b) in enumerate(zip(original,candidate)) if a!=b]==[offset]
    (DEST/'CA.exe.before').write_bytes(original)
    (DEST/'CA.exe.candidate').write_bytes(candidate)
    report={'address':'0x52ff34','offset':offset,'before':'75','after':'eb',
            'beforeSha256':hashlib.sha256(original).hexdigest(),
            'candidateSha256':hashlib.sha256(candidate).hexdigest(),
            'practiceCampAllowed':True,'npcCampExclusionPreserved':True,'runtimeVerified':False}
    (DEST/'manifest.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report))

if __name__=='__main__':main()
