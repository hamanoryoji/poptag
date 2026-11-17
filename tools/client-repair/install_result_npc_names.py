"""Install only after isolated native checks, with the client stopped."""
from pathlib import Path
import json,hashlib,struct
import pefile
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'port-audit/result-exp-names/npc-adapter'
def main():
    m=json.loads((OUT/'manifest.json').read_text(encoding='utf-8'))
    native=json.loads((OUT/'native-test-result.json').read_text(encoding='utf-8-sig'))
    reader=json.loads((OUT.parent/'native-reader-test-result.json').read_text(encoding='utf-8-sig'))
    assert native['passed']>=59 and reader['passed']>=44
    path=ROOT/'client/CA.exe';before=path.read_bytes();after=(OUT/'CA.exe.candidate').read_bytes()
    assert hashlib.sha256(before).hexdigest()==m['beforeSha256']
    assert hashlib.sha256(after).hexdigest()==m['candidateSha256']
    pe=pefile.PE(data=before);newpe=pefile.PE(data=after)
    allowed=set(range(m['siteOffset'],m['siteOffset']+5))
    for obj,field,size in [(pe.FILE_HEADER,'NumberOfSections',2),(pe.OPTIONAL_HEADER,'SizeOfImage',4)]:
        o=obj.get_field_absolute_offset(field);allowed.update(range(o,o+size))
    header=pe.sections[-1].get_file_offset()+40;allowed.update(range(header,header+40))
    assert all(i in allowed for i,(a,b) in enumerate(zip(before,after)) if a!=b)
    assert newpe.sections[-1].Name.rstrip(b'\0')==b'.result'
    assert newpe.get_data(m['base']-0x400000,len(bytes.fromhex(m['payload'])))==bytes.fromhex(m['payload'])
    path.write_bytes(after);assert hashlib.sha256(path.read_bytes()).hexdigest()==m['candidateSha256']
    m['installed']=True;(OUT/'manifest.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Installed one AI result-name call and new section; all previous executable behavior bytes preserved.')
if __name__=='__main__':main()
