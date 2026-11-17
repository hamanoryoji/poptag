"""Use CN057's two 11-glyph digit sets with its native money coordinates."""
from prepare_cn324_shop_batch import *

m=json.loads((DEST/'manifest.json').read_text(encoding='utf-8'))
name='Shop/S_MoneyCount'
assert name in m['geometrySkipped']
current,_=load_index(ROOT/'client/fx.idx');entries,key=load_index(DEST/'fx.idx.candidate')
source,_=load_index(CN057/'fx.idx');old=read_asset(ROOT/'client',current[name]);new=read_asset(CN057,source[name])
assert struct.unpack_from('<2I',old)==struct.unpack_from('<2I',new)==(22,2)
# The same indices represent 0..9 and separator, repeated for the second font.
# Their geometry deliberately changes with the existing single-field renderer.
for i in range(22):
    im=first_frame(CN057,name) if i==0 else None
    if im is not None:assert im.height<=24 and im.width<=16
append=(DEST/'fx.append.bin').read_bytes();before=current[name]
after={**before,'offset':m['fxOriginalBytes']+len(append),'size':len(new),'key':source[name]['key']}
entries[name]=after;(DEST/'fx.append.bin').write_bytes(append+new)
write_index(DEST/'fx.idx.candidate',entries,key)
m['fxChanges'].append({'name':name,'before':before,'after':after,'sha256':sha(new),'reason':'057 native money renderer coordinates and matching 22 digit-glyph indices'})
m['geometrySkipped'].remove(name)
(DEST/'manifest.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
print('Prepared matching CN057 digit font; other geometry mismatches remain untouched')
