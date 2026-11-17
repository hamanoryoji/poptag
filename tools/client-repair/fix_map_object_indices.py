"""Convert CN057 board references while preserving stable object-table indexes."""
from pathlib import Path
import hashlib
import json
import struct
import sys

ROOT = Path(__file__).resolve().parent
STABLE = ROOT.parent.parent / '泡泡堂各个版本' / '美服 10年版' / 'client'
SOURCE = STABLE.parent.parent / '国服 057版'
sys.path.insert(0, str(ROOT.parent / 'PopTag-shop'))
from idd_assets import load_index, read_asset
from rewrite_fx_index import write_index

scope = {}
exec((STABLE.parent / 'server/tools/decrypt_attr.py').read_text(encoding='utf-8-sig').split('with open(')[0], scope)
decrypt = scope['decrypt_idd_file']


def objects(folder, index):
    entry = index['ObjectTable/ObjectTable']
    raw = decrypt(read_asset(folder, entry), entry['flags'])
    count = struct.unpack_from('<I', raw)[0]
    position = 4
    rows = []
    for _ in range(count):
        length = struct.unpack_from('<I', raw, position)[0]; position += 4
        name = raw[position:position + length].decode('cp949'); position += length
        offset = struct.unpack_from('<I', raw, position)[0]; position += 4
        rows.append((name, offset))
    offsets = sorted({o for _, o in rows if o != 0xffffffff})
    ends = dict(zip(offsets, offsets[1:] + [len(raw)]))
    return {name: None if offset == 0xffffffff else raw[offset:ends[offset]] for name, offset in rows}


def rebuild(ordered):
    # The table's insertion order is a numeric ABI used by every board cell.
    header_size = 4 + sum(8 + len(name.encode('cp949')) for name in ordered)
    header = bytearray(struct.pack('<I', len(ordered)))
    body = bytearray()
    for name, payload in ordered.items():
        encoded = name.encode('cp949')
        header += struct.pack('<I', len(encoded)) + encoded
        header += struct.pack('<I', 0xffffffff if payload is None else header_size + len(body))
        if payload is not None:
            body += payload
    return bytes(header + body)


def encrypt(plain, key):
    assert len(plain) % 4 == 0
    out = bytearray(); previous = 0
    for index in range(len(plain) // 4):
        value = struct.unpack_from('<I', plain, index * 4)[0]
        factor = 0 if index == 0 else ((index + 0x3c6) * (index + 0x7e4) * index * index) & 0xffffffff
        out += struct.pack('<I', value ^ (key if index == 0 else previous) ^ factor ^ 0x9c6c95ce)
        previous = value
    return bytes(out)


def crc(plain, seed):
    for value in plain:
        seed ^= value << 24
        for _ in range(8):
            seed = ((seed << 1) ^ (0x04c11db7 if seed & 0x80000000 else 0)) & 0xffffffff
    return seed


def main():
    candidate = ROOT / 'client'
    current, key = load_index(candidate / 'fx.idx')
    source_index, _ = load_index(SOURCE / 'Fx.idx')
    stable_index, _ = load_index(STABLE / 'fx.idx')
    old_objects = objects(STABLE, stable_index)
    candidate_objects = objects(candidate, current)
    source_objects = objects(SOURCE, source_index)
    restored = dict(old_objects)
    restored.update((n, p) for n, p in candidate_objects.items() if n not in restored)
    assert list(restored)[:len(old_objects)] == list(old_objects)
    destination = {name: i for i, name in enumerate(restored)}
    source_names = list(source_objects)
    payloads = {}
    plain_table = rebuild(restored)
    plain_table += bytes((-len(plain_table)) % 4)
    payloads['ObjectTable/ObjectTable'] = plain_table
    report = {'stable_object_count': len(old_objects), 'appended_objects': len(restored) - len(old_objects),
              'stable_object_indexes_preserved': True, 'maps': [], 'runtime_verified': False}
    for family, count in [('MonSeal', 27), ('MonTurtle', 24), ('MonRobot', 18)]:
        for number in range(1, count + 1):
            name = f'Map/{family}{number:02d}'
            entry = source_index[name]
            original = decrypt(read_asset(SOURCE, entry, 'Fx'), entry['flags'])
            converted = bytearray(original)
            width, height = struct.unpack_from('<II', original, 12)
            assert (width, height) == (15, 13), name
            changed = 0
            for offset in range(0x70, 0x70 + width * height * 8, 4):
                model = struct.unpack_from('<I', original, offset)[0]
                if model == 0xffffffff:
                    continue
                assert model < len(source_names), (name, offset, model)
                target = destination[source_names[model]]
                struct.pack_into('<I', converted, offset, target)
                assert list(restored)[target] == source_names[model]
                changed += int(target != model)
            assert converted[:0x70] == original[:0x70]
            assert converted[0x688:] == original[0x688:], name
            converted += bytes((-len(converted)) % 4)
            payloads[name] = bytes(converted)
            report['maps'].append({'name': name, 'converted_cells': changed,
                                   'source_sha256': hashlib.sha256(original).hexdigest(),
                                   'converted_sha256': hashlib.sha256(converted).hexdigest()})
    backup = ROOT / 'port-audit/fx.before-object-index-fix.idx'
    if not backup.exists():
        backup.write_bytes((candidate / 'fx.idx').read_bytes())
    with (candidate / 'fx.idd').open('ab') as archive:
        for name, plain in payloads.items():
            entry = dict(current[name])
            encoded = encrypt(plain, entry['flags'])
            assert decrypt(encoded, entry['flags']) == plain
            entry.update(offset=archive.tell(), size=len(encoded), key=crc(plain, entry['flags']))
            archive.write(encoded)
            current[name] = entry
    write_index(candidate / 'fx.idx', current, key)
    check, _ = load_index(candidate / 'fx.idx')
    assert list(objects(candidate, check)) == list(restored)
    for name, entry in stable_index.items():
        if name.startswith('Map/'):
            assert read_asset(candidate, check[name]) == read_asset(STABLE, entry), name
    for name, plain in payloads.items():
        assert decrypt(read_asset(candidate, check[name]), check[name]['flags']) == plain
    report['checked_original_maps'] = sum(n.startswith('Map/') for n in stable_index)
    (ROOT / 'port-audit/map-object-index-conversion.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'converted_maps': len(report['maps']), 'converted_cells': sum(x['converted_cells'] for x in report['maps']),
                      'preserved_original_maps': report['checked_original_maps'], 'stable_object_indexes_preserved': True}))


if __name__ == '__main__':
    main()
