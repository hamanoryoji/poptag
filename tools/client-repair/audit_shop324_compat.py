"""Read-only join of national definitions, old definitions and registered models."""
from inspect_shop324 import *

def national_items(folder=CN324):
    b=(folder/'csysitem.ssd').read_bytes()[8:]
    r=Reader(zlib.decompress(b[10:]) if b[5] else b[6:])
    rows=[r.item(True) for _ in range(r.number('I'))]
    assert r.position==len(r.data)
    return rows

def current_items():
    b=(DATA/'item_def_base.bin').read_bytes();r=Reader(b,8)
    rows=[r.item() for _ in range(struct.unpack_from('<H',b,6)[0])]
    for l in (DATA/'local_extra_items.tsv').read_text(encoding='utf-8-sig').splitlines():
        f=l.split('\t');rows.append(dict(zip(['itemId','category','name','skinId','targetCharId','param','desc','flags','status'],[int(f[0]),int(f[1]),f[2],int(f[3]),int(f[4]),int(f[5]),f[6],int(f[7]),int(f[8])])))
    return rows

if __name__=='__main__':
    national=national_items();current=current_items();idx,models=model_names(ROOT/'client')
    existing={identity(i) for i in current}
    groups=Counter();samples={}
    for i in national:
        if identity(i) in existing:continue
        model=icon=None
        if i['category']==14:
            model=f'BombDeco/Bomb{i["param"]:03d}';icon=f'Icon/Bomb{i["param"]:03d}icon'
        elif i['category']==12 and i['name']=='Item/CharDeco' and i['skinId']==-1 and i['targetCharId'] in range(7):
            model=f'CharDeco/CharDeco_{chr(65+i["targetCharId"])}{i["param"]:04d}';icon='Icon/'+model.split('/')[1]+'icon'
        if model:
            ok=model in models and icon in idx
            groups[(i['category'],ok)]+=1
            if ok:samples.setdefault(str(i['category']),[]).append({'item':i,'model':model,'icon':icon})
    out=ROOT/'port-audit/shop-324';out.mkdir(exist_ok=True)
    (out/'compatible-definitions.json').write_text(json.dumps(samples,ensure_ascii=False,indent=2),encoding='utf-8')
    print('new by category, model/icon present:',dict(groups))
    print('consumables:',[(i['itemId'],i['name'],i['skinId'],i['param']) for i in national if i['category']==1][:25])
