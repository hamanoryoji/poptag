"""Generate editable local prices from compatible resource identities.

Visual-complexity bands are project pricing tiers, not official rarity claims.
No client or account data is changed by this preparation script.
"""
from audit_shop324_compat import *

OUT = ROOT/'port-audit/local-economy'

def main():
    OUT.mkdir(exist_ok=True)
    idx,models = model_names(ROOT/'client')
    current = current_items(); national = national_items()
    rows=[]
    for item in current:
        cat=item['category'];frames=0;model='';name=item['desc']
        if cat==14:
            model=f'BombDeco/Bomb{item["param"]:03d}'
        elif cat==12 and item['name']=='Item/CharDeco' and item['targetCharId'] in range(7):
            model=f'CharDeco/CharDeco_{chr(65+item["targetCharId"])}{item["param"]:04d}'
        # Headers of actual installed sprites, not a guess based on numeric IDs.
        for key in [k for k in idx if model and (k==model or k.startswith(model+'_'))]:
            e=idx[key]
            if not e['flags']:
                raw=read_asset(ROOT/'client',e,'CharTE')
                if len(raw)>=16 and struct.unpack_from('<I',raw,4)[0]==2:
                    frames=max(frames,struct.unpack_from('<I',raw)[0])
        tier=0 if frames<8 else 1 if frames<16 else 2 if frames<32 else 3
        reason=f'installed animation frames={frames}'
        if cat==14:
            # Curated showcase pieces, with remaining tiers driven by animation.
            if any(w in name for w in ['星河无极','河清海晏','六彩','四彩','绿意盎然','宝石糖果','爱心熊']):
                tier=3;reason='curated elaborate showcase appearance'
            price=[180000,320000,580000,880000][tier]
        elif cat==12:price=[120000,220000,380000,680000][tier]
        elif cat==19:price=120000 if item['param']<8 else 240000
        elif cat==3:price=80000 if item['targetCharId']==0 else 120000
        elif cat in [1,15]:price=5000
        elif cat in [10,18,29]:price=[120000,220000,380000,680000][tier]
        else:price=120000
        rows.append({'itemId':item['itemId'],'price':price,'tier':['普通','精致','华丽','典藏'][tier],
            'name':name,'category':cat,'frames':frames,'reason':reason,'resource':model})
    pricefile=DATA/'local-shop-prices.tsv'
    pricefile.write_text('# itemId\tgold\tlocal tier\tname\n'+''.join(
        f'{r["itemId"]}\t{r["price"]}\t{r["tier"]}\t{r["name"]}\n' for r in rows),encoding='utf-8')
    (OUT/'price-audit.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
    existing={identity(i) for i in current};counts=Counter();samples={}
    for item in national:
        if identity(item) in existing:continue
        cat=item['category'];model=icon=None
        if cat==14:
            model=f'BombDeco/Bomb{item["param"]:03d}';icon=f'Icon/Bomb{item["param"]:03d}icon'
        elif cat==12 and item['name']=='Item/CharDeco' and item['skinId']==-1 and item['targetCharId'] in range(7):
            model=f'CharDeco/CharDeco_{chr(65+item["targetCharId"])}{item["param"]:04d}';icon='Icon/'+model.split('/')[1]+'icon'
        if model:
            compatible=model in models and icon in idx
            counts[f'{cat}:registered={compatible}']+=1
            if compatible:samples.setdefault(str(cat),[]).append({'item':item,'model':model,'icon':icon})
    (OUT/'remaining-port-candidates.json').write_text(json.dumps({'counts':dict(counts),'candidates':samples},ensure_ascii=False,indent=2),encoding='utf-8')
    private=Path(r'D:\GAME\泡泡堂各个版本\私服\PopTag')
    pi,_=load_index(private/'fx.idx')
    (OUT/'private-reference-audit.json').write_text(json.dumps({
        'source':str(private),'inspected':['file inventory','xml/Init/Init.xml','fx.idx resource names'],
        'rewardRatesFound':False,'levelExperienceTableFound':False,
        'relatedResourceNames':[n for n in pi if re.search(r'exp|level|reward|lucci',n,re.I)],
        'limitation':'Icons and effects do not establish reward rates. No online connection or executable launch.'
    },ensure_ascii=False,indent=2),encoding='utf-8')
    print('Price records:',len(rows),'remaining resource candidates:',dict(counts))

if __name__=='__main__':main()
