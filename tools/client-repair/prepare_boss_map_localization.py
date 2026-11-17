"""Prepare six map-selection tooltips without changing map or script assets."""
from pathlib import Path
import hashlib
import json
import re
import struct
from xml.sax.saxutils import escape
from fix_map_object_indices import ROOT
from idd_assets import load_index, read_asset

translations = {
    'MonSeal': '海皇带着手下来袭！小心它的暴怒翻滚，通过前两关后即可挑战海皇。',
    'MonSealRandom': '选择海皇系列地图。',
    'MonTurtle': '龟派仙人带领小海星来袭！部分关卡需要利用海星壳消灭幽灵，通过前两关后即可挑战首领。',
    'MonTurtleRandom': '选择神秘海域系列地图。',
    'MonRobot': '怪物博士率领机器人来袭！通过前两关后，挑战机械帝国首领。',
    'MonRobotRandom': '选择机械帝国系列地图。',
}
idx, _ = load_index(ROOT / 'client/fx.idx')
entry = idx['ClientData/ClientString']
assert entry['flags'] == 0
raw = read_asset(ROOT / 'client', entry)
assert struct.unpack_from('<I', raw)[0] == len(raw) - 4
changes = []
pattern = re.compile(rb'(<Elem>.*?<key>)(.*?)(</key>.*?<string>)(.*?)(</string>.*?</Elem>)', re.S)
def replace(m):
    key = m[2].decode('ascii', errors='replace')
    if key not in translations:
        return m[0]
    changes.append(key)
    return m[1] + m[2] + m[3] + escape(translations[key]).encode('gbk') + m[5]
localized = pattern.sub(replace, raw[4:])
missing = sorted(set(translations) - set(changes))
assert localized.count(b'</DocumentElement>') == 1
for k in missing:
    addition = (b'<Elem>\r\n    <LangID>1033</LangID>\r\n    <key>' + k.encode('ascii')
                + b'</key>\r\n    <string>' + escape(translations[k]).encode('gbk')
                + b'</string>\r\n  </Elem>\r\n  ')
    following = next((m for m in pattern.finditer(localized) if m[2] > k.encode('ascii')), None)
    position = following.start() if following else localized.index(b'</DocumentElement>')
    localized = localized[:position] + addition + localized[position:]
assert sorted(changes + missing) == sorted(translations)
assert localized.count(b'<Elem>') == raw.count(b'<Elem>') + len(missing)
data = struct.pack('<I', len(localized)) + localized
out = ROOT / 'port-audit/boss-map-localization'
out.mkdir(exist_ok=True)
(out / 'ClientString.before.bin').write_bytes(raw)
(out / 'ClientString.zh-CN.bin').write_bytes(data)
(out / 'manifest.json').write_text(json.dumps({
    'asset': 'ClientData/ClientString', 'before': entry,
    'beforeSha256': hashlib.sha256(raw).hexdigest(),
    'afterSha256': hashlib.sha256(data).hexdigest(),
    'translations': translations,
    'addedKeys': missing,
    'officialMapGuide': 'https://bnb.web.sdo.com/web5/gameinfo/gameinfo_2.asp?CategoryID=3019&cid=1&id=121384',
    'officialVersionHistory': 'https://bnb.web.sdo.com/web5/gameinfo/gameinfo_4.asp?Page=16',
    'groupOverride': {'MonTurtleRandom': '王者龟来'},
    'boardSeries': {'MonSeal': '海皇', 'MonTurtle': '神秘海域', 'MonRobot': '机械帝国'},
}, ensure_ascii=False, indent=2), encoding='utf-8')
print('Prepared map-selection Chinese strings:', len(changes), 'replaced,', len(missing), 'added')
