"""Prepare result-state preservation without entering the load-stage context.

The old adapter called SetPhase(1) on an already played practice board. That
routine switches context to slot 1 as part of normal launch preparation. Only
its object-list preservation is needed before native result phase 3 here.
Keep native result construction, rendering, destruction and errors untouched.
"""
from pathlib import Path
import hashlib
import json
import struct
import pefile
import capstone

ROOT = Path(__file__).resolve().parent
scope = {'__file__': str(ROOT / 'prepare_test_supplies.py')}
exec((ROOT / 'prepare_test_supplies.py').read_text().split('# Return success')[0], scope)
Code = scope['Code']
target = ROOT / 'client/CA.exe'
original = target.read_bytes()
pe = pefile.PE(data=original)
old_manifest = json.loads((ROOT.parent / 'PopTag-shop/monster-result-lifecycle-manifest.json').read_text())
address = 0xb95d00
offset = pe.get_offset_from_rva(address - 0x400000)
old = bytes.fromhex(old_manifest['stub'])
assert original[offset:offset + len(old)] == old, 'Unexpected existing result adapter'
assert not any(original[offset + len(old):offset + 0x100]), 'Occupied adapter padding'

c = Code(address)
c.emit('50a1443c990083b8700500000e58'); c.branch('85', 'original')
c.emit('83be3c3f000000'); c.branch('85', 'original')
c.emit('60')
# Clear only old list nodes, using the same native list-clear primitive as
# SetPhase(1). It does not destroy the pointed-to game objects.
c.emit('8d8e443f0000'); c.call(0x54acba)
c.emit('8d9ef83b00008dbe443f00008b033bc3'); c.branch('84', 'saved')
# Splice the complete doubly-linked list; retain object identities and order.
c.emit('8b53048907895704897804893a891b895b04')
c.label('saved')
c.emit('8b86d03000008986403f0000')
# Phase 2 itself only sets the phase number. Do not execute the context-1
# switch from phase 1 or the separate launch handler that resets bombers.
c.emit('6a028bce'); c.call(0x555688)
c.emit('61'); c.jump(0x5585e8)
c.label('original')
c.emit('83be3c3f000002'); c.branch('84', 'allow')
c.jump(0x55864d)
c.label('allow'); c.jump(0x5585e8)
code = c.finish()
assert len(code) <= 0x100
data = bytearray(original)
data[offset:offset + 0x100] = code.ljust(0x100, b'\0')
relocations = []
cs = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
listing = []
for ins in cs.disasm(code, address):
    listing.append(f'{ins.address:08x} {ins.mnemonic} {ins.op_str}')
    if ins.mnemonic in ('call', 'jmp') and ins.op_str.startswith('0x'):
        destination = int(ins.op_str, 16)
        if not address <= destination < address + len(code):
            relocations.append({'offset': ins.address - address + 1,
                                'end': ins.address - address + ins.size,
                                'target': destination})
audit = ROOT / 'port-audit/monster-result-preservation'
audit.mkdir(exist_ok=True)
assert not (audit / 'CA.exe.before-preservation').exists(), 'Do not overwrite baseline'
(audit / 'CA.exe.before-preservation').write_bytes(original)
(audit / 'candidate.exe').write_bytes(data)
(audit / 'disassembly.txt').write_text('\n'.join(listing), encoding='utf-8')
(audit / 'manifest.json').write_text(json.dumps({
    'address': address, 'before': original[offset:offset + 0x100].hex(),
    'after': code.ljust(0x100, b'\0').hex(), 'stub': code.hex(),
    'relative': relocations,
    'beforeSha256': hashlib.sha256(original).hexdigest(),
    'afterSha256': hashlib.sha256(data).hexdigest(),
    'runtimeVerified': False,
}, indent=2))
print('Prepared current-context result preservation:', len(code), 'bytes')
