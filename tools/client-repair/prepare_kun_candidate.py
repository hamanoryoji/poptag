"""Build a separate, opt-in KUN appearance/attributes candidate using legacy AI."""
from prepare_cn057_shop_port import ROOT
from prepare_remaining_ui import write_preserving_index,sha
from fix_map_object_indices import decrypt
from idd_assets import load_index,read_asset
from legacy_sprite_codec import convert_sprite
from pathlib import Path
import json,struct,shutil,re

DEST=ROOT.parent/'PopTag-kun-probe'
BUNDLE=ROOT.parent/'PopTag-shop/KUN-cn324'
def encrypt(data,key):
    assert len(data)%4==0
    out=bytearray();previous=0
    for i,value in enumerate(struct.unpack('<'+'I'*(len(data)//4),data)):
        factor=0 if i==0 else ((i+0x3c6)*(i+0x3c6+0x41e)*i*i)&0xffffffff
        out+=struct.pack('<I',(value^(key if i==0 else previous)^factor^0x9c6c95ce)&0xffffffff);previous=value
    return bytes(out)
def crc(data,seed):
    for value in data:
        seed^=value<<24
        for _ in range(8):seed=((seed<<1)^(0x04c11db7 if seed&0x80000000 else 0))&0xffffffff
    return seed

def main():
    assert not DEST.exists(),'Do not overwrite an existing candidate'
    assert (ROOT/'port-audit/npc-level-restore-v2/installed.json').is_file(),'Base must contain the verified NPC/level fix first'
    source=json.loads((BUNDLE/'manifest.json').read_text(encoding='utf-8'))
    assert not source['decodeIssues'] and source['modernCharacterId']==4015
    DEST.mkdir();(DEST/'logs').mkdir();(DEST/'server/target').mkdir(parents=True)
    shutil.copytree(ROOT/'client',DEST/'client',ignore=shutil.ignore_patterns('*.bak','*.before*','*.candidate','*.dmp','*.log','*.tmp','*.backup','*.7z'))
    shutil.copytree(ROOT/'server/target/classes',DEST/'server/target/classes')
    shutil.copy2(ROOT/'server/target/runtime-classpath.txt',DEST/'server/target/runtime-classpath.txt')
    for p in (ROOT/'server').glob('*.yaml'):shutil.copy2(p,DEST/'server'/p.name)
    if (ROOT/'server/data').exists():shutil.copytree(ROOT/'server/data',DEST/'server/data')
    for name in ['runtime.json','启动本地版.ps1','停止本地版.ps1']:shutil.copy2(ROOT/name,DEST/name)
    client=DEST/'client';idx,key=load_index(client/'fx.idx');before=dict(idx);payloads={};converted=0
    base_actions={n.split('Character/N01-',1)[1] for n in idx if n.startswith('Character/N01-')}
    actions=set()
    for row in source['assets']:
        name=row['name']
        if not name.startswith('Character/N16-'):continue
        assert name not in idx
        raw=(BUNDLE/'assets'/(name+'.bin')).read_bytes();assert sha(raw)==row['sha256']
        data,audit=convert_sprite(raw);converted+=bool(audit)
        payloads[name]=(data,{'flags':0,'key':0,'rawName':name.encode('ascii').hex()});actions.add(name.split('Character/N16-',1)[1])
    assert base_actions<=actions and len(payloads)==42
    entry=idx['Attr/BomberAttr'];original_attr=read_asset(client,entry);plain=decrypt(original_attr,entry['flags'])
    assert encrypt(plain,entry['flags'])==original_attr and crc(plain,entry['flags'])==entry['key']
    assert len(plain)==32*57 and plain[25*57+4:25*57+17]==b'Character/C25'
    modern=(BUNDLE/'BomberAttr-cn324-record.bin').read_bytes();legacy=modern[:17]+modern[21:]
    assert legacy==(BUNDLE/'BomberAttr-legacy-format-record.bin').read_bytes() and legacy[4:17]==b'Character/N16'
    updated=plain[:25*57]+legacy+plain[26*57:]
    encrypted=encrypt(updated,entry['flags']);assert decrypt(encrypted,entry['flags'])==updated
    payloads['Attr/BomberAttr']=(encrypted,{**entry,'key':crc(updated,entry['flags'])})
    entry=idx['ClientData/ClientString'];xml=read_asset(client,entry)[4:]
    elem=b'<Elem><LangID>1033</LangID><key>AIBattleNpcName05</key><string>'+('菜刀'.encode('gbk'))+b'</string></Elem>\r\n'
    assert b'aibattlenpcname05' not in xml.lower()
    xml=xml.replace(b'</DocumentElement>',elem+b'</DocumentElement>')
    payloads['ClientData/ClientString']=(struct.pack('<I',len(xml))+xml,entry)
    size=(client/'fx.idd').stat().st_size;append=bytearray();changes=[]
    for name,(data,metadata) in payloads.items():
        idx[name]={**metadata,'offset':size+len(append),'size':len(data)};append+=data
        changes.append({'name':name,'sha256':sha(data)})
    idx={n:idx[n] for n in sorted(idx,key=str.lower)}
    assert [n for n in idx if n in before]==list(before),'Preserve all previous resource ordering'
    assert [n for n in idx if n.startswith('Map/')]==[n for n in before if n.startswith('Map/')]
    with (client/'fx.idd').open('ab') as f:f.write(append)
    write_preserving_index(client/'fx.idx',idx,key)
    for row in changes:assert sha(read_asset(client,idx[row['name']]))==row['sha256']
    # Add KUN to this candidate's existing result-name adapter, without touching
    # the stable executable. Its table is moved into unused space in .result.
    import pefile
    binary=bytearray((client/'CA.exe').read_bytes());p=pefile.PE(data=binary,fast_load=True)
    section=next(s for s in p.sections if s.Name.rstrip(b'\0')==b'.result');off=section.PointerToRawData;base=0x400000+section.VirtualAddress
    body=bytearray(binary[off:off+0x300]);at=body.find(bytes.fromhex('83e81483f803'));assert at>=0;body[at+5]=5
    ptr=body.find(b'\xff\x34\x85'+struct.pack('<I',base+0x200));assert ptr>=0
    struct.pack_into('<I',body,ptr+3,base+0x2a0)
    pointers=list(struct.unpack_from('<4I',body,0x200))+[base+0x2c0,base+0x2d0]
    struct.pack_into('<6I',body,0x2a0,*pointers)
    for offset,text in [(0x2c0,'电脑对手'),(0x2d0,'菜刀')]:
        data=text.encode('gbk')+b'\0';body[offset:offset+len(data)]=data
    binary[off:off+0x300]=body;(client/'CA.exe').write_bytes(binary)
    manifest={'source':source['source'],'model':'Character/N16','modernId':4015,'legacyId':25,'slot':4,'difficulty':4,'badge':163,'convertedAssets':converted,'allRequiredActionsPresent':True,'nativeAIported':False,'ai':'existing legacy red preset 4; no extra attack/pursuit boost','nativeRedItems':['Item/Shield','Item/ItemNiddle','Item/Spring'],'gameplayVerified':False,'stableClientChanged':False,'mapOrderPreserved':True,'changes':changes}
    (client/'kun-prototype.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    launch=f"$ErrorActionPreference='Stop'\n& '{ROOT / '停止本地版.ps1'}'\n& '{DEST / '启动本地版.ps1'}'\n"
    restore=f"$ErrorActionPreference='Stop'\n& '{DEST / '停止本地版.ps1'}'\n& '{ROOT / '启动本地版.ps1'}'\n"
    (DEST/'启动菜刀候选.ps1').write_text(launch,encoding='utf-8-sig');(DEST/'返回稳定版本.ps1').write_text(restore,encoding='utf-8-sig')
    (DEST/'测试说明.txt').write_text('独立菜刀外观／属性兼容候选，尚未验证实际游玩。\n启动菜刀候选.ps1会停止稳定版本，两个服务不能同时占用本地端口。\n登录潜在游鱼，创建激战房，第一个NPC自动替换为菜刀（红档163）。其他NPC仍可比较。\n检查显示、移动、放泡、受击、道具和结算；原版国服菜刀AI未移植。\n账号为创建候选时的独立副本，候选里的金币和物品变化不回写稳定版本。\n返回稳定版本.ps1可切回。\n',encoding='utf-8-sig')
    print(json.dumps({'candidate':str(DEST),'convertedAssets':converted,'requiredActions':len(base_actions),'gameplayVerified':False},ensure_ascii=False))
if __name__=='__main__':main()
