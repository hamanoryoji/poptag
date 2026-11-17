"""Expand local normal-mode wallet only; preserve original ranked cap routine."""
from pathlib import Path
import struct,json,hashlib
import pefile
from capstone import Cs,CS_ARCH_X86,CS_MODE_32
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'port-audit/local-economy/wallet-cap'
def align(n,a):return (n+a-1)//a*a
def main():
    OUT.mkdir(parents=True,exist_ok=False)
    exe=(ROOT/'client/CA.exe').read_bytes();pe=pefile.PE(data=exe);image=pe.OPTIONAL_HEADER.ImageBase
    site=0x5062e9;end=0x506315;o=pe.get_offset_from_rva(site-image);old=exe[o:o+end-site]
    assert old[:8]==bytes.fromhex('56ff7424088bf1e8')
    last=pe.sections[-1];rva=align(last.VirtualAddress+max(last.Misc_VirtualSize,last.SizeOfRawData),pe.OPTIONAL_HEADER.SectionAlignment);base=image+rva
    # JNE skips the new normal-wallet return and enters an exact relocated copy.
    prefix=bytes.fromhex('837c2404007508b8')+struct.pack('<I',99999999)+bytes.fromhex('c20400')
    code=bytearray(prefix+old);rel=[]
    for i in Cs(CS_ARCH_X86,CS_MODE_32).disasm(old,site):
        if i.mnemonic=='call' and i.bytes[0]==0xe8:
            target=int(i.op_str,16);off=len(prefix)+i.address-site+1
            struct.pack_into('<i',code,off,target-(base+off+4));rel.append({'offset':off,'target':target})
    header=last.get_file_offset()+40;assert not any(exe[header:header+40])
    assert header+40<=min(s.PointerToRawData for s in pe.sections)
    rawoff=align(len(exe),pe.OPTIONAL_HEADER.FileAlignment);size=align(len(code),pe.OPTIONAL_HEADER.FileAlignment)
    candidate=bytearray(exe)+bytes(rawoff-len(exe))+code+bytes(size-len(code))
    struct.pack_into('<8sIIIIIIHHI',candidate,header,b'.economy',len(code),rva,size,rawoff,0,0,0,0,0x60000020)
    struct.pack_into('<H',candidate,pe.FILE_HEADER.get_field_absolute_offset('NumberOfSections'),len(pe.sections)+1)
    struct.pack_into('<I',candidate,pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage'),align(rva+len(code),pe.OPTIONAL_HEADER.SectionAlignment))
    candidate[o:o+5]=b'\xe9'+struct.pack('<i',base-site-5)
    (OUT/'CA.exe.before').write_bytes(exe);(OUT/'CA.exe.candidate').write_bytes(candidate)
    (OUT/'manifest.json').write_text(json.dumps({'code':code.hex(),'base':base,'relocations':rel,'normalLimit':99999999,
        'originalSha256':hashlib.sha256(exe).hexdigest(),'candidateSha256':hashlib.sha256(candidate).hexdigest(),
        'site':site,'siteOffset':o,'originalRoutine':old.hex(),'rankedRoutinePreserved':True,'installed':False},indent=2))
    print('Wallet candidate:',len(code),'bytes; normal cap 99,999,999; ranked branch preserved')
if __name__=='__main__':main()
