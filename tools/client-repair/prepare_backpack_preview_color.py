"""Keep the shared FX preview palette equal to the backpack's selected palette.
Only CMyPage's refresh entry is adapted; inventory, catalog and game actors stay native.
"""
from prepare_cn_login_tabs import Asm
from pathlib import Path
import pefile,struct,json,hashlib
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'port-audit/backpack-preview-color/common-adapter'
sha=lambda b:hashlib.sha256(b).hexdigest()
def align(n,a):return (n+a-1)//a*a

def main():
    OUT.mkdir(exist_ok=True);assert not (OUT/'installed.json').exists()
    exe=(ROOT/'client/CA.exe').read_bytes();pe=pefile.PE(data=exe,fast_load=True);image=pe.OPTIONAL_HEADER.ImageBase
    last=pe.sections[-1];rva=align(last.VirtualAddress+max(last.Misc_VirtualSize,last.SizeOfRawData),pe.OPTIONAL_HEADER.SectionAlignment);base=image+rva
    a=Asm(base);a.emit('9c60') # preserve flags and every register across native getters
    a.load('8b1d',0x9e06c0);a.emit('85db');a.branch('0f84','done')
    a.emit('8b866093010083f801');a.branch('0f87','done') # profile inventory index, only 0/1
    a.emit('8bd0') # retain slot through getter
    a.emit('8d8e049c0100');a.call(0x44b790) # CMyPage preview+0x630 selected palette
    a.emit('83f808');a.branch('0f86','valid');a.emit('33c0')
    a.label('valid');a.emit('8bf88b866093010069c0380400008d8c0384000000') # fixed below: manager+0xC+0x84
    # CUserFx preview is manager+slot*0x438+0xC, protected palette at +0x84.
    struct.pack_into('<I',a.b,len(a.b)-4,0x90)
    a.call(0x44b790);a.emit('3bc7');a.branch('0f84','done')
    a.emit('57ffb6609301008bcb');a.call(0x67973e)
    a.label('done');a.emit('619d');a.load('8b0d',0x9e06c0);a.emit('c3');code=a.done()
    site=0x5dee57;off=pe.get_offset_from_rva(site-image);old=bytes.fromhex('8b0dc0069e00');assert exe[off:off+6]==old
    header=last.get_file_offset()+40;assert not any(exe[header:header+40]) and header+40<=min(s.PointerToRawData for s in pe.sections)
    rawoff=align(len(exe),pe.OPTIONAL_HEADER.FileAlignment);size=align(len(code),pe.OPTIONAL_HEADER.FileAlignment)
    candidate=bytearray(exe)+bytes(rawoff-len(exe))+code+bytes(size-len(code))
    struct.pack_into('<8sIIIIIIHHI',candidate,header,b'.prvcol',len(code),rva,size,rawoff,0,0,0,0,0x60000020)
    struct.pack_into('<H',candidate,pe.FILE_HEADER.get_field_absolute_offset('NumberOfSections'),len(pe.sections)+1)
    struct.pack_into('<I',candidate,pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage'),align(rva+len(code),pe.OPTIONAL_HEADER.SectionAlignment))
    candidate[off:off+6]=b'\xe8'+struct.pack('<i',base-site-5)+b'\x90'
    (OUT/'CA.exe.before').write_bytes(exe);(OUT/'CA.exe.candidate').write_bytes(candidate)
    (OUT/'manifest.json').write_text(json.dumps({'base':base,'payload':code.hex(),'absolute':a.absolute,'relative':a.relative,
        'site':site,'siteOffset':off,'before':old.hex(),'beforeSha256':sha(exe),'candidateSha256':sha(candidate),
        'scope':'CMyPage preview refresh only; normalize shared FX palette from selected preview palette before obtaining its sprite',
        'visualVerification':'pending'},indent=2))
    print('Prepared common backpack preview adapter:',len(code),'bytes')
if __name__=='__main__':main()
