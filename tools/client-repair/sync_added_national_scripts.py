"""Copy current national combat XML, retaining converted board geometry."""
from pathlib import Path
import hashlib
import json
import re
import struct
import xml.etree.ElementTree as ET
from fix_map_object_indices import ROOT, decrypt, encrypt, crc
from idd_assets import load_index, read_asset
from rewrite_fx_index import write_index

NATIONAL = ROOT.parent.parent / '泡泡堂各个版本' / '国服 324 版'


def xml_parts(raw):
    start = raw.index(b'<mapscript')
    end = raw.index(b'</mapscript>', start) + len(b'</mapscript>')
    return start, end, raw[start:end]


def parse(xml):
    return ET.fromstring(re.sub(rb'^\s*#.*$', b'', xml, flags=re.M).decode('cp949', errors='replace'))


def main():
    candidate = ROOT / 'client'
    index, key = load_index(candidate / 'fx.idx')
    national_index, _ = load_index(NATIONAL / 'FxMap.idx')
    rows = []; metadata = []
    type_names = {'basicmon': 'basicMon', 'pudadakmon': 'pudadakMon', 'starfishmon': 'starFishMon',
                  'bossmon2': 'bossMon2', 'bossturtle': 'bossTurtle', 'bossrobot': 'bossRobot',
                  'smallrobot': 'smallRobot', 'bombmissile': 'bombMissile', 'bombmissile2': 'bombMissile2'}
    with (candidate / 'fx.idd').open('ab') as archive:
        for family, count in [('MonSeal', 27), ('MonTurtle', 24), ('MonRobot', 18)]:
            for number in range(1, count + 1):
                name = f'{family}{number:02d}'; asset = 'Map/' + name
                entry = dict(index[asset]); source_entry = national_index[asset]
                old = decrypt(read_asset(candidate, entry), entry['flags'])
                national = decrypt(read_asset(NATIONAL, source_entry, 'FxMap'), source_entry['flags'])
                start, end, original_xml = xml_parts(old)
                _, _, national_xml = xml_parts(national)
                # The binary item-probability list is followed by four dwords
                # and a length-prefixed script text. Retain its outer whitespace.
                position = 0x688
                item_count = struct.unpack_from('<I', old, position)[0]; position += 4
                for _ in range(item_count):
                    length = struct.unpack_from('<I', old, position)[0]
                    position += 4 + length + 4
                # Some maps have an additional placed-item table here. Locate
                # the unique length-prefixed text ending at the archive padding.
                candidates = []
                for offset in range(position, start - 3):
                    size = struct.unpack_from('<I', old, offset)[0]
                    remaining = len(old) - offset - 4 - size
                    if 0 <= remaining <= 3 and offset + 4 <= start < end <= offset + 4 + size:
                        if not any(old[offset + 4 + size:]): candidates.append((offset, size))
                assert len(candidates) == 1, (name, candidates)
                length_offset, length = candidates[0]
                text_start = length_offset + 4; text_end = text_start + length
                assert text_start <= start < end <= text_end <= len(old), name
                text = old[text_start:start] + national_xml + old[end:text_end]
                converted = old[:length_offset] + struct.pack('<I', len(text)) + text + old[text_end:]
                converted += bytes((-len(converted)) % 4)
                assert converted[:0x688] == old[:0x688], name
                encoded = encrypt(converted, entry['flags'])
                entry.update(offset=archive.tell(), size=len(encoded), key=crc(converted, entry['flags']))
                archive.write(encoded); index[asset] = entry
                tree = parse(national_xml)
                terminal_starfish = set()
                for obj in tree.findall('.//ScriptObject'):
                    info = obj.find('InitInfo')
                    if info is None:
                        continue
                    a = {k.lower(): v for k, v in info.attrib.items()}
                    if (a.get('type', '').lower() == 'starfishmon'
                            and a.get('fromstart', 'true').lower() not in ('1', 'true')
                            and not any(t.get('Type', t.get('type', '')).lower() == 'selfdie'
                                        for t in obj.findall('.//Trigger'))):
                        terminal_starfish.add(int(a['class']))
                next_name = f'{family}{number + 1:02d}' if number % 3 else '-'
                actors = sorted(tree.findall('.//ScriptObject/InitInfo'), key=lambda node:
                    {k.lower(): v for k, v in node.attrib.items()}.get('fromstart', 'true').lower() not in ('1', 'true'))
                for object_id, node in enumerate(actors):
                    attrs = {k.lower(): v for k, v in node.attrib.items()}
                    hp = re.search(r'max\s*=\s*(\d+)', attrs.get('life', ''), re.I)
                    anger = re.search(r'anger\s*=\s*(\d+)', attrs.get('life', ''), re.I)
                    metadata.append('\t'.join(map(str, [name, next_name, object_id, type_names[attrs['type'].lower()],
                        int(hp[1]) if hp else 1, int(attrs.get('fromstart', 'true').lower() in ('1', 'true')),
                        int(attrs.get('boss', 'false').lower() in ('1', 'true')), int(attrs.get('class', object_id)),
                        int(anger[1]) if anger else 0,
                        int(attrs.get("noinfluencegameend", "0").lower() not in ("1", "true")),
                        int(int(attrs.get('class', object_id)) in terminal_starfish)])))
                rows.append({'name': name, 'xml_bytes_identical_to_national': True,
                             'xml_changed': original_xml != national_xml,
                             'national_xml_sha256': hashlib.sha256(national_xml).hexdigest(),
                             'board_geometry_preserved': True})
    write_index(candidate / 'fx.idx', index, key)
    for row in rows:
        entry = index['Map/' + row['name']]
        plain = decrypt(read_asset(candidate, entry), entry['flags'])
        assert hashlib.sha256(xml_parts(plain)[2]).hexdigest() == row['national_xml_sha256']
    data = ROOT / 'server/src/main/resources/data/local-monster-stages.tsv'
    preserved = [line for line in data.read_text(encoding='utf-8').splitlines()
                 if not line.startswith(('MonSeal', 'MonTurtle', 'MonRobot'))]
    data.write_text('\n'.join(preserved + metadata) + '\n', encoding='utf-8')
    (ROOT / 'port-audit/added-national-script-sync.json').write_text(json.dumps(rows, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'maps_synced': len(rows), 'actors': len(metadata), 'xml_changed': sum(r['xml_changed'] for r in rows)}))


if __name__ == '__main__':
    main()
