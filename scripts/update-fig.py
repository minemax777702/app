#!/usr/bin/env python3
"""Create verified pixel-reference pages from a complete Android handoff.
Original editable nodes are retained; screenshot pages are deliberately raster.
"""
import argparse
import copy
import hashlib
import json
import pathlib
import struct
import zipfile
import zlib
from figcodec import Codec, R, W, compress, decompress

def decode(canvas):
    if canvas[:8] != b'fig-kiwi':
        raise ValueError('Unsupported FIG signature')
    n = struct.unpack_from('<I', canvas, 12)[0]
    size = struct.unpack_from('<I', canvas, 16+n)[0]
    if 20+n+size != len(canvas):
        raise ValueError('Unexpected FIG block layout')
    codec = Codec(zlib.decompress(canvas[16:16+n], -15))
    raw = decompress(canvas[20+n:])
    reader = R(raw)
    message = codec.decode(reader, codec.byname['Message'])
    writer = W()
    codec.encode(writer, codec.byname['Message'], message)
    if reader.i != len(raw) or bytes(writer.b) != raw:
        raise ValueError('Source FIG cannot be losslessly decoded/re-encoded')
    return codec, message, n

def digest(data):
    return hashlib.sha256(data).hexdigest()

def png_size(data):
    if data[:16] != b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR':
        raise ValueError('Invalid PNG')
    w, h = struct.unpack_from('>II', data, 16)
    if not (0 < w <= 16384 and 0 < h <= 16384):
        raise ValueError('Invalid PNG dimensions')
    return w, h

def matrix(x=0, y=0):
    return dict(m00=1., m01=0., m02=float(x), m10=0., m11=1., m12=float(y))

def rect(w, h):
    return b''.join(bytes([cmd])+struct.pack('<ff', x, y) for cmd,x,y in [(1,0,0),(2,w,0),(2,w,h),(2,0,h),(2,0,0)])+b'\0'

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--source', required=True, type=pathlib.Path)
    p.add_argument('--handoff', required=True, type=pathlib.Path)
    p.add_argument('--output', required=True, type=pathlib.Path)
    args = p.parse_args()
    manifest = json.loads((args.handoff/'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('coverage_complete') is not True or manifest.get('auth_complete') is not True:
        raise ValueError('Refusing incomplete capture')
    records = manifest['screens']
    keys = {(s['theme'],s['screen']) for s in records}
    themes = ('light','dark')
    if len(keys) != len(records) or any(s['status'] != 'captured' or s['theme'] not in themes for s in records):
        raise ValueError('Invalid capture inventory')
    if {s['screen'] for s in records if s['theme']=='light'} != {s['screen'] for s in records if s['theme']=='dark'} or not records:
        raise ValueError('Theme inventories differ')
    with zipfile.ZipFile(args.source) as source:
        canvas = source.read('canvas.fig')
        codec, msg, n = decode(canvas)
        nodes = msg['nodeChanges']
        old_nodes = copy.deepcopy(nodes)
        old_blobs = copy.deepcopy(msg['blobs'])
        session = max(x['guid']['sessionID'] for x in nodes)+1
        seq = 0
        def guid():
            nonlocal seq
            seq += 1
            return dict(sessionID=session,localID=seq)
        document, = [x['guid'] for x in nodes if x.get('type')==codec.enum('NodeType','DOCUMENT')]
        images = {}
        checks = []
        geometries = {}
        for theme, pos in zip(themes, ('~#','~$')):
            page = guid()
            shade = .92 if theme=='light' else .19
            nodes.append(dict(guid=page,phase=0,parentIndex=dict(guid=document,position=pos),type=codec.enum('NodeType','CANVAS'),name='CI · '+theme.title()+' · '+str(manifest['version']),visible=1,opacity=1.,transform=matrix(),backgroundOpacity=1.,backgroundColor=dict(r=shade,g=shade,b=shade,a=1.),isPageDivider=0,backgroundEnabled=1))
            x=y=row_height=0
            for i,s in enumerate(r for r in records if r['theme']==theme):
                path=(args.handoff/s['screenshot']).resolve()
                if not path.is_relative_to(args.handoff.resolve()):
                    raise ValueError('Unsafe screenshot path')
                raw=path.read_bytes()
                if digest(raw)!=s['sha256']:
                    raise ValueError('Screenshot digest mismatch: '+s['screen'])
                w,h=png_size(raw)
                if i%5==0 and i:
                    x=0; y+=row_height+120; row_height=0
                if (w,h) not in geometries:
                    geometries[w,h]=len(msg['blobs']);msg['blobs'].append(dict(bytes=rect(w,h)))
                sha=hashlib.sha1(raw).digest()
                if sha.hex() not in images:
                    images[sha.hex()]=(len(msg['blobs']),raw)
                    msg['blobs'].append(dict(bytes=raw))
                blob,_=images[sha.hex()]
                g=guid()
                paint=dict(type=codec.enum('PaintType','IMAGE'),opacity=1.,visible=1,blendMode=1,image=dict(hash=sha,name=s['screen']+'.png',dataBlob=blob),imageScaleMode=2,originalImageWidth=w,originalImageHeight=h)
                nodes.append(dict(guid=g,phase=0,parentIndex=dict(guid=page,position=f'{i+1:06d}'),type=codec.enum('NodeType','FRAME'),name=f'{i+1:02d} · {s["screen"]}',visible=1,opacity=1.,size=dict(x=float(w),y=float(h)),transform=matrix(x,y),strokeWeight=0.,strokeAlign=1,strokeJoin=0,fillPaints=[paint],fillGeometry=[dict(windingRule=0,commandsBlob=geometries[w,h],styleID=0)],frameMaskDisabled=0,description=f'CI raster reference · {manifest["commit"]} · {theme}/{s["screen"]} · {w}×{h} px'))
                checks.append(dict(guid=g,theme=theme,screen=s['screen'],sha256=s['sha256'],width=w,height=h,image=sha.hex()))
                x+=w+120;row_height=max(row_height,h)
        writer=W();codec.encode(writer,codec.byname['Message'],msg)
        comp=compress(bytes(writer.b))
        meta=json.loads(source.read('meta.json'));meta['file_name']='Shopping list · '+str(manifest['version'])+' · CI references'
        args.output.parent.mkdir(parents=True,exist_ok=True)
        tmp=args.output.with_suffix('.tmp')
        with zipfile.ZipFile(tmp,'w',compression=zipfile.ZIP_STORED) as out:
            for name in source.namelist():
                data=source.read(name)
                if name=='canvas.fig':data=canvas[:16+n]+struct.pack('<I',len(comp))+comp
                elif name=='meta.json':data=json.dumps(meta,ensure_ascii=False).encode()
                out.writestr(name,data)
            for sha,(_,raw) in images.items():
                name='images/'+sha
                if name in source.namelist():
                    if source.read(name)!=raw:raise ValueError('Image hash collision')
                else:out.writestr(name,raw)
    with zipfile.ZipFile(tmp) as out:
        if out.testzip():raise ValueError('Invalid output archive')
        _, result, _=decode(out.read('canvas.fig'))
        if result['nodeChanges'][:len(old_nodes)]!=old_nodes or result['blobs'][:len(old_blobs)]!=old_blobs:
            raise ValueError('Original nodes or blobs modified')
        index={(n['guid']['sessionID'],n['guid']['localID']):n for n in result['nodeChanges']}
        if len(index)!=len(result['nodeChanges']):raise ValueError('Duplicate node GUID')
        for c in checks:
            g=c['guid'];f=index[g['sessionID'],g['localID']];im=f['fillPaints'][0]['image']
            raw=out.read('images/'+c['image'])
            if digest(raw)!=c['sha256'] or result['blobs'][im['dataBlob']]['bytes']!=raw or f['size']!=dict(x=float(c['width']),y=float(c['height'])):
                raise ValueError('Pixel-reference validation failed: '+c['screen'])
    tmp.replace(args.output)
    report=dict(commit=manifest['commit'],version=manifest['version'],source_sha256=digest(args.source.read_bytes()),output_sha256=digest(args.output.read_bytes()),source_nodes=len(old_nodes),reference_count=len(checks),source_nodes_preserved=True,png_bytes_preserved=True,figma_import_verified=False,references=checks)
    args.output.with_suffix('.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'Validated {len(checks)} unchanged PNG references and {len(old_nodes)} original nodes')

if __name__=='__main__':
    main()
