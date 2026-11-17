"""Revert local NPC boosts and extend medal drawing using national 324 artwork."""
from prepare_cn_login_tabs import Asm
from prepare_cn057_shop_port import ROOT,CN324
from prepare_remaining_ui import write_preserving_index,sha
from idd_assets import load_index,read_asset
from legacy_sprite_codec import convert_sprite,frame_from_sprite
import pefile,struct,json

OUT=ROOT/'port-audit/npc-level-restore-v2'
def align(n,a):return (n+a-1)//a*a
def main():
    assert not OUT.exists(),'Preserve previous evidence'
    OUT.mkdir();client=ROOT/'client';original=(client/'CA.exe').read_bytes();p=pefile.PE(data=original,fast_load=True)
    candidate=bytearray(original);patches=[]
    def patch(va,before,after):
        o=p.get_offset_from_rva(va-0x400000);before=bytes.fromhex(before);after=bytes.fromhex(after)
        assert len(before)==len(after) and original[o:o+len(before)]==before,(hex(va),original[o:o+len(before)].hex())
        candidate[o:o+len(after)]=after;patches.append({'va':va,'offset':o,'before':before.hex(),'after':after.hex()})
    # Keep map eligibility and all unrelated gameplay patches intact.
    patch(0x599097,'e9e4bf5f0090','558bec578bf9')
    patch(0x599d5c,'909090909090','0f8e7c010000')
    patch(0x599e8e,'909090909090909090','837d0803750333f646')
    patch(0x59dbc7,'6a556a41','6a2d6a19')
    patch(0x59dbcd,'6a5f6a4b','6a376a23')
    patch(0x694378,'c2010000','d0020000') # Simple medal renderer maximum: 450 -> 720.
    last=p.sections[-1];rva=align(last.VirtualAddress+max(last.Misc_VirtualSize,last.SizeOfRawData),p.OPTIONAL_HEADER.SectionAlignment);base=0x400000+rva
    a=Asm(base)
    a.emit('817c2410c2010000');a.branch('0f8e','legacy')
    # Extended levels use the original arithmetic renderer and its new 720 clamp.
    # Never index beyond the old 450-row cached lookup table.
    a.emit('558becff7514ff7510ff750cff7508');a.call(0x694368);a.emit('83c4105dc21000')
    a.label('legacy');a.emit('558bec8b4514');a.branch('e9','original')
    # Resolve the external continuation as an ordinary relative jump.
    a.labels['original']=0x6945cb-base
    code=a.done();header=last.get_file_offset()+40
    assert not any(original[header:header+40]) and header+40<=min(s.PointerToRawData for s in p.sections if s.PointerToRawData)
    raw=align(len(candidate),p.OPTIONAL_HEADER.FileAlignment);size=align(len(code),p.OPTIONAL_HEADER.FileAlignment)
    candidate+=bytes(raw-len(candidate))+code+bytes(size-len(code))
    struct.pack_into('<8sIIIIIIHHI',candidate,header,b'.lv720\0\0',len(code),rva,size,raw,0,0,0,0,0x60000020)
    struct.pack_into('<H',candidate,p.FILE_HEADER.get_field_absolute_offset('NumberOfSections'),len(p.sections)+1)
    struct.pack_into('<I',candidate,p.OPTIONAL_HEADER.get_field_absolute_offset('SizeOfImage'),align(rva+len(code),p.OPTIONAL_HEADER.SectionAlignment))
    patch(0x6945c5,'558bec8b4514',(b'\xe9'+struct.pack('<i',base-0x6945ca)+b'\x90').hex())
    (OUT/'CA.exe.before').write_bytes(original);(OUT/'CA.exe.candidate').write_bytes(candidate)
    index,key=load_index(client/'fx.idx');old=dict(index);national,_=load_index(CN324/'FxDesktop.idx')
    name='Etc/LevelIcons';source=read_asset(CN324,national[name],'FxDesktop');art,_=convert_sprite(source)
    assert struct.unpack_from('<I',art)[0]==92
    previous=read_asset(client,index[name]);np=struct.unpack_from('<I',previous,8)[0];nq=struct.unpack_from('<I',art,8)[0]
    for i in range(46):
        assert frame_from_sprite(previous,i).size==frame_from_sprite(art,i).size
        assert previous[16+8*np+48*i:16+8*np+48*i+12]==art[16+8*nq+48*i:16+8*nq+48*i+12],('existing medal anchor changed',i)
    # National artwork reserves frames80..91 as empty placeholders. The native
    # arithmetic for levels1..720 references only frames0..79.
    for i in range(80):frame_from_sprite(art,i)
    for i in range(80,92):
        f=struct.unpack_from('<12i',art,16+8*nq+48*i)
        assert f[4]==f[6] and f[5]==f[7]
    iddsize=(client/'fx.idd').stat().st_size
    index[name]={**index[name],'offset':iddsize,'size':len(art),'flags':0,'key':0}
    (OUT/'fx.idx.before').write_bytes((client/'fx.idx').read_bytes());(OUT/'fx.append.bin').write_bytes(art)
    write_preserving_index(OUT/'fx.idx.candidate',index,key)
    assert list(index)==list(old) and all(index[n]==old[n] for n in old if n!=name)
    manifest={'beforeSha256':sha(original),'candidateSha256':sha(candidate),'patches':patches,'base':base,'code':code.hex(),'relative':a.relative,'legacyJumpOffset':len(code)-4,'originalIddSize':iddsize,'originalIdxSha256':sha((client/'fx.idx').read_bytes()),'iconSha256':sha(art),'frames':92,'nationalLevelReference':'https://bnb.web.sdo.com/web5/gameinfo/gameinfo_6_1.asp','maxLevel':720,'mapOrderPreserved':True}
    (OUT/'manifest.json').write_text(json.dumps(manifest,indent=2))
    print(json.dumps({'nativeChanges':len(patches),'maxLevel':720,'medalFrames':92,'adapterBytes':len(code)}))
if __name__=='__main__':main()
