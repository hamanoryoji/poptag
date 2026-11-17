"""Extract bounded original reader and badge code for isolated native verification."""
from pathlib import Path
import pefile,json,sys
from capstone import Cs,CS_ARCH_X86,CS_MODE_32
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'port-audit/result-exp-names'
def main():
    exe=(Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'client/CA.exe').read_bytes();pe=pefile.PE(data=exe,fast_load=True);md=Cs(CS_ARCH_X86,CS_MODE_32)
    cases=[]
    for name,start,end in [('reader',0x5413dd,0x5415b5),('badge',0x694368,0x694404)]:
        offset=pe.get_offset_from_rva(start-0x400000);code=bytearray(exe[offset:offset+end-start]);calls=[]
        for ins in md.disasm(code,start):
            if ins.mnemonic=='call':
                assert ins.bytes[0]==0xe8
                calls.append({'offset':ins.address-start+1,'target':int(ins.op_str,16)})
            if ins.mnemonic=='jmp' and ins.op_str=='0x5415bc':
                assert bytes(ins.bytes)==bytes.fromhex('eb07')
                code[ins.address-start+1]=0 # finish at a standalone RET, not the game's EH epilogue
        if name=='reader':code.append(0xc3)
        cases.append({'name':name,'code':code.hex(),'calls':calls})
    (OUT/'native-reader-cases.json').write_text(json.dumps(cases,indent=2))
    print('Prepared actual native result-field reader and original level-badge renderer.')
if __name__=='__main__':main()
