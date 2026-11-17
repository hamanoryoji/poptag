"""Read-only checks of ported archives against CN057 and server routing data."""
from pathlib import Path
import collections
import hashlib
import json
import re
import sys
import struct
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parent.parent / '泡泡堂各个版本' / '国服 057版'
STABLE = SOURCE.parent / '美服 10年版'
sys.path.insert(0, str(ROOT.parent / 'PopTag-shop'))
from idd_assets import load_index, read_asset
from fix_map_object_indices import objects


def main():
    data = ROOT / 'server/src/main/resources/data'
    output = ROOT / 'port-audit'
    source_index, _ = load_index(SOURCE / 'Fx.idx')
    national = SOURCE.parent / '国服 324 版'
    national_index, _ = load_index(national / 'FxMap.idx')
    candidate_index, _ = load_index(ROOT / 'client/fx.idx')
    source_models = list(objects(SOURCE, source_index))
    candidate_models = list(objects(ROOT / 'client', candidate_index))
    stable_index, _ = load_index(STABLE / 'client/fx.idx')
    stable_models = list(objects(STABLE / 'client', stable_index))
    assert candidate_models[:len(stable_models)] == stable_models, 'Stable object indexes changed'
    native_names = sorted((n[4:] for n in candidate_index if n.startswith('Map/')), key=str.casefold)
    native = {name: i for i, name in enumerate(native_names)}
    for file in [data / 'ported-native-map-indices.tsv', ROOT / 'server/src/test/resources/native-map-index.tsv']:
        recorded = {name: int(index) for index, name in (line.split('\t') for line in file.read_text().splitlines())}
        assert recorded == native, f'Native map index drift: {file}'

    scope = {}
    exec((STABLE / 'server/tools/decrypt_attr.py').read_text(encoding='utf-8-sig').split('with open(')[0], scope)
    decrypt = scope['decrypt_idd_file']
    stages = collections.defaultdict(list)
    for line in (data / 'local-monster-stages.tsv').read_text(encoding='utf-8-sig').splitlines():
        if line and not line.startswith('#'):
            fields = line.split('\t')
            stages[fields[0]].append(fields)
    additions = json.loads((data / 'local-map-expansion.json').read_text(encoding='utf-8'))['records']
    map_rows = {r['name']: r for r in additions}
    result = {'runtime_verified': False, 'families': {}, 'map_checks': [], 'shop_resource_differences': {}}
    types = collections.Counter()
    for family, count, theme in [('MonSeal', 27, 248), ('MonTurtle', 24, 251), ('MonRobot', 18, 253)]:
        for number in range(1, count + 1):
            name = f'{family}{number:02d}'
            key = 'Map/' + name
            a = read_asset(SOURCE, source_index[key], 'Fx')
            b = read_asset(ROOT / 'client', candidate_index[key])
            # Raw board indexes are version-specific. Compare resolved object
            # identities instead of requiring the incompatible source bytes.
            row = map_rows[name]
            assert row['theme_id'] == theme and row['sort_order'] == native[name], name
            assert row['tail1'] == int(number % 3 == 1), name
            plain = decrypt(a, source_index[key]['flags'])
            converted = decrypt(b, candidate_index[key]['flags'])
            assert converted[:0x70] == plain[:0x70], name
            for offset in range(0x70, 0x688, 4):
                original_id = struct.unpack_from('<I', plain, offset)[0]
                ported_id = struct.unpack_from('<I', converted, offset)[0]
                if original_id == 0xffffffff:
                    assert ported_id == original_id, (name, offset)
                else:
                    assert source_models[original_id] == candidate_models[ported_id], (name, offset)
            script = converted[converted.index(b'<mapscript'):].split(b'</mapscript>')[0] + b'</mapscript>'
            national_plain = decrypt(read_asset(national, national_index[key], 'FxMap'), national_index[key]['flags'])
            national_script = national_plain[national_plain.index(b'<mapscript'):].split(b'</mapscript>')[0] + b'</mapscript>'
            assert script == national_script, name
            tree = ET.fromstring(re.sub(rb'^\s*#.*$', b'', script, flags=re.M).decode('cp949', errors='replace'))
            actors = sorted(tree.findall('.//ScriptObject/InitInfo'), key=lambda node:
                {k.lower(): v for k, v in node.attrib.items()}.get('fromstart', 'true').lower() not in ('true', '1'))
            assert len(actors) == len(stages[name]), name
            next_name = f'{family}{number + 1:02d}' if number % 3 else '-'
            for index, (actor, fields) in enumerate(zip(actors, stages[name])):
                attrs = {k.lower(): v for k, v in actor.attrib.items()}
                hp = re.search(r'max\s*=\s*(\d+)', attrs.get('life', ''), re.I)
                anger = re.search(r'anger\s*=\s*(\d+)', attrs.get('life', ''), re.I)
                expected = [name, next_name, str(index), attrs['type'].lower(),
                            str(int(hp[1]) if hp else 1),
                            str(int(attrs.get('fromstart', 'true').lower() in ('true', '1'))),
                            str(int(attrs.get('boss', 'false').lower() in ('true', '1'))),
                            str(int(attrs.get('class', index))), str(int(anger[1]) if anger else 0)]
                actual = fields[:9]; actual[3] = actual[3].lower()
                assert actual == expected, f'Actor data differs: {name} object {index}'
                expected_clear = attrs.get('noinfluencegameend', '0').lower() not in ('1', 'true')
                assert (fields[9] != '0') == expected_clear, f'Clear flag differs: {name} object {index}'
                types[attrs['type'].lower()] += 1
            result['map_checks'].append({'name': name, 'native_index': native[name],
                                         'actors': len(actors), 'source_sha256': hashlib.sha256(a).hexdigest(),
                                         'candidate_sha256': hashlib.sha256(b).hexdigest(), 'board_object_names_match': True})
        result['families'][family] = {'groups': count // 3, 'internal_stages': count, 'theme': theme}

    for package in ['Fx', 'CharTE', 'EmoTE']:
        cn, _ = load_index(SOURCE / (package + '.idx'))
        en, _ = load_index(ROOT / 'client' / (package + '.idx'))
        missing = sorted(set(cn) - set(en))
        shared = set(cn) & set(en)
        identical = sum(read_asset(SOURCE, cn[n], package) == read_asset(ROOT / 'client', en[n], package) for n in shared)
        result['shop_resource_differences'][package] = {
            'source_only_names': missing, 'shared_names': len(shared), 'shared_identical_bytes': identical,
            'note': 'Archive entries are not products. Shared names do not imply identical visuals or compatible definitions.'}
    result['actor_types'] = dict(types)
    result['checked_stages'] = len(result['map_checks'])
    result['checked_actors'] = sum(types.values())
    (output / 'candidate-validation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: result[k] for k in ['runtime_verified', 'families', 'checked_stages', 'checked_actors', 'actor_types']}, ensure_ascii=True))


if __name__ == '__main__':
    main()
