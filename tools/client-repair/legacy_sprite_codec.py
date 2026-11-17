"""Strict modern C0/C1 sprite decoding and legacy RGB565 RLE encoding."""
import struct
import numpy as np
from PIL import Image
from decode_idd_preview import decode_page

def alpha_page(raw,count):
    pos=0;out=bytearray()
    while len(out)<count:
        op=raw[pos];pos+=1;n=max(op&63,1)
        repeated=raw[pos:pos+1] if op&64 else None
        if repeated is not None:pos+=1
        if op&128:out.extend(bytes(n))
        elif op&64:out.extend(repeated*n)
        else:out.extend(raw[pos:pos+n]);pos+=n
    assert len(out)==count and pos==len(raw),(len(out),count,pos,len(raw))
    return bytes(out)

def modern_page(raw,w,h):
    if raw[0]==0xc1:
        alpha_offset=struct.unpack_from('<I',raw,1)[0]
        assert 5<alpha_offset<len(raw)
        image=decode_page(b'\xc0'+raw[5:alpha_offset],w,h)
        image.putalpha(Image.frombytes('L',(w,h),alpha_page(raw[alpha_offset:],w*h)))
        return image
    return decode_page(raw,w,h)

def frame_from_sprite(raw,frame=0):
    nf,version,npages,flags=struct.unpack_from('<4I',raw);assert version==2 and frame<nf
    dims=[struct.unpack_from('<2I',raw,16+8*j) for j in range(npages)]
    f=struct.unpack_from('<12i',raw,16+8*npages+48*frame)
    pos=16+8*npages+48*nf
    for j,(h,w) in enumerate(dims):
        size=struct.unpack_from('<I',raw,pos)[0];pos+=4
        if j==f[3]:return modern_page(raw[pos:pos+size],w,h).crop(f[4:8])
        pos+=size
    raise ValueError('Sprite frame page is missing')

def encode_legacy(image):
    a=np.asarray(image.convert('RGBA')).astype(np.uint16)
    # Ordered alpha coverage keeps translucent glows without black premultiplied
    # rectangles. The old renderer supports opaque RGB565 or transparent runs.
    matrix=np.array([[0,8,2,10],[12,4,14,6],[3,11,1,9],[15,7,13,5]],dtype=np.uint16)
    threshold=np.tile(matrix,(a.shape[0]//4+1,a.shape[1]//4+1))[:a.shape[0],:a.shape[1]]*16+8
    visible=a[:,:,3]>threshold
    pixels=((a[:,:,0]>>3)<<11)|((a[:,:,1]>>2)<<5)|(a[:,:,2]>>3)
    colors=pixels.flatten();mask=visible.flatten();out=bytearray();i=0
    while i<len(colors):
        if not mask[i]:
            end=i+1
            while end<len(colors) and not mask[end] and end-i<63:end+=1
            out.append(128|(end-i));i=end;continue
        end=i+1
        while end<len(colors) and mask[end] and end-i<63:end+=1
        # Literal runs avoid the reserved C0/C1 page prefixes entirely.
        out.append(end-i);out.extend(colors[i:end].astype('<u2').tobytes());i=end
    return bytes(out)

def convert_sprite(raw):
    nf,version,npages,flags=struct.unpack_from('<4I',raw);assert version==2
    dims=[struct.unpack_from('<2I',raw,16+8*i) for i in range(npages)]
    header_end=16+8*npages+48*nf;pos=header_end;out=bytearray(raw[:header_end]);changed=False;audits=[]
    for h,w in dims:
        n=struct.unpack_from('<I',raw,pos)[0];pos+=4;page=raw[pos:pos+n];pos+=n
        if n and page[0] in (0xc0,0xc1):
            image=modern_page(page,w,h);encoded=encode_legacy(image);check=decode_page(encoded,w,h)
            # Verify alpha coverage and RGB565 quantization independently.
            a=np.asarray(image).astype(np.uint16);b=np.asarray(check)
            visible=b[:,:,3]>0
            assert np.max(np.abs(a[:,:,:3][visible].astype(int)-b[:,:,:3][visible].astype(int)),initial=0)<=7
            assert np.all(b[:,:,3][a[:,:,3]==0]==0)
            page=encoded;changed=True
            audits.append({'width':w,'height':h,'sourceMarker':raw[pos-n],'decodedPixels':w*h})
        elif n:decode_page(page,w,h)
        out.extend(struct.pack('<I',len(page)));out.extend(page)
    assert pos==len(raw)
    if changed and flags==0xffffffff:struct.pack_into('<I',out,12,2 if nf>1 else 0)
    return bytes(out),audits
