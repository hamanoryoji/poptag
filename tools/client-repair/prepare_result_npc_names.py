"""Use actual AI role for settlement names; leave generic AI formatting untouched."""
from prepare_cn_login_tabs import Asm
from pathlib import Path
import struct,json,hashlib,re,sys
import pefile
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'port-audit/result-exp-names/npc-adapter'
sys.path.insert(0,r'D:/GAME/work/PopTag-shop')
from idd_assets import load_index,read_asset

def align(n,a):return (n+a-1)//a*a
def main():
    OUT.mkdir(exist_ok=True);assert not any(OUT.iterdir()),'Keep prior adapter evidence'
    exe=(ROOT/'client/CA.exe').read_bytes();pe=pefile.PE(data=exe);image=pe.OPTIONAL_HEADER.ImageBase
    last=pe.sections[-1];rva=align(last.VirtualAddress+max(last.Misc_VirtualSize,last.SizeOfRawData),pe.OPTIONAL_HEADER.SectionAlignment);base=image+rva
    idx,_=load_index(ROOT/'client/fx.idx');xml=read_asset(ROOT/'client',idx['ClientData/ClientString'])[4:]
    pairs={k.decode('ascii',errors='replace').lower():v for k,v in re.findall(rb'<key>(.*?)</key>.*?<string>(.*?)</string>',xml,re.S)}
    names=[pairs[f'aibattlenpcname{i:02d}'] for i in range(4)]
    assert [v.decode('gbk') for v in names]==['歪仔','阿八','青宝','嘿木']
    a=Asm(base);a.emit('558bec535657a1');globalOffset=len(a.b);a.u32(0x993c44)
    a.emit('85c0');a.branch('0f84','fallback')
    a.emit('83b87005000010');a.branch('0f85','fallback')
    a.emit('8b55008b520883fa07');a.branch('0f87','fallback')
    a.emit('528bc8');a.call(0x6860d8) # GetSlotBomber(slot), thiscall/ret4
    a.emit('83e81483f803');a.branch('0f87','fallback')
    a.emit('ff3485');a.address(base+0x200)
    a.pushaddr(base+0x210);a.emit('ff7508');a.call(0x75ab10);a.emit('83c40c');a.branch('e9','done')
    a.label('fallback');a.emit('ff7514ff7510ff750cff7508');a.call(0x467f62);a.emit('83c410')
    a.label('done');a.emit('5f5e5b5dc3');code=a.done();assert len(code)<0x200
    payload=bytearray(code)+bytes(0x300-len(code));tableRel=[]
    for i,name in enumerate(names):
        at=0x220+16*i;assert len(name)<16;struct.pack_into('<I',payload,0x200+4*i,base+at)
        payload[at:at+len(name)+1]=name+b'\0';tableRel.append(0x200+4*i)
    payload[0x210:0x213]=b'%s\0'
    header=last.get_file_offset()+40;assert not any(exe[header:header+40]) and header+40<=min(s.PointerToRawData for s in pe.sections)
    rawoff=align(len(exe),pe.OPTIONAL_HEADER.FileAlignment);size=align(len(payload),pe.OPTIONAL_HEADER.FileAlignment)
    candidate=bytearray(exe)+bytes(rawoff-len(exe))+payload+bytes(size-len(payload))
    struct.pack_into('<8sIIIIIIHHI',candidate,header,b'.result',len(payload),rva,size,rawoff,0,0,0,0,0x60000020)
    struct.pack_into('<H',candidate,pe.FILE_HEADER.get_field_absolute_offset('NumberOfSections'),len(pe.sections)+1)
    struct.pack_into('<I',candidate,pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage'),align(rva+len(payload),pe.OPTIONAL_HEADER.SectionAlignment))
    site=0x5412d0;off=pe.get_offset_from_rva(site-image);old=b'\xe8'+struct.pack('<i',0x467f62-site-5);assert exe[off:off+5]==old
    candidate[off:off+5]=b'\xe8'+struct.pack('<i',base-site-5)
    (OUT/'CA.exe.before').write_bytes(exe);(OUT/'CA.exe.candidate').write_bytes(candidate)
    (OUT/'manifest.json').write_text(json.dumps({'base':base,'payload':payload.hex(),'absolute':a.absolute+tableRel,
        'relative':a.relative,'contextAddressOffset':globalOffset,'codeBytes':len(code),'names':[v.decode('gbk') for v in names],
        'site':site,'siteOffset':off,'before':old.hex(),'installed':False,
        'beforeSha256':hashlib.sha256(exe).hexdigest(),'candidateSha256':hashlib.sha256(candidate).hexdigest()},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Prepared AI settlement adapter:',len(code),'bytes; names follow actual role, with original generic fallback.')
if __name__=='__main__':main()
