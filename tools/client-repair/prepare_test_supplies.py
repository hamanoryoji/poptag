"""Prepare reversible, ID-scoped testing hooks for this candidate only."""
from pathlib import Path
import hashlib, json, struct
import pefile, capstone

ROOT = Path(__file__).resolve().parent
path = ROOT / 'client/CA.exe'
original = path.read_bytes()
data = bytearray(original)
pe = pefile.PE(data=original)
patches = []

class Code:
    def __init__(self, address):
        self.address, self.data, self.labels, self.fixups = address, bytearray(), {}, []
    def emit(self, value): self.data.extend(bytes.fromhex(value))
    def call(self, target): self.data.extend(b'\xe8' + struct.pack('<i', target - self.address - len(self.data) - 5))
    def jump(self, target): self.data.extend(b'\xe9' + struct.pack('<i', target - self.address - len(self.data) - 5))
    def branch(self, opcode, label):
        self.emit('0f' + opcode)
        self.fixups.append((len(self.data), label))
        self.data.extend(bytes(4))
    def label(self, name): self.labels[name] = len(self.data)
    def finish(self):
        for offset, name in self.fixups:
            struct.pack_into('<i', self.data, offset, self.labels[name] - offset - 4)
        return bytes(self.data)

def patch(address, before, after):
    offset = pe.get_offset_from_rva(address - 0x400000)
    assert data[offset:offset + len(before)] == before, hex(address)
    assert len(before) == len(after)
    data[offset:offset + len(after)] = after
    patches.append(dict(address=address, before=before.hex(), after=after.hex()))

def install(code, capacity, expected_prefix=b''):
    assembled = code.finish()
    assert len(assembled) <= capacity
    offset = pe.get_offset_from_rva(code.address - 0x400000)
    old = original[offset:offset + capacity]
    assert old.startswith(expected_prefix)
    if not expected_prefix: assert old == bytes(capacity)
    patch(code.address, old, assembled + bytes(capacity - len(assembled)))

# Return success without consuming the two reserved test IDs or the test needle.
# The native inventory-dirty acknowledgement is required by the use-item path.
c = Code(0xb95a00)
c.emit('9c60a1443c99008b54242c81fa13270000'); c.branch('84', 'success')
c.emit('81fa14270000'); c.branch('84', 'success')
c.emit('81fa10270000'); c.branch('85', 'fallback')
c.label('success')
c.emit('8b0d683e99008b44242869c0b00201008d8c01b00201006a01')
c.call(0x44b4d1); c.emit('619dc20800')
c.label('fallback'); c.emit('619d8b442404ff742408'); c.jump(0x67110a)
install(c, 0x100, bytes.fromhex('9c60a1443c990083b8700500000f752d'))

c = Code(0xb95140)
c.emit('9c608b44242883f817'); c.branch('87', 'fallback')
c.emit('8b4c24188b8c81e800000085c9'); c.branch('84', 'fallback')
c.emit('83c108'); c.call(0x44b790)
c.emit('3d13270000'); c.branch('84', 'success')
c.emit('3d14270000'); c.branch('84', 'success')
c.emit('3d10270000'); c.branch('85', 'fallback')
c.label('success'); c.emit('619db801000000c20800')
c.label('fallback'); c.emit('619d558bec837d0c00'); c.jump(0x473066)
install(c, 0xc0, bytes.fromhex('9c60a1443c990083b8700500000f7c3a'))

# Actual blast contact path (0x54C8B3): after 0x5335E5 confirms a collision,
# 0x54C913 asks whether the player has a shield. The outgoing AI state-event
# method 0x462A32 is not used for this local-player collision and is left intact.
# Activate the equipped test shield before the original shield-hit branch runs.
c = Code(0xb95600)
c.emit('9c608bf1ff050055b90089352055b900')
c.call(0x45d15b); c.emit('85c0'); c.branch('85', 'success')
c.emit('83be c8080000 00'); c.branch('84', 'fallback')
c.emit('ff050c55b900')
string_va = 0xb957c0
c.emit('68' + struct.pack('<I', string_va).hex() + '8b0d583d9900')
c.call(0x67769a); c.emit('a31055b90085c0'); c.branch('84', 'fallback')
c.emit('8bf88b06578bceff9094010000a31455b90085c0'); c.branch('8e', 'fallback')
c.emit('576a748bce'); c.call(0x461479)  # ItemShieldC is native effect index 116
c.emit('a31855b90085c0'); c.branch('84', 'fallback')
c.emit('ff051c55b900')
c.label('success'); c.emit('619db801000000c3')
c.label('fallback'); c.emit('619d'); c.jump(0x45d15b)
install(c, 0x1c0)
# Isolated observation data. This section must be writable before any counters
# are used; the original protection is included in byte-verified rollback.
offset = pe.sections[-1].get_file_offset() + 36
patch(0x400000 + offset, struct.pack('<I', pe.sections[-1].Characteristics),
      struct.pack('<I', pe.sections[-1].Characteristics | 0x80000000))
observation_data = bytearray(0x100)
crash_path = str(ROOT / 'logs/client-native-exception-v2.bin').encode('ascii') + b'\0'
assert len(crash_path) <= 0x80
observation_data[0x40:0x40+len(crash_path)] = crash_path
observation_data[0xc0:0xcd] = b'kernel32.dll\0'
observation_data[0xd0:0xec] = b'AddVectoredExceptionHandler\0'
patch(0xb95500, bytes(0x100), bytes(observation_data))
patch(string_va, bytes(0x40), b'Item/ShieldC\0'.ljust(0x40, b'\0'))
patch(0x54c913, b'\xe8' + struct.pack('<i', 0x45d15b - 0x54c918),
      b'\xe8' + struct.pack('<i', 0xb95600 - 0x54c918))

# Observe access violations before the game's own exception handler can exit.
# The callback writes EXCEPTION_RECORD32 + CONTEXT32, then returns CONTINUE_SEARCH;
# it never handles/suppresses the exception and does not attach a debugger.
c = Code(0xb95900)
c.emit('9c608b7424288b068b003d050000c0'); c.branch('84', 'record')
c.emit('3d1d0000c0'); c.branch('85', 'done')
c.label('record')
c.emit('6a0068800000006a046a006a036a04684055b900ff15a0928800')
c.emit('83f8ff'); c.branch('84', 'done')
c.emit('8bf8')
for size, pointer in [(80, 'ff36'), (716, 'ff7604')]:
    c.emit('6a00683055b90068' + struct.pack('<I', size).hex() + pointer + '57ff15f4918800')
# Include the faulting thread's stack before the game's handler terminates it.
c.emit('8b46048b80c40000006a00683055b90068000400005057ff15f4918800')
c.emit('57ff15f492880057ff15a4928800')
c.label('done'); c.emit('619d33c0c20400')
install(c, 0x100)

# Preserve the CRT security-cookie initializer and original entry continuation.
c = Code(0xb95300)
c.call(0x73a91e); c.emit('9c6068c055b900ff153891880085c0'); c.branch('84', 'done')
c.emit('68d055b90050ff15b892880085c0'); c.branch('84', 'done')
c.emit('680059b9006a01ffd0a32855b900')
c.label('done'); c.emit('619d'); c.jump(0x727402)
install(c, 0x100)
patch(0x7273fd, b'\xe8' + struct.pack('<i', 0x73a91e - 0x727402),
      b'\xe9' + struct.pack('<i', 0xb95300 - 0x727402))

audit = ROOT / 'port-audit/test-supplies'
audit.mkdir(parents=True, exist_ok=True)
manifest = audit / 'native-patch.json'
assert not manifest.exists(), 'Patch already prepared; do not overwrite rollback evidence'
backup = audit / 'CA.exe.before-test-supplies'
backup.write_bytes(original)
candidate = audit / 'CA.exe.test-supplies'
candidate.write_bytes(data)
manifest.write_text(json.dumps(dict(beforeSha256=hashlib.sha256(original).hexdigest(),
    afterSha256=hashlib.sha256(data).hexdigest(), patches=patches), indent=2))
cs = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
with (audit / 'native-disassembly.txt').open('w') as output:
    for row in patches:
        if row['address'] == string_va: continue
        for ins in cs.disasm(bytes.fromhex(row['after']), row['address']):
            output.write(f'{ins.address:08x} {ins.mnemonic} {ins.op_str}\n')
print('Prepared test supplies candidate and rollback manifest:', candidate)
