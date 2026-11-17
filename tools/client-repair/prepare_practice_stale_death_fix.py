"""Extend the existing non-trapped stale-death guard to free practice."""
from pathlib import Path
import json,struct,pefile,capstone
root=Path(__file__).resolve().parent
scope={'__file__':str(root/'prepare_test_supplies.py')}
exec((root/'prepare_test_supplies.py').read_text().split('# Return success')[0],scope)
Code=scope['Code']
path=root/'client/CA.exe';original=path.read_bytes();data=bytearray(original);pe=pefile.PE(data=data)
address=0xb95b00;offset=pe.get_offset_from_rva(address-0x400000)
prefix=bytes.fromhex('9c60a1443c990083b8700500000e75')
assert data[offset:offset+len(prefix)]==prefix
c=Code(address)
c.emit('9c60a1443c990083b8700500000e');c.branch('8c','original')
c.emit('83b8700500000f');c.branch('8f','original')
c.emit('8bce');c.call(0x45bd64);c.emit('85c0');c.branch('85','original')
c.emit('6a008d8ec42a0000');c.call(0x44b4d1)
c.emit('619d');c.jump(0x4689a5)
c.label('original');c.emit('619d8b068bceff90e0010000');c.jump(0x4689a5)
code=c.finish();assert len(code)<0x100
before=bytes(data[offset:offset+0x100]);data[offset:offset+0x100]=code.ljust(0x100,b'\0')
cs=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_32)
relative=[]
for ins in cs.disasm(code,address):
    if ins.mnemonic in ('call','jmp') and ins.op_str.startswith('0x'):
        target=int(ins.op_str,16)
        if target in (0x45bd64,0x44b4d1,0x4689a5):relative.append(dict(offset=ins.address-address+1,end=ins.address-address+ins.size,target=target))
audit=root/'port-audit/practice-stale-death';audit.mkdir(exist_ok=True)
(audit/'CA.exe.before-practice-guard').write_bytes(original)
(audit/'candidate.exe').write_bytes(data)
(audit/'manifest.json').write_text(json.dumps(dict(address=address,before=before.hex(),after=code.ljust(0x100,b'\0').hex(),stub=code.hex(),relative=relative),indent=2))
print('Prepared practice non-trapped death-timer guard')
