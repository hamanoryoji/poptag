"""Prepare a bounded CN324 catalog batch and a geometry-compatible CN057 skin.

Keep the existing 16-bit item-table budget until a larger native table has been
verified. Do not activate modern avatar clothing or unimplemented consumables.
"""
from audit_shop324_compat import *
from decode_idd_preview import first_frame
from legacy_sprite_codec import convert_sprite, frame_from_sprite
import numpy as np

DEST=ROOT/'port-audit/shop-324-batch1'
FIELDS=['itemId','category','name','skinId','targetCharId','param','desc','flags','status']

def sha(b):return hashlib.sha256(b).hexdigest()

def has_diy_badge(image,templates):
    a=np.asarray(image.convert('RGB'))
    for t in templates:
        h,w=t.shape[:2]
        if a.shape[0]<h or a.shape[1]<w:continue
        # Search a distinctive first row before comparing the whole badge.
        hay=a.tobytes();needle=t[0].tobytes();pos=hay.find(needle)
        while pos>=0:
            if pos%3==0:
                y,x=divmod(pos//3,a.shape[1])
                if y+h<=a.shape[0] and x+w<=a.shape[1] and np.array_equal(a[y:y+h,x:x+w],t):return True
            pos=hay.find(needle,pos+1)
    return False

def main():
    assert not DEST.exists(),'Do not overwrite a previous audit/baseline'
    DEST.mkdir()
    national=national_items();current=current_items();idx,models=model_names(ROOT/'client')
    source,_=load_index(CN324/'CharTE.idx');existing={identity(i) for i in current}
    templates=[]
    for s in sorted(DIY_STYLES):
        a=np.asarray(first_frame(ROOT/'client',f'Icon/Bomb{s:03d}icon','CharTE').convert('RGB'))
        templates.append(a[-11:-2,-24:-2])
    groups={12:[],14:[]};rejected=Counter();diy=set(DIY_STYLES)
    seen=set(existing)
    for original in national:
        i=dict(original);cat=i['category'];model=None;assets=[]
        if cat==14 and i['name']=='Item/Bomb':
            model=f'BombDeco/Bomb{i["param"]:03d}';icon=f'Icon/Bomb{i["param"]:03d}icon'
            if i['param'] in {6,122} or i['param'] in diy:continue
            if model not in models or icon not in idx:rejected['bubble missing model/icon']+=1;continue
            if has_diy_badge(frame_from_sprite(read_asset(ROOT/'client',idx[icon],'CharTE')),templates):
                diy.add(i['param']);continue
            i['name']=model
            assets=[model,icon]
        elif cat==12 and i['name']=='Item/CharDeco' and i['skinId']==-1 and i['targetCharId'] in range(7):
            suffix=f'{chr(65+i["targetCharId"])}{i["param"]:04d}'
            model=f'CharDeco/CharDeco_{suffix}';icon=f'Icon/CharDeco_{suffix}icon'
            if model not in models or icon not in idx:rejected['decoration missing model/icon']+=1;continue
            assets=[n for n in source if n==icon or n.startswith('CharDeco/'+f'CharDeco_{suffix}') or n.startswith('CharDecoAdj/'+f'CharDecoAdj_{suffix}')]
        else:continue
        ident=identity(i)
        if ident in seen:continue
        if not assets or any(n not in source or n not in idx for n in assets):rejected['missing source asset']+=1;continue
        if any(source[n]['flags']!=0 or idx[n]['flags']!=0 for n in assets):rejected['unsupported sprite flags']+=1;continue
        if any(len(b:=read_asset(CN324,source[n],'CharTE'))<16 or struct.unpack_from('<I',b,4)[0]!=2 for n in assets):
            rejected['unsupported sprite version']+=1;continue
        seen.add(ident);groups[cat].append({**i,'sourceItemId':i['itemId'],'itemId':950_000_000+i['itemId'],'flags':0,'status':1,'assets':assets})
    # Small first batch to respect the verified legacy ItemDef size budget.
    products=[]
    for cat in [14,12]:products.extend(groups[cat][:24])
    assert len(products)==48
    assert not ({i['itemId'] for i in products}&{i['itemId'] for i in current})
    extras=DATA/'local_extra_items.tsv'
    (DEST/'local_extra_items.before.tsv').write_bytes(extras.read_bytes())
    (DEST/'local_extra_items.candidate.tsv').write_text(extras.read_text(encoding='utf-8-sig').rstrip()+'\n'+''.join('\t'.join(str(i[k]) for k in FIELDS)+'\n' for i in products),encoding='utf-8')
    names=ROOT/'server/src/main/resources/localization/zh-CN-official-item-names.tsv'
    (DEST/'names.before.tsv').write_bytes(names.read_bytes())
    (DEST/'names.candidate.tsv').write_text(names.read_text(encoding='utf-8').rstrip()+'\n'+''.join(f'{i["itemId"]}\t{i["desc"]}\n' for i in products),encoding='utf-8')
    # Append only unused appearance sprites; preserve model IDs and all old IDs.
    changed=[];append=bytearray();base=(ROOT/'client/CharTE.idd').stat().st_size
    entries,key=load_index(ROOT/'client/CharTE.idx')
    for n in sorted({n for i in products for n in i['assets']}):
        content=convert_sprite(read_asset(CN324,source[n],'CharTE'))[0];before=entries[n]
        if content==read_asset(ROOT/'client',before,'CharTE'):continue
        after={**before,'offset':base+len(append),'size':len(content),'key':source[n]['key']}
        entries[n]=after;append.extend(content);changed.append({'name':n,'before':before,'after':after,'sha256':sha(content)})
    (DEST/'CharTE.idx.before').write_bytes((ROOT/'client/CharTE.idx').read_bytes())
    (DEST/'CharTE.append.bin').write_bytes(append);write_index(DEST/'CharTE.idx.candidate',entries,key)
    # CN057 visual controls only when frame geometry is identical to old controls.
    fx,key=load_index(ROOT/'client/fx.idx');cnfx,_=load_index(CN057/'fx.idx')
    fxbase=(ROOT/'client/fx.idd').stat().st_size;fxappend=bytearray();fxchanges=[];geometrySkipped=[]
    for n,before in list(fx.items()):
        if not n.startswith('Shop/') or n not in cnfx or before['flags'] or cnfx[n]['flags']:continue
        old=read_asset(ROOT/'client',before);new=read_asset(CN057,cnfx[n])
        if n!='Shop/Stage':
            if min(len(old),len(new))<24:continue
            count,version,pages=struct.unpack_from('<3I',old)
            end=16+pages*8+count*48
            if version!=2 or old[:12]+old[16:end]!=new[:12]+new[16:end]:geometrySkipped.append(n);continue
        if old==new:continue
        after={**before,'offset':fxbase+len(fxappend),'size':len(new),'key':cnfx[n]['key']}
        fx[n]=after;fxappend.extend(new);fxchanges.append({'name':n,'before':before,'after':after,'sha256':sha(new)})
    (DEST/'fx.idx.before').write_bytes((ROOT/'client/fx.idx').read_bytes())
    (DEST/'fx.append.bin').write_bytes(fxappend);write_index(DEST/'fx.idx.candidate',fx,key)
    exe=(ROOT/'client/CA.exe').read_bytes();candidate=bytearray(exe)
    # In the shop renderer only, select its existing single-cash-field positions.
    # This does not alter locale/protocol/character/gameplay branches.
    import pefile
    pe=pefile.PE(data=exe);patches=[]
    for address,old,new in [(0x5d086e,b'\x74\x07',b'\xeb\x07'),(0x5d08d6,b'\x74\x23',b'\xeb\x23'),(0x5d0937,b'\x74\x36',b'\xeb\x36')]:
        o=pe.get_offset_from_rva(address-pe.OPTIONAL_HEADER.ImageBase);assert exe[o:o+len(old)]==old,hex(address)
        candidate[o:o+len(old)]=new;patches.append({'address':hex(address),'offset':o,'before':old.hex(),'after':new.hex()})
    (DEST/'CA.exe.before').write_bytes(exe);(DEST/'CA.exe.candidate').write_bytes(candidate)
    manifest={'source':str(CN324),'sourceItems':len(national),'eligibleAfterDedup':{str(k):len(v) for k,v in groups.items()},
              'batch':products,'pendingCompatibleProducts':sum(map(len,groups.values()))-len(products),'rejected':dict(rejected),
              'clothing19':'native inventory type 5 exists; room/avatar wire synchronization not implemented; not activated',
              'consumables':'not activated: purchase/consume persistence requires separate implementation',
              'diyStyles':sorted(diy),'charOriginalBytes':base,'charChanges':changed,'fxOriginalBytes':fxbase,'fxChanges':fxchanges,
              'geometrySkipped':geometrySkipped,'exeBeforeSha256':sha(exe),'exeAfterSha256':sha(candidate),'exePatches':patches,
              'accountsChanged':False,'liveVerified':False}
    (DEST/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'eligible':manifest['eligibleAfterDedup'],'batch':len(products),'sprites':len(changed),'shopSprites':len(fxchanges),'geometrySkipped':len(geometrySkipped),'diy':sorted(diy),'exePatches':len(patches)}))

if __name__=='__main__':main()
