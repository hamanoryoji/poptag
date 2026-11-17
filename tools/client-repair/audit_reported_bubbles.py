from audit_shop324_compat import *
from decode_idd_preview import decode_page
from PIL import Image,ImageDraw
OUT2=ROOT/'port-audit/shop-render-fix'

def sprite(raw):
    nf,v,np,flags=struct.unpack_from('<4I',raw)
    assert v==2
    dims=[struct.unpack_from('<2I',raw,16+8*j) for j in range(np)]
    frames=[struct.unpack_from('<12i',raw,16+8*np+48*j) for j in range(nf)]
    pos=16+8*np+48*nf;pages=[];info=[]
    for h,w in dims:
        length=struct.unpack_from('<I',raw,pos)[0];pos+=4;b=raw[pos:pos+length];pos+=length
        info.append({'width':w,'height':h,'bytes':length,'prefix':b[:6].hex(),'rgb24':bool(length and b[0]&0xc0==0xc0)})
        try:pages.append(decode_page(b,w,h) if length else None)
        except Exception as e:
            info[-1]['decodeError']=str(e);pages.append(Image.new('RGBA',(w,h),(255,0,255,255)))
    assert pos==len(raw)
    return nf,flags,frames,pages,info

if __name__=='__main__':
    OUT2.mkdir(exist_ok=True);national=national_items();current=current_items()
    names=['crazy','国潮','六彩玲珑','四彩玲珑','绿意盎然','河清海晏','宝石糖果','爱心熊']
    items=[i for i in national if i['category']==14 and any(n.lower() in i['desc'].lower() for n in names)]
    index,_=load_index(ROOT/'client/CharTE.idx');rows=[];images=[]
    for i in items:
        for name in [f'Icon/Bomb{i["param"]:03d}icon',f'BombDeco/Bomb{i["param"]:03d}']:
            if name not in index:continue
            raw=read_asset(ROOT/'client',index[name],'CharTE')
            nf,flags,frames,pages,info=sprite(raw)
            f=frames[0];image=pages[f[3]].crop(f[4:8]);images.append((str(i['param'])+' '+name.split('/')[0],image))
            rows.append({'source':i,'resource':name,'frames':nf,'flags':flags,'pages':info,'sha256':hashlib.sha256(raw).hexdigest(),'frame0hash':hashlib.sha256(image.tobytes()).hexdigest()})
    (OUT2/'reported-bubbles.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
    im=Image.new('RGB',(8*110,((len(images)+7)//8)*110),'#cccccc');d=ImageDraw.Draw(im)
    for j,(label,image) in enumerate(images):
        image.thumbnail((90,80));x=j%8*110;y=j//8*110;im.paste(image,(x,y),image);d.text((x,y+82),label,fill='black')
    im.save(OUT2/'reported-bubbles.png')
    print(json.dumps([{'name':r['source']['desc'],'style':r['source']['param'],'resource':r['resource'],'flags':r['flags'],'pageFormats':[p['rgb24'] for p in r['pages']],'firstHash':r['frame0hash'][:10]} for r in rows],ensure_ascii=True))
