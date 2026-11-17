"""Prepare CN057 login geometry and bounded face/emote/accessory catalog."""
from audit_shop324_compat import *
from legacy_sprite_codec import convert_sprite,frame_from_sprite
from rewrite_fx_index import write_index
import pefile

DEST=ROOT/'port-audit/login-face-emote/batch'
FIELDS=['itemId','category','name','skinId','targetCharId','param','desc','flags','status']
def sha(b):return hashlib.sha256(b).hexdigest()

def main():
    assert not DEST.exists(),'Keep prior audit snapshots intact'
    DEST.mkdir();national=national_items();current=current_items();existing={identity(i) for i in current}
    ci,models=model_names(ROOT/'client');si,_=load_index(CN057/'CharTE.idx');modern,_=load_index(CN324/'CharTE.idx')
    chars=json.loads((DEST.parent/'avatar-character-mapping.json').read_text());mapping={r['character']:r['model'] for r in chars}
    products=[];assets={};seen=set(existing)
    for char in [6,5]:
        candidates=sorted((i for i in national if i['category']==19 and i['skinId']==char and i['targetCharId']==1),key=lambda i:i['param'])
        added=0
        for i in candidates:
            model=f'AvatarDeco/A{mapping[char]:02d}_10_{i["param"]:04d}';icon='Icon/'+model.split('/')[1]+'icon'
            if model not in models or icon not in ci:continue
            keys=[n for n in ci if n==icon or n.startswith(model+'_')]
            if not keys:continue
            source=CN057 if all(n in si for n in keys) else CN324
            source_index=si if source==CN057 else modern
            if any(n not in source_index or ci[n]['flags'] or source_index[n]['flags'] for n in keys):continue
            for n in keys:assets[n]=(source,source_index[n])
            products.append({**i,'itemId':970_000_000+i['itemId'],'sourceItemId':i['itemId'],'source':str(source),'assets':keys,'model':model,'flags':0,'status':1});added+=1
            if added==8:break
        assert added==8,(char,added)
    # Existing basic-character emotion animations. Do not copy modern character
    # skeletons or change the default character actions.
    emo,_=load_index(ROOT/'client/EmoTE.idx');fx,_=load_index(ROOT/'client/fx.idx')
    for char in range(8):
        for emotion in [0,1]:
            i=next(i for i in national if i['category']==3 and i['skinId']==char and i['targetCharId']==emotion and i['param']==0)
            prefix=f'Character/C{mapping[char]:02d}-Emotion{chr(65+emotion)}000'
            keys=[n for n in emo if n.startswith(prefix)]
            assert len(keys)>=2 and i['name'] in fx,(i,prefix)
            for n in keys:convert_sprite(read_asset(ROOT/'client',emo[n],'EmoTE'))
            products.append({**i,'itemId':970_000_000+i['itemId'],'sourceItemId':i['itemId'],'assets':keys,'source':'existing basic-character EmoTE animations','flags':0,'status':1})
    # Add a small balanced selection of ordinary decorations.
    for part in [0,3,4]:
        count=0
        for i in national:
            if i['category']!=12 or i['name']!='Item/CharDeco' or i['skinId']!=-1 or i['targetCharId']!=part or identity(i) in seen:continue
            suffix=f'{chr(65+part)}{i["param"]:04d}';model='CharDeco/CharDeco_'+suffix;icon='Icon/CharDeco_'+suffix+'icon'
            if model not in models or icon not in ci:continue
            keys=[n for n in modern if n==icon or n.startswith(model) or n.startswith('CharDecoAdj/CharDecoAdj_'+suffix)]
            if not keys or any(n not in ci or ci[n]['flags'] or modern[n]['flags'] for n in keys):continue
            for n in keys:assets[n]=(CN324,modern[n])
            products.append({**i,'itemId':970_000_000+i['itemId'],'sourceItemId':i['itemId'],'source':str(CN324),'assets':keys,'flags':0,'status':1});seen.add(identity(i));count+=1
            if count==4:break
        assert count==4
    assert len(products)==44 and len({i['itemId'] for i in products})==44
    for name,p in [('local_extra_items',DATA/'local_extra_items.tsv'),('names',ROOT/'server/src/main/resources/localization/zh-CN-official-item-names.tsv')]:
        (DEST/(name+'.before.tsv')).write_bytes(p.read_bytes())
        extra=''.join(('\t'.join(str(i[k]) for k in FIELDS) if name=='local_extra_items' else f'{i["itemId"]}\t{i["desc"]}')+'\n' for i in products)
        (DEST/(name+'.candidate.tsv')).write_text(p.read_text(encoding='utf-8-sig').rstrip()+'\n'+extra,encoding='utf-8')
    packages={};asset_rows={}
    def prepare(package,entries,source_assets):
        _,key=load_index(ROOT/'client'/f'{package}.idx');base=(ROOT/'client'/f'{package}.idd').stat().st_size;append=bytearray();changes=[]
        for name,(folder,entry) in sorted(source_assets.items()):
            new,pages=convert_sprite(read_asset(folder,entry,package));before=entries[name]
            if new==read_asset(ROOT/'client',before,package):continue
            after={**before,'offset':base+len(append),'size':len(new),'key':entry['key']};entries[name]=after;append.extend(new)
            changes.append({'name':name,'before':before,'after':after,'sha256':sha(new),'convertedPages':len(pages),'source':str(folder)})
        (DEST/f'{package}.idx.before').write_bytes((ROOT/'client'/f'{package}.idx').read_bytes())
        (DEST/f'{package}.append.bin').write_bytes(append);write_index(DEST/f'{package}.idx.candidate',entries,key)
        packages[package]={'originalBytes':base,'appendBytes':len(append),'changes':changes}
    prepare('CharTE',ci,assets)
    # Alias old renderer names to the national login panels, keeping index order.
    fx,_=load_index(ROOT/'client/fx.idx');cnfx,_=load_index(CN057/'fx.idx');login_assets={}
    for old,new in [('LO_LogTable00','LO_LoginTable00'),('LO_LogTable01','LO_LoginTable01')]:
        login_assets['LoginStage/'+old]=(CN057,cnfx['LoginStage/'+new])
    prepare('fx',fx,login_assets)
    exe=(ROOT/'client/CA.exe').read_bytes();pe=pefile.PE(data=exe);candidate=bytearray(exe);patches=[]
    # Positions derived from each national panel's white input rectangles, with
    # the existing five/six-pixel inset. No login validation or auth code changes.
    edits=[(0x620f1f,'68bc000000','68ac000000'),(0x620ebb,'bd8e000000','bd5f000000'),
      (0x623e50,'b998010000','b995010000'),(0x623ea2,'8d97c0010000','8d97e1010000'),
      (0x623ebb,'8d8fd9010000','8d8ffa010000'),(0x623ed0,'c700c2000000','c700b2000000'),
      (0x623edf,'c70011020000','c70015020000'),(0x623af2,'b96b010000','b921010000'),
      (0x623b93,'b9c8010000','b9eb010000'),(0x623b69,'8d97c0010000','8d97e0010000'),
      (0x623b82,'8d8fd9010000','8d8ff9010000'),(0x623b9a,'8d97c0010000','8d97e0010000'),
      (0x623bb3,'8d8fd9010000','8d8ff9010000'),(0x623bc8,'c70095000000','c70065000000'),
      (0x623bd7,'c7003e020000','c70062020000')]
    for address,a,b in edits:
        before,after=bytes.fromhex(a),bytes.fromhex(b);o=pe.get_offset_from_rva(address-0x400000)
        assert exe[o:o+len(before)]==before,(hex(address),exe[o:o+len(before)].hex(),a)
        candidate[o:o+len(after)]=after;patches.append({'address':hex(address),'offset':o,'before':a,'after':b})
    (DEST/'CA.exe.before').write_bytes(exe);(DEST/'CA.exe.candidate').write_bytes(candidate)
    # Both single and dual login text boxes must fit their new source rectangles.
    rects=[(172,228,83,405,481),(172,228,108,405,506),(95,188,82,289,480),(95,188,107,289,505),(95,390,82,491,480),(95,390,107,491,505)]
    for ox,wx,wy,x,y in rects:assert ox+wx<=x and x+84<=ox+wx+91 and 393+wy<=y and y+12<=393+wy+19
    manifest={'products':products,'packages':packages,'exePatches':patches,'exeSha256':sha(candidate),'counts':dict(Counter(i['category'] for i in products)),'accountsChanged':False,'liveVerified':False}
    (DEST/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'products':manifest['counts'],'assets':{p:len(v['changes']) for p,v in packages.items()},'loginPatches':len(patches)}))

if __name__=='__main__':main()
