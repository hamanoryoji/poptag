"""Bounded national emote import, retaining the legacy action/model registry."""
from audit_shop324_compat import *
from legacy_sprite_codec import convert_sprite

DEST = ROOT / 'port-audit/shop-emote-expansion-v1'
FIELDS = ['itemId','category','name','skinId','targetCharId','param','desc','flags','status']

def main():
    assert not DEST.exists(), 'Preserve the previous candidate/audit'
    DEST.mkdir()
    current, key = load_index(ROOT/'client/EmoTE.idx')
    national, _ = load_index(CN057/'EmoTE.idx')
    fx, _ = load_index(ROOT/'client/fx.idx')
    old = {identity(i) for i in current_items()}
    products, assets = [], {}
    for char, model in [(6,2),(5,6)]:
        for item in national_items():
            if not (item['category']==3 and item['skinId']==char and identity(item) not in old
                    and item['targetCharId'] in range(8) and item['param']<3):
                continue
            prefix=f'Character/C{model:02d}-Emotion{chr(65+item["targetCharId"])}{item["param"]:03d}'
            names=[n for n in current if n.startswith(prefix)]
            if len(names)<2 or item['name'] not in fx or any(n not in national for n in names):
                continue
            for name in names:
                converted, pages=convert_sprite(read_asset(CN057,national[name],'EmoTE'))
                assert convert_sprite(converted)[0]==converted
                assets[name]=converted
            products.append({**item,'itemId':970000000+item['itemId'],
                             'sourceItemId':item['itemId'],'assets':names,'flags':0,'status':1})
            old.add(identity(item))
    assert len(products)>=12 and len({p['itemId'] for p in products})==len(products)
    base=(ROOT/'client/EmoTE.idd').stat().st_size
    append=bytearray(); changes=[]; ordered=list(current)
    for name,raw in sorted(assets.items()):
        if read_asset(ROOT/'client',current[name],'EmoTE')==raw:continue
        entry={**current[name],'offset':base+len(append),'size':len(raw),'key':national[name]['key']}
        append.extend(raw);current[name]=entry
        changes.append({'name':name,'sha256':hashlib.sha256(raw).hexdigest()})
    assert list(current)==ordered
    (DEST/'EmoTE.idx.before').write_bytes((ROOT/'client/EmoTE.idx').read_bytes())
    write_index(DEST/'EmoTE.idx.candidate',current,key)
    (DEST/'EmoTE.append.bin').write_bytes(append)
    for kind,path in [('items',DATA/'local_extra_items.tsv'),('names',ROOT/'server/src/main/resources/localization/zh-CN-official-item-names.tsv')]:
        original=path.read_bytes();(DEST/f'{kind}.before.tsv').write_bytes(original)
        lines=['\t'.join(str(i[k]) for k in FIELDS) if kind=='items' else f'{i["itemId"]}\t{i["desc"]}' for i in products]
        assert all('\n' not in line for line in lines)
        (DEST/f'{kind}.candidate.tsv').write_text(original.decode('utf-8-sig').rstrip()+'\n'+'\n'.join(lines)+'\n',encoding='utf-8')
    report={'source':str(CN057),'definitionSource':str(CN324),'products':products,
            'originalBytes':base,'appendBytes':len(append),'changes':changes,'runtimeVerified':False}
    (DEST/'manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'products':len(products),'changedAnimations':len(changes),'appendBytes':len(append)}))

if __name__=='__main__':main()
