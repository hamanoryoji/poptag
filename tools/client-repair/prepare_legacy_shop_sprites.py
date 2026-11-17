"""Transcode reachable shop sprites without changing frame/model indexes."""
from audit_reported_bubbles import *
from legacy_sprite_codec import convert_sprite
from rewrite_fx_index import write_index
DEST=ROOT/'port-audit/shop-render-fix/legacy-pack'

def main():
    assert not DEST.exists(),'Do not overwrite a previous baseline'
    DEST.mkdir(parents=True)
    index,key=load_index(ROOT/'client/CharTE.idx');rows=current_items();wanted=set()
    for item in rows:
        if item['category']==14:
            s=item['param'];wanted.update(n for n in [f'Icon/Bomb{s:03d}icon',f'BombDeco/Bomb{s:03d}'] if n in index)
        elif item['category']==12 and item['name']=='Item/CharDeco' and item['skinId']==-1 and item['targetCharId'] in range(7):
            suffix=f'{chr(65+item["targetCharId"])}{item["param"]:04d}'
            wanted.update(n for n in index if n==f'Icon/CharDeco_{suffix}icon' or n.startswith('CharDeco/CharDeco_'+suffix) or n.startswith('CharDecoAdj/CharDecoAdj_'+suffix))
    append=bytearray();changes=[];errors=[];original=(ROOT/'client/CharTE.idd').stat().st_size
    for name in sorted(wanted):
        before=dict(index[name]);raw=read_asset(ROOT/'client',before,'CharTE')
        try:
            new,pages=convert_sprite(raw)
        except Exception as e:errors.append({'name':name,'error':repr(e)});continue
        if new==raw:continue
        after={**before,'offset':original+len(append),'size':len(new)}
        index[name]=after;append.extend(new)
        changes.append({'name':name,'before':before,'after':after,'pages':pages,'sha256':hashlib.sha256(new).hexdigest()})
    (DEST/'CharTE.idx.before').write_bytes((ROOT/'client/CharTE.idx').read_bytes())
    (DEST/'CharTE.append.bin').write_bytes(append);write_index(DEST/'CharTE.idx.candidate',index,key)
    report={'scannedSprites':len(wanted),'changedSprites':len(changes),'originalBytes':original,'appendBytes':len(append),'changes':changes,'errors':errors,'accountsChanged':False,'gameplayVerified':False}
    (DEST/'manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ['changes']},ensure_ascii=True))

if __name__=='__main__':main()
