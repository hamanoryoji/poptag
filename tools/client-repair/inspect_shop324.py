from prepare_cn057_shop_port import *
from fix_map_object_indices import decrypt
from collections import Counter

def model_names(folder):
    index,_=load_index(folder/'CharTE.idx')
    entry=index[next(n for n in index if n.startswith('ObjectTable/'))]
    raw=decrypt(read_asset(folder,entry,'CharTE'),entry['flags'])
    r=Reader(raw); count=r.number('I'); names=set()
    for _ in range(count):
        length=r.number('I'); name=raw[r.position:r.position+length].decode('cp949');r.position+=length
        r.number('I');names.add(name)
    return index,names

if __name__=='__main__':
    b=(CN324/'csysitem.ssd').read_bytes()[8:]
    r=Reader(zlib.decompress(b[10:]) if b[5] else b[6:])
    national=[r.item(True) for _ in range(r.number('I'))]
    print(type(national).__name__)
    for cat in [1,12,14,19]:
        rows=[i for i in national if i['category']==cat]
        print('category',cat,'count',len(rows),'names',Counter(i['name'].split('/')[0] for i in rows))
        print(json.dumps(rows[:4],ensure_ascii=False))
    index,models=model_names(ROOT/'client')
    print('Avatar sample',sorted(n for n in models if n.startswith('AvatarDeco/'))[:8])
    print('Char sample',sorted(n for n in models if n.startswith('CharDeco/'))[:12])
    print('Bomb sample',sorted(n for n in models if n.startswith('BombDeco/'))[:5])
    print('fx resources item', [n for n in load_index(ROOT/'client/fx.idx')[0] if n.startswith('Item/')][:30])
