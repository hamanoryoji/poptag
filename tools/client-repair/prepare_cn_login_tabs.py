"""Native login presentation adapter. Authentication remains in CLoginStage.

Draw the original national selector sprites and handle their mouse transitions
inside the login renderer only. No foreign authentication initialization, input
injection, debugger, or game-process memory writes are used by the build/test.
"""
from audit_shop324_compat import *
from legacy_sprite_codec import convert_sprite
from rewrite_fx_index import write_index
import pefile

DEST=ROOT/'port-audit/login-tabs-adapter'

class Asm:
    def __init__(self,base):self.base=base;self.b=bytearray();self.labels={};self.fix=[];self.absolute=[];self.relative=[];self.iat=[]
    def emit(self,s):self.b.extend(bytes.fromhex(s))
    def u32(self,n):self.b.extend(struct.pack('<I',n&0xffffffff))
    def label(self,s):self.labels[s]=len(self.b)
    def branch(self,op,label):
        self.emit(op);self.fix.append((len(self.b),label));self.u32(0)
    def call(self,a):
        self.emit('e8');o=len(self.b);self.u32(a-self.base-o-4);self.relative.append({'offset':o,'end':o+4,'target':a})
    def api(self,name,imports):
        self.emit('ff15');o=len(self.b);self.u32(imports[name]);self.iat.append({'offset':o,'name':name})
    def address(self,n):self.absolute.append(len(self.b));self.u32(n)
    def push(self,n):self.emit('68');self.u32(n)
    def pushaddr(self,n):self.emit('68');self.address(n)
    def load(self,op,n):self.emit(op);self.address(n)
    def done(self):
        for o,label in self.fix:struct.pack_into('<i',self.b,o,self.labels[label]-o-4)
        return bytes(self.b)

def build(base,imports):
    data=base+0x1000;strings={};tail=bytearray(0x100)
    def string(s):
        if s not in strings:strings[s]=data+len(tail);tail.extend(s.encode('ascii')+b'\0')
        return strings[s]
    mode0,mode1,last,proc=data,data+4,data+8,data+12
    a=Asm(base)
    # Four original renderer arguments remain at EBP+8..20.
    a.emit('558bec83ec3060ff7514ff7510ff750cff75088bcf');a.call(0x75e110)
    a.emit('8945cc') # preserve the original native drawing return value in PUSHAD
    a.push(1);a.api('GetAsyncKeyState',imports);a.emit('2500800000')
    a.load('8b15',last);a.load('a3',last);a.emit('85c0');a.branch('0f84','draw')
    a.emit('85d2');a.branch('0f85','draw')
    a.api('GetForegroundWindow',imports);a.emit('85c0');a.branch('0f84','draw');a.emit('8bd8')
    a.load('a1',proc);a.emit('85c0');a.branch('0f85','resolved')
    a.pushaddr(string('user32.dll'));a.api('GetModuleHandleA',imports);a.emit('85c0');a.branch('0f84','draw')
    a.pushaddr(string('GetWindowThreadProcessId'));a.emit('50');a.api('GetProcAddress',imports)
    a.load('a3',proc);a.emit('85c0');a.branch('0f84','draw')
    a.label('resolved');a.emit('8d4dfc5153ffd0');a.api('GetCurrentProcessId',imports)
    a.emit('3b45fc');a.branch('0f85','draw')
    a.emit('8d45f050');a.api('GetCursorPos',imports);a.emit('85c0');a.branch('0f84','draw')
    a.emit('8d45f05053');a.api('ScreenToClient',imports);a.emit('85c0');a.branch('0f84','draw')
    a.emit('8d45d85053');a.api('GetClientRect',imports);a.emit('85c0');a.branch('0f84','draw')
    a.emit('837de000');a.branch('0f8e','draw');a.emit('837de400');a.branch('0f8e','draw')
    a.emit('8b45f069c02003000099f77de08945ec8b45f469c05802000099f77de4')
    a.emit('3daa010000');a.branch('0f8c','draw');a.emit('3dc9010000');a.branch('0f8d','draw')
    a.emit('8b45ec83be7001000000');a.branch('0f85','click_two')
    a.emit('3d15010000');a.branch('0f8c','draw');a.emit('3d0b020000');a.branch('0f8d','draw')
    a.emit('3d900100000f9dc00fb6c0');a.load('a3',mode0);a.branch('e9','draw')
    a.label('click_two')
    a.emit('3dc8000000');a.branch('0f8c','draw');a.emit('3d8e010000');a.branch('0f8d','click_second')
    a.emit('3d2b0100000f9dc00fb6c0');a.load('a3',mode0);a.branch('e9','draw')
    a.label('click_second')
    a.emit('3d92010000');a.branch('0f8c','draw');a.emit('3d58020000');a.branch('0f8d','draw')
    a.emit('3df50100000f9dc00fb6c0');a.load('a3',mode1)
    a.label('draw');a.emit('83be7001000000');a.branch('0f85','draw_two')
    def image(key,x,y,state=0,mode=None,invert=False):
        if mode is None:a.push(state)
        else:
            a.load('a1',mode)
            if invert:a.emit('83f001')
            a.emit('50')
        a.pushaddr(string('LoginStage/'+key));a.push(y);a.push(x);a.branch('e8','image')
    a.load('a1',mode0);a.emit('85c0');a.branch('0f84','one_tabs')
    image('LO_LoginBox1P',277,455)
    a.label('one_tabs');image('LO_BtBNBId1P',277,426,mode=mode0,invert=True);image('LO_BtPTId1P',400,426,mode=mode0)
    a.branch('e9','end')
    a.label('draw_two')
    a.load('a1',mode0);a.emit('85c0');a.branch('0f84','two_box_second');image('LO_LoginBox2P',200,455)
    a.label('two_box_second');a.load('a1',mode1);a.emit('85c0');a.branch('0f84','two_tabs');image('LO_LoginBox2P',402,455)
    a.label('two_tabs')
    image('LO_BtBNBId2P',200,426,mode=mode0,invert=True);image('LO_BtPTId2P',299,426,mode=mode0)
    image('LO_BtBNBId2P',402,426,mode=mode1,invert=True);image('LO_BtPTId2P',501,426,mode=mode1)
    a.label('end');a.emit('618be55dc21000')
    a.label('image');a.emit('558bec6a006a006a00ff7510');a.call(0x75e980)
    a.emit('8bc8');a.call(0x75ec60);a.emit('85c0');a.branch('0f84','image_end')
    a.emit('ff751450ff750cff75088bcf');a.call(0x75e110)
    a.label('image_end');a.emit('5dc21000')
    code=a.done();assert len(code)<0x1000
    payload=code+bytes(0x1000-len(code))+tail
    return payload,{'codeBytes':len(code),'absolute':a.absolute,'relative':a.relative,'iat':a.iat,'dataOffset':0x1000,'strings':strings}

def main():
    assert not DEST.exists(),'Do not overwrite a previous baseline';DEST.mkdir()
    exe=(ROOT/'client/CA.exe').read_bytes();pe=pefile.PE(data=exe);align=lambda n,a:(n+a-1)//a*a
    secva=align(max(s.VirtualAddress+max(s.Misc_VirtualSize,s.SizeOfRawData) for s in pe.sections),pe.OPTIONAL_HEADER.SectionAlignment)
    base=pe.OPTIONAL_HEADER.ImageBase+secva
    imports={x.name.decode():x.address for e in pe.DIRECTORY_ENTRY_IMPORT for x in e.imports if x.name}
    payload,meta=build(base,imports)
    header=pe.sections[-1].get_file_offset()+40
    assert not any(exe[header:header+40]) and header+40<=min(s.PointerToRawData for s in pe.sections)
    rawoff=align(len(exe),pe.OPTIONAL_HEADER.FileAlignment);rawsize=align(len(payload),pe.OPTIONAL_HEADER.FileAlignment)
    candidate=bytearray(exe)+bytes(rawoff-len(exe))+payload+bytes(rawsize-len(payload))
    struct.pack_into('<8sIIIIIIHHI',candidate,header,b'.cnlogin',len(payload),secva,rawsize,rawoff,0,0,0,0,0xe0000020)
    struct.pack_into('<H',candidate,pe.FILE_HEADER.get_field_absolute_offset('NumberOfSections'),len(pe.sections)+1)
    struct.pack_into('<I',candidate,pe.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage'),align(secva+len(payload),pe.OPTIONAL_HEADER.SectionAlignment))
    site=0x620f26;o=pe.get_offset_from_rva(site-pe.OPTIONAL_HEADER.ImageBase)
    before=b'\xe8'+struct.pack('<i',0x75e110-site-5);assert exe[o:o+5]==before
    candidate[o:o+5]=b'\xe8'+struct.pack('<i',base-site-5)
    (DEST/'CA.exe.before').write_bytes(exe);(DEST/'CA.exe.candidate').write_bytes(candidate);(DEST/'adapter.bin').write_bytes(payload)
    idx,key=load_index(ROOT/'client/fx.idx');source,_=load_index(CN057/'fx.idx');oldkeys=list(idx)
    fxbase=(ROOT/'client/fx.idd').stat().st_size;append=bytearray();changes=[]
    for keyname in ['LO_BtBNBId1P','LO_BtPTId1P','LO_BtBNBId2P','LO_BtPTId2P','LO_LoginBox1P','LO_LoginBox2P']:
        name='LoginStage/'+keyname;assert name not in idx
        content,pages=convert_sprite(read_asset(CN057,source[name]));new={**source[name],'offset':fxbase+len(append),'size':len(content),'flags':0}
        idx[name]=new;append.extend(content);changes.append({'name':name,'entry':new,'sha256':hashlib.sha256(content).hexdigest()})
    (DEST/'fx.idx.before').write_bytes((ROOT/'client/fx.idx').read_bytes());(DEST/'fx.append.bin').write_bytes(append)
    write_index(DEST/'fx.idx.candidate',idx,key)
    after,_=load_index(DEST/'fx.idx.candidate');assert [k for k in oldkeys if k.startswith('Map/')]==[k for k in after if k.startswith('Map/')]
    manifest={**meta,'base':base,'site':site,'siteOffset':o,'before':before.hex(),'originalExeBytes':len(exe),
        'payload':payload.hex(),'fxOriginalBytes':fxbase,'assets':changes,'headerOffset':header,'rawOffset':rawoff,
        'authenticationChanged':False,'installed':False,'liveVerified':False}
    (DEST/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'adapterBytes':meta['codeBytes'],'sectionBase':hex(base),'assets':len(changes),'authChanged':False}))

if __name__=='__main__':main()
