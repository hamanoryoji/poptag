"""Prepare compatible CN057 accessories and CN324 official item names.

No account files are read or changed. Keep all old ItemDef ids so already-owned
items remain resolvable. Import only supported cosmetic definitions and source
sprites whose models are not referenced by the current item definitions.
"""
from pathlib import Path
from collections import Counter
import hashlib
import json
import re
import struct
import zlib
from fix_map_object_indices import ROOT
from idd_assets import load_index, read_asset
from rewrite_fx_index import write_index

OUT = ROOT / 'port-audit/shop-port'
DATA = ROOT / 'server/src/main/resources/data'
CN057 = ROOT.parent.parent / '泡泡堂各个版本/国服 057版'
CN324 = CN057.parent / '国服 324 版'
DIY_STYLES = {89, 90, 91, 92, 93, 94, 95, 96, 97, 99, 100, 107, 108, 109, 110}


class Reader:
    def __init__(self, data, position=0):
        self.data, self.position = data, position
    def number(self, fmt):
        value = struct.unpack_from('<' + fmt, self.data, self.position)[0]
        self.position += struct.calcsize('<' + fmt)
        return value
    def string(self):
        size = self.number('I')
        assert 0 <= size <= 100000 and self.position + size <= len(self.data)
        value = self.data[self.position:self.position + size].decode('gbk')
        self.position += size
        return value
    def item(self, modern=False):
        item = {'itemId': self.number('I'), 'category': self.number('B'), 'name': self.string(),
                'skinId': self.number('i'), 'targetCharId': self.number('i'),
                'param': self.number('i'), 'desc': self.string()}
        if modern:
            # CN324 has byte flags/status plus a two-byte extension. Do not
            # forward its four-byte tail to the old three-byte EN item decoder.
            item['flags'], item['status'] = self.number('B'), self.number('B')
            item['extension'] = self.number('H')
        else:
            item['flags'], item['status'] = self.number('H'), self.number('B')
        return item


def identity(item):
    if item['category'] == 14 and (item['name'] == 'Item/Bomb' or re.fullmatch(r'BombDeco/Bomb\d+', item['name'])):
        return ('bubble', item['param'])
    return tuple(item[k] for k in ['category', 'name', 'skinId', 'targetCharId', 'param'])


def main():
    OUT.mkdir(exist_ok=True)
    base = (DATA / 'item_def_base.bin').read_bytes()
    reader = Reader(base, 8)
    current = [reader.item() for _ in range(struct.unpack_from('<H', base, 6)[0])]
    extras_path = DATA / 'local_extra_items.tsv'
    extras = extras_path.read_text(encoding='utf-8-sig').splitlines()
    for line in extras:
        f = line.split('\t')
        current.append(dict(zip(['itemId', 'category', 'name', 'skinId', 'targetCharId', 'param', 'desc', 'flags', 'status'],
                                [int(f[0]), int(f[1]), f[2], int(f[3]), int(f[4]), int(f[5]), f[6], int(f[7]), int(f[8])])))
    cached = (CN324 / 'csysitem.ssd').read_bytes()
    block = cached[8:]
    plain = zlib.decompress(block[10:]) if block[5] else block[6:]
    reader = Reader(plain)
    count = reader.number('I')
    national = [reader.item(modern=True) for _ in range(count)]
    assert reader.position == len(plain), 'Incomplete national item decoding'
    src_index, _ = load_index(CN057 / 'CharTE.idx')
    cur_index, key = load_index(ROOT / 'client/CharTE.idx')
    existing = {identity(i) for i in current}
    active_models = {(i['targetCharId'], i['param']) for i in current
                     if i['category'] == 12 and i['name'] == 'Item/CharDeco'}
    used_ids = {i['itemId'] for i in current}
    products, assets = [], set()
    for item in national:
        if (item['category'] != 12 or item['name'] != 'Item/CharDeco'
                or item['skinId'] != -1 or not 0 <= item['targetCharId'] <= 6):
            continue
        if identity(item) in existing or (item['targetCharId'], item['param']) in active_models:
            continue
        suffix = f'{chr(65 + item["targetCharId"])}{item["param"]:04d}'
        prefixes = ['CharDeco/CharDeco_' + suffix, 'CharDecoAdj/CharDecoAdj_' + suffix,
                    'Icon/CharDeco_' + suffix + 'icon']
        models = [n for n in src_index if any(n.startswith(p) for p in prefixes)]
        if not models or not any(n.startswith(prefixes[0]) for n in models) or prefixes[2] not in src_index:
            continue
        assert all(n in cur_index for n in models), 'Do not silently add new object-table types'
        new_id = 940_000_000 + item['itemId']
        assert new_id not in used_ids
        imported = {**item, 'sourceItemId': item['itemId'], 'itemId': new_id, 'flags': 0, 'status': 1,
                    'assets': models}
        products.append(imported); assets.update(models); used_ids.add(new_id)
        existing.add(identity(item)); active_models.add((item['targetCharId'], item['param']))
    assert products, 'No compatible new products; do not claim an import'
    additions = ['\t'.join(str(i[k]) for k in ['itemId', 'category', 'name', 'skinId', 'targetCharId', 'param', 'desc', 'flags', 'status'])
                 for i in products]
    assert all('\n' not in i['desc'] and '\t' not in i['desc'] for i in products)
    (OUT / 'local_extra_items.before.tsv').write_bytes(extras_path.read_bytes())
    (OUT / 'local_extra_items.candidate.tsv').write_text('\n'.join(extras + additions) + '\n', encoding='utf-8')
    official = {identity(i): i for i in national if i['category'] in (10,12,14,18,29) and i['desc']}
    mappings = []
    for item in current + products:
        source = official.get(identity(item))
        if source and any('\u3400' <= c <= '\u9fff' for c in source['desc']):
            mappings.append({'itemId': item['itemId'], 'sourceItemId': source['itemId'], 'name': source['desc']})
    (OUT / 'zh-CN-official-item-names.tsv').write_text(''.join(f'{x["itemId"]}\t{x["name"]}\n' for x in mappings), encoding='utf-8')
    append = bytearray()
    original_size = (ROOT / 'client/CharTE.idd').stat().st_size
    asset_rows = []
    for name in sorted(assets):
        source = src_index[name]; before = dict(cur_index[name])
        assert source['flags'] == 0 and before['flags'] == 0
        content = read_asset(CN057, source, 'CharTE')
        assert len(content) >= 16 and struct.unpack_from('<I', content, 4)[0] == 2, name
        prior = read_asset(ROOT / 'client', before, 'CharTE')
        if content == prior:
            continue
        after = {**before, 'offset': original_size + len(append), 'size': len(content), 'key': source['key']}
        cur_index[name] = after; append.extend(content)
        asset_rows.append({'name': name, 'before': before, 'after': after,
                           'sourceSha256': hashlib.sha256(content).hexdigest()})
    (OUT / 'CharTE.idx.before').write_bytes((ROOT / 'client/CharTE.idx').read_bytes())
    (OUT / 'CharTE.append.bin').write_bytes(append)
    write_index(OUT / 'CharTE.idx.candidate', cur_index, key)
    manifest = {'resourceSource': str(CN057 / 'CharTE.idd'), 'nameSource': str(CN324 / 'csysitem.ssd'),
                'sourceItemCount': count, 'sourceItemCacheSha256': hashlib.sha256(cached).hexdigest(),
                'newProducts': products, 'officialNameMappings': mappings,
                'diyBubbleStylesToHide': sorted(DIY_STYLES),
                'originalCharTEBytes': original_size, 'appendBytes': len(append), 'assetChanges': asset_rows,
                'oldDefinitionIdsPreserved': True, 'accountFilesChanged': False, 'runtimeVerified': False}
    (OUT / 'import-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'newProducts': len(products), 'officialNames': len(mappings),
                      'changedSprites': len(asset_rows), 'appendBytes': len(append), 'DIYHidden': len(DIY_STYLES)}))


if __name__ == '__main__':
    main()
