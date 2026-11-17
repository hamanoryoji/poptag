"""Audit every registered three-board monster run; never alter game assets.

Static agreement, server simulation and actual client play are different forms
of evidence. This report explicitly keeps those results separate.
"""
from pathlib import Path
from collections import defaultdict, Counter
import hashlib
import json
import re
from fix_map_object_indices import decrypt
from idd_assets import load_index, read_asset
from sync_added_national_scripts import xml_parts, parse

ROOT = Path(__file__).resolve().parent
DATA = ROOT / 'server/src/main/resources/data'
OUT = ROOT / 'port-audit/monster-campaign'
ORDER = ['Monster', 'MonPen', 'MonSeal', 'MonTurtle', 'MonRobot']
LABELS = {'Monster': '章鱼岛', 'MonPen': '企鹅', 'MonSeal': '海皇',
          'MonTurtle': '王者龟来／海龟', 'MonRobot': '机械'}


def truth(value):
    return str(value).lower() in ('true', '1')


def combat_structure(node):
    attrs = sorted((k.lower(), v) for k, v in node.attrib.items()
                   if not k.lower().startswith('say') and k.lower() not in ('desc', 'description', 'comment'))
    children = [combat_structure(c) for c in node
                if c.tag.lower() not in ('say', 'chat')
                and not (c.tag.lower() == 'action' and c.get('name', '').lower() == 'say')]
    return node.tag.lower(), attrs, (node.text or '').strip(), children


def main():
    OUT.mkdir(exist_ok=True)
    grouped = defaultdict(list)
    for line in (DATA / 'local-monster-stages.tsv').read_text(encoding='utf-8-sig').splitlines():
        if line and not line.startswith('#'):
            fields = line.split('\t')
            grouped[fields[0]].append(fields)
    names = dict(line.split('\t', 1) for line in
                 (ROOT / 'server/src/main/resources/localization/zh-CN-map-names.tsv')
                 .read_text(encoding='utf-8-sig').splitlines() if '\t' in line and not line.startswith('#'))
    dimensions = {a[0]: (int(a[1]), int(a[2])) for a in
                  (line.split('\t') for line in (DATA / 'local-map-dimensions.tsv').read_text().splitlines())}
    records = json.loads((DATA / 'fxdynmapinfo.json').read_text(encoding='utf-8'))['records']
    records += json.loads((DATA / 'local-map-expansion.json').read_text(encoding='utf-8'))['records']
    selectable = [r for r in records if r['flag'] == 0]
    national = ROOT.parent.parent / '泡泡堂各个版本/国服 324 版'
    local_idx, _ = load_index(ROOT / 'client/fx.idx')
    cn_idx, _ = load_index(national / 'FxMap.idx')
    boards = {}
    for name, fields in grouped.items():
        family, number = re.fullmatch(r'(\D+)(\d+)', name).groups()
        number = int(number)
        errors, warnings = [], []
        key = 'Map/' + name
        if key not in local_idx:
            boards[name] = {'name': name, 'errors': ['Missing client board resource']}
            continue
        entry = local_idx[key]
        raw = decrypt(read_asset(ROOT / 'client', entry), entry['flags'])
        xml = xml_parts(raw)[2]
        tree = parse(xml)
        objects = tree.findall('.//ScriptObject')
        infos = [node.find('InitInfo') for node in objects if node.find('InitInfo') is not None]
        infos.sort(key=lambda node: not truth({k.lower(): v for k, v in node.attrib.items()}.get('fromstart', 'true')))
        if len(infos) != len(fields):
            errors.append('Actor count differs from client XML')
        metadata = {int(row[2]): row for row in fields}
        classes = set()
        bosses, actions = [], Counter()
        for object_id, info in enumerate(infos):
            a = {k.lower(): v for k, v in info.attrib.items()}
            script_class = int(a.get('class', object_id)); classes.add(script_class)
            life = re.search(r'max\s*=\s*(\d+)', a.get('life', ''), re.I)
            anger = re.search(r'anger\s*=\s*(\d+)', a.get('life', ''), re.I)
            expected = [name, f'{family}{number + 1:02d}' if number % 3 else '-', str(object_id),
                        a['type'].lower(), str(int(life[1]) if life else 1),
                        str(int(truth(a.get('fromstart', 'true')))), str(int(truth(a.get('boss', 'false')))),
                        str(script_class), str(int(anger[1]) if anger else 0),
                        str(int(not truth(a.get('noinfluencegameend', '0'))))]
            actual = metadata.get(object_id, [])[:10]
            if actual:
                actual[3] = actual[3].lower()
            if actual != expected:
                errors.append(f'Actor {object_id} metadata differs from XML')
            if truth(a.get('boss', 'false')):
                bosses.append({'id': object_id, 'class': script_class, 'type': a['type'],
                               'hp': expected[4], 'angerHp': expected[8]})
        for obj in objects:
            a = {k.lower(): v for k, v in obj.find('InitInfo').attrib.items()}
            terminal = (a.get('type', '').lower() == 'starfishmon'
                        and not truth(a.get('fromstart', 'true'))
                        and not any(t.get('Type', t.get('type', '')).lower() == 'selfdie'
                                    for t in obj.findall('.//Trigger')))
            matching = [row for row in fields if int(row[7]) == int(a.get('class', -1))]
            if any((row[10] == '1' if len(row) > 10 else False) != terminal for row in matching):
                errors.append(f'Terminal actor flag differs for class {a.get("class")}')
            for action in obj.iter():
                if action.tag.lower() != 'action':
                    continue
                attr = {k.lower(): v for k, v in action.attrib.items()}
                action_name = attr.get('name', '').lower(); actions[action_name] += 1
                if action_name == 'createobj' and attr.get('class', '').isdigit() and int(attr['class']) not in classes:
                    warnings.append(f'CreateObj references unknown class {attr["class"]}')
        entries = [r for r in selectable if r['name'] == name]
        if len(entries) != 1:
            errors.append('Board must have exactly one game record')
        expected_next = f'{family}{number + 1:02d}' if number % 3 else '-'
        if expected_next != '-' and expected_next not in grouped:
            errors.append('Next board is missing')
        initial_targets = [row for row in fields if row[5] == '1' and row[9] == '1'
                           and (not bosses or row[6] == '1')]
        if not initial_targets:
            errors.append('No initial clear objectives')
        same = None
        combat_same = None
        if key in cn_idx:
            cn = cn_idx[key]
            cn_raw = decrypt(read_asset(national, cn, 'FxMap'), cn['flags'])
            national_xml = xml_parts(cn_raw)[2]
            same = xml == national_xml
            combat_same = combat_structure(tree) == combat_structure(parse(national_xml))
            if not combat_same:
                warnings.append('Combat script differs from national 324')
        else:
            warnings.append('No matching national 324 script resource')
        boards[name] = {'name': name, 'display': names.get(name, name), 'family': family,
                        'number': number, 'next': expected_next, 'dimensions': dimensions.get(name),
                        'actors': len(infos), 'initialObjectives': len(initial_targets), 'bosses': bosses,
                        'actions': dict(actions), 'nationalXmlBytesEqual': same,
                        'nationalCombatScriptEqual': combat_same,
                        'scriptSha256': hashlib.sha256(xml).hexdigest(),
                        'errors': sorted(set(errors)), 'warnings': sorted(set(warnings))}
    runs = []
    for family in ORDER:
        for name in sorted(n for n in boards if n.startswith(family) and boards[n].get('number', 0) % 3 == 1):
            num = boards[name]['number']; chain = [f'{family}{num+i:02d}' for i in range(3)]
            issues = [f'{n}: {e}' for n in chain for e in boards.get(n, {}).get('errors', ['Missing board'])]
            runs.append({'order': len(runs)+1, 'entry': name, 'label': names.get(name, LABELS[family] + f' {(num+2)//3:02d}-1'),
                         'boards': chain, 'staticStatus': 'FAIL' if issues else 'PASS', 'issues': issues,
                         'clientPlayStatus': 'pending', 'notes': ''})
    state = OUT / 'playtest-status.json'
    if state.exists():
        old = {r['entry']: r for r in json.loads(state.read_text(encoding='utf-8'))}
        for row in runs:
            for key in ['clientPlayStatus', 'notes']:
                row[key] = old.get(row['entry'], {}).get(key, row[key])
    state.write_text(json.dumps(runs, ensure_ascii=False, indent=2), encoding='utf-8')
    report = {'scope': 'static client/server/script audit; not actual gameplay', 'groups': len(runs),
              'boards': len(boards), 'failedBoards': [n for n, r in boards.items() if r['errors']],
              'warningBoards': [n for n, r in boards.items() if r['warnings']], 'results': boards}
    (OUT / 'static-audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# 怪物模式顺序检查表', '',
             '静态通过仅代表资源、脚本元数据与切关配置一致，不代表游戏实测通过。', '',
             '| 顺序 | 入口名称 | 内部三关 | 静态检查 | 客户端实测 | 备注 |', '|---|---|---|---|---|---|']
    for row in runs:
        lines.append(f'| {row["order"]} | {row["label"]} | {" → ".join(row["boards"])} | {row["staticStatus"]} | {row["clientPlayStatus"]} | {row["notes"]} |')
    lines += ['', '每组实测项目：进入第一关；小怪受击与清关；两次关卡切换；Boss攻击、掉血、阶段变化与死亡；胜负结算、返回原房间及再次开始。',
              '', '优先级：崩溃／掉线 > 无法推进／结算 > 战斗行为偏差 > 显示问题。']
    (ROOT / '怪物模式顺序测试清单.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ['groups', 'boards', 'failedBoards', 'warningBoards']}))


if __name__ == '__main__':
    main()
