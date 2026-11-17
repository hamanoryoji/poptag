"""Extend the existing passive exception file with the two collision actors.

No exception is handled, no debugger is attached, and gameplay is unchanged.
The kernel probes WriteFile buffers; an inaccessible diagnostic buffer simply
fails the write. Keep the original record/context/stack first in the output.
"""
from pathlib import Path
import hashlib
import json
import pefile

ROOT = Path(__file__).resolve().parent
scope = {'__file__': str(ROOT / 'prepare_test_supplies.py')}
exec((ROOT / 'prepare_test_supplies.py').read_text().split('# Return success')[0], scope)
Code = scope['Code']
target = ROOT / 'client/CA.exe'
original = target.read_bytes()
data = bytearray(original)
pe = pefile.PE(data=original)
manifest_path = ROOT / 'port-audit/test-supplies/native-patch.json'
manifest = json.loads(manifest_path.read_text())

c = Code(0xb95900)
c.emit('9c608b7424288b068b003d050000c0'); c.branch('84', 'record')
c.emit('3d1d0000c0'); c.branch('85', 'done')
c.label('record')
c.emit('6a0068800000006a046a006a036a04684055b900ff15a0928800')
c.emit('83f8ff'); c.branch('84', 'done'); c.emit('8bf8')
for size, pointer in [(80, 'ff36'), (716, 'ff7604')]:
    c.emit('6a00683055b90068' + size.to_bytes(4, 'little').hex() + pointer + '57ff15f4918800')
c.emit('8b46048b80c40000006a00683055b90068000400005057ff15f4918800')
# CONTEXT32.EDI: collided actor; EBX: actor performing the collision scan.
# Preserve both complete primary/secondary interfaces and their owner fields.
for register_offset in (156, 164):
    c.emit('8b46048b80' + register_offset.to_bytes(4, 'little').hex())
    c.emit('6a00683055b90068400300005057ff15f4918800')
c.emit('57ff15f492880057ff15a4928800')
c.label('done'); c.emit('619d33c0c20400')
code = c.finish()
assert len(code) <= 0x100, len(code)

updates = {
    0xb95900: code.ljust(0x100, b'\0'),
}
for patch in manifest['patches']:
    address = patch['address']
    offset = pe.get_offset_from_rva(address - 0x400000)
    if address == 0xb95500:
        replacement = bytearray.fromhex(patch['after'])
        name = str(ROOT / 'logs/turtle02-native-exception.bin').encode('ascii') + b'\0'
        assert len(name) <= 0x80
        replacement[0x40:0xc0] = name.ljust(0x80, b'\0')
        updates[address] = bytes(replacement)
    if address in updates:
        before = bytes.fromhex(patch['after'])
        assert data[offset:offset + len(before)] == before, hex(address)
        data[offset:offset + len(before)] = updates[address]
        patch['after'] = updates[address].hex()

audit = ROOT / 'port-audit/turtle02-crash-observer'
audit.mkdir(exist_ok=True)
assert not (audit / 'CA.exe.before-observer').exists(), 'Do not overwrite original evidence'
(audit / 'CA.exe.before-observer').write_bytes(original)
(audit / 'native-patch.before.json').write_bytes(manifest_path.read_bytes())
(audit / 'candidate.exe').write_bytes(data)
manifest['afterSha256'] = hashlib.sha256(data).hexdigest()
(audit / 'observer.json').write_text(json.dumps({
    'beforeSha256': hashlib.sha256(original).hexdigest(),
    'afterSha256': manifest['afterSha256'],
    'codeBytes': len(code), 'recordBytesWhenBothBuffersReadable': 3484,
    'bufferOrder': ['EXCEPTION_RECORD32:80', 'CONTEXT32:716', 'stack:1024', 'EDI:832', 'EBX:832'],
    'handlesException': False,
}, indent=2))
target.write_bytes(data)
manifest_path.write_text(json.dumps(manifest, indent=2))
print('Passive actor snapshot observer installed, code bytes:', len(code))
