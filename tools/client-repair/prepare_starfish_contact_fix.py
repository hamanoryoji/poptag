"""Retired experiment; shell contact is fixed in the server collision-field mapping."""
raise SystemExit('Retired: correct C116 base/size -> S151 event11/event12 instead of modifying the contact gate.')
from pathlib import Path
import hashlib, json, struct
import pefile

root = Path(__file__).resolve().parent
scope = {'__file__': str(root/'prepare_test_supplies.py')}
exec((root/'prepare_test_supplies.py').read_text().split('# Return success')[0], scope)
Code = scope['Code']
original = (root/'client/CA.exe').read_bytes()
data = bytearray(original)
pe = pefile.PE(data=original)
# Existing .local tails contain earlier fixes. Add our own section rather
# than treating their zero padding as unowned scratch space.
align = lambda n, a: (n+a-1)//a*a
header = pe.sections[-1].get_file_offset()+40
assert header+40 <= pe.OPTIONAL_HEADER.SizeOfHeaders
assert data[header:header+40] == bytes(40)
rva = align(max(s.VirtualAddress+max(s.Misc_VirtualSize,s.SizeOfRawData) for s in pe.sections), pe.OPTIONAL_HEADER.SectionAlignment)
raw = align(len(data), pe.OPTIONAL_HEADER.FileAlignment)
raw_size = align(0x100, pe.OPTIONAL_HEADER.FileAlignment)
data.extend(bytes(raw+raw_size-len(data)))
data[header:header+40] = struct.pack('<8sIIIIIIHHI', b'.shell\0\0', 0x100, rva, raw_size, raw, 0,0,0,0,0x60000020)
struct.pack_into('<H',data,pe.FILE_HEADER.get_field_absolute_offset('NumberOfSections'),pe.FILE_HEADER.NumberOfSections+1)
struct.pack_into('<I',data,pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage'),align(rva+0x100,pe.OPTIONAL_HEADER.SectionAlignment))
pe = pefile.PE(data=data)
address = 0x400000+rva
c = Code(address)
# Called only from StarFishMon::CanCollideWithBomber, after its state,
# post-spawn grace and cooldown checks. Keep other game modes unchanged.
c.emit('a1443c990085c0'); c.branch('84', 'fallback')
c.emit('83b8700500000e'); c.branch('85', 'fallback')
c.emit('518b01ff50585983f80b'); c.branch('84', 'shell')
c.emit('83f80c'); c.branch('85', 'fallback')
c.label('shell'); c.emit('b801000000c20400')
c.label('fallback'); c.jump(0x5867a1)
stub = c.finish()
patches = []
def patch(va, before, after):
    off = pe.get_offset_from_rva(va-0x400000)
    assert len(before) == len(after) and data[off:off+len(before)] == before, hex(va)
    data[off:off+len(before)] = after
    patches.append(dict(address=va, before=before.hex(), after=after.hex()))
patch(address, bytes(0x100), stub.ljust(0x100, b'\0'))
patch(0x58b205, b'\xe8'+struct.pack('<i', 0x5867a1-0x58b20a), b'\xe8'+struct.pack('<i', address-0x58b20a))
# A shell may be kicked again after 80ms instead of the old half-second delay.
patch(0x58b1ef, bytes.fromhex('81fff4010000'), bytes.fromhex('81ff50000000'))
audit = root/'port-audit/starfish-contact'
audit.mkdir(exist_ok=True)
(audit/'candidate.exe').write_bytes(data)
(audit/'CA.exe.before-contact').write_bytes(original)
manifest = dict(beforeSha256=hashlib.sha256(original).hexdigest(), afterSha256=hashlib.sha256(data).hexdigest(),
                address=address, stub=stub.hex(), globalOperand=1,
                fallbackJump=c.labels['fallback'], patches=patches,
                coordinateWriter=original[pe.get_offset_from_rva(0x58b253-0x400000):pe.get_offset_from_rva(0x58b285-0x400000)].hex(),
                scope='Only StarFishMon shell states 11/12 in local monster mode14; original fallback elsewhere')
(audit/'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf8')
print('Prepared shell contact candidate; active client unchanged')
