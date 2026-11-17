"""Prepare only reported English UI artwork; preserve existing frame geometry."""
from prepare_cn057_shop_port import ROOT,CN057
from idd_assets import load_index,read_asset
from legacy_sprite_codec import modern_page,encode_legacy,frame_from_sprite
from PIL import Image,ImageDraw,ImageFont
import struct,json,hashlib

OUT=ROOT/'port-audit/remaining-ui/batch-v2'
FULL=['Lobby/Dlg_Creat','Lobby/Dlg_LB-NMCreate','NM/Dlg_NM-ListGlobal',
      'NM/Dlg_NM-ChatSearch','NM/Dlg_NM-GameSearch',
      'SelectServerStage/CH_Tab_Free_Ba','SelectServerStage/CH_Tab_Novice_Ba',
      'Tutorial/CH_RecommendToolTip','MyInfo/MoneyType_Lucci']
PARTIAL={'MyInfo/MP_Stage':[(14,20,210,68)],
         'MyInfo/MP_BackInfoRecord':[(0,0,326,18),(0,179,326,193)],
         'Lobby/Gametype_bt01':[(8,7,113,37)],
         'Lobby/Gametype_bt02':[(5,2,103,29)]}
sha=lambda b:hashlib.sha256(b).hexdigest()

def patch_sprite(old,source,regions=None,name=''):
    nf,ver,npages,flags=struct.unpack_from('<4I',old)
    assert ver==2 and struct.unpack_from('<I',source)[0]==nf
    dims=[struct.unpack_from('<2I',old,16+8*i) for i in range(npages)]
    end=16+8*npages+48*nf;pos=end;pages=[]
    for h,w in dims:
        n=struct.unpack_from('<I',old,pos)[0];pos+=4
        pages.append(modern_page(old[pos:pos+n],w,h));pos+=n
    assert pos==len(old)
    for frame in range(nf):
        f=struct.unpack_from('<12i',old,16+8*npages+48*frame)
        page=pages[f[3]];box=f[4:8];im=frame_from_sprite(source,frame)
        original=page.crop(box)
        if regions is None:
            replacement=Image.new('RGBA',original.size)
            if im.width>replacement.width or im.height>replacement.height:im.thumbnail(replacement.size,Image.Resampling.LANCZOS)
            replacement.paste(im,((replacement.width-im.width)//2,(replacement.height-im.height)//2))
        else:
            replacement=original.copy()
            for region in regions:
                assert region[2]<=min(original.width,im.width) and region[3]<=min(original.height,im.height)
                replacement.paste(im.crop(region),region[:2])
        if name=='MyInfo/MP_Stage':
            # National money-panel geometry is lower than this client's already
            # aligned profile panel. Redraw only its baked currency caption.
            draw=ImageDraw.Draw(replacement)
            draw.rectangle((31,266,90,281),fill=original.getpixel((100,274)))
            draw.text((34,267),'金币',font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',12),fill=(0,70,120,255))
        page.paste(replacement,box[:2])
    result=bytearray(old[:end])
    for page in pages:
        encoded=encode_legacy(page);result+=struct.pack('<I',len(encoded))+encoded
    assert result[:end]==old[:end], 'Do not move UI controls or alter animation/frame metadata'
    for frame in range(nf):assert frame_from_sprite(result,frame).size==frame_from_sprite(old,frame).size
    return bytes(result)

def write_preserving_index(path,entries,key):
    plain=bytearray(struct.pack('<3I',0,15,len(entries)))
    for e in entries.values():
        name=bytes.fromhex(e['rawName']);plain+=struct.pack('<I',len(name))+name+struct.pack('<4I',e['offset'],e['size'],e['flags'],e['key'])
    encoded=bytearray();previous=key
    for at in range(0,len(plain)//4*4,4):
        value=struct.unpack_from('<I',plain,at)[0];encoded+=struct.pack('<I',value^previous);previous=value
    for j,b in enumerate(plain[len(encoded):]):encoded.append(b^((previous>>(8*j))&255))
    path.write_bytes(encoded);assert load_index(path)==(entries,key)

def main():
    assert not OUT.exists(),'Preserve earlier rollback snapshots'
    OUT.mkdir(parents=True);client=ROOT/'client'
    index,key=load_index(client/'fx.idx');national,_=load_index(CN057/'fx.idx')
    before=dict(index);size=(client/'fx.idd').stat().st_size
    (OUT/'fx.idx.before').write_bytes((client/'fx.idx').read_bytes())
    append=bytearray();changes=[];preview=[]
    for name in FULL+list(PARTIAL):
        old=read_asset(client,index[name]);source=read_asset(CN057,national[name])
        assert index[name]['flags']==national[name]['flags']==0
        result=patch_sprite(old,source,PARTIAL.get(name),name)
        if result==old:continue
        entry=dict(index[name]);index[name]={**entry,'offset':size+len(append),'size':len(result)}
        append+=result
        changes.append({'name':name,'before':entry,'after':index[name],'sha256':sha(result),'source':str(CN057),'regions':PARTIAL.get(name)})
        preview.append((name,frame_from_sprite(result)))
    (OUT/'fx.append.bin').write_bytes(append);write_preserving_index(OUT/'fx.idx.candidate',index,key)
    assert [n for n in index if n.startswith('Map/')]==[n for n in before if n.startswith('Map/')]
    assert all(index[n]==e for n,e in before.items() if n not in {r['name'] for r in changes})
    manifest={'originalIddSize':size,'originalIdxSha256':sha((client/'fx.idx').read_bytes()),'appendSize':len(append),'changes':changes,'frameGeometryPreserved':True,'mapOrderPreserved':True}
    (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    canvas=Image.new('RGB',(1400,350*((len(preview)+3)//4)),(50,65,80));draw=ImageDraw.Draw(canvas);font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',12)
    for j,(name,im) in enumerate(preview):
        x=j%4*350;y=j//4*350;draw.text((x+5,y+3),name,font=font,fill='white');im.thumbnail((340,320));canvas.paste(im,(x+5,y+24),im)
    canvas.save(OUT/'preview.png')
    print(json.dumps({'resources':len(changes),'appendBytes':len(append),'preview':str(OUT/'preview.png')}))
if __name__=='__main__':main()
