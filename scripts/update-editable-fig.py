#!/usr/bin/env python3
"""Native, image-free reconstruction of captured Android UI. Not a parity certificate."""
import argparse, collections, copy, hashlib, importlib.util, json, pathlib, re, struct, zipfile
import xml.etree.ElementTree as ET
import cv2
import numpy as np
from figcodec import W as Writer, compress
spec=importlib.util.spec_from_file_location('base',pathlib.Path(__file__).with_name('update-fig.py'))
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)

def paint(c):
    r,g,b=[float(v)/255 for v in c]
    return dict(type=0,color=dict(r=r,g=g,b=b,a=1.),opacity=1.,visible=1,blendMode=1)

def palette(im,limit=14):
    a=im.astype(np.int32); q=(a[:,:,0]>>3)*1024+(a[:,:,1]>>3)*32+(a[:,:,2]>>3);counts=np.bincount(q.ravel(),minlength=32768)
    ids=np.argsort(counts)[::-1][:limit];flat=im.reshape(-1,3);q=q.ravel();cs=[]
    for k in ids[counts[ids]>4]:
        vals=flat[q==k];cs.append(np.median(vals,axis=0).astype(np.uint8))
    return np.array(cs,dtype=np.uint8)

def index_colors(im,colors):
    a=im.astype(np.int32);best=np.full(im.shape[:2],2**31-1,np.int32);idx=np.zeros(im.shape[:2],np.uint8)
    for i,c in enumerate(colors):
        d=np.sum((a-c.astype(np.int32))**2,axis=2);ok=d<best;idx[ok]=i;best[ok]=d[ok]
    return idx

def vector(loops):
    vs=[];ss=[];rs=[];cmd=bytearray()
    for loop in loops:
        start=len(vs);first=len(ss);vs.extend(loop)
        for i,(x,y) in enumerate(loop):
            cmd.extend(bytes([1 if i==0 else 2])+struct.pack('<ff',x,y));ss.append((start+i,start+(i+1)%len(loop)))
        cmd.append(0);rs.append(list(range(first,len(ss))))
    net=bytearray(struct.pack('<III',len(vs),len(ss),1))
    for x,y in vs:net.extend(struct.pack('<Iff',0,x,y))
    for a,b in ss:net.extend(struct.pack('<IIffIff',0,a,0.,0.,b,0.,0.))
    net.extend(struct.pack('<II',0,len(rs)))
    for r in rs:net.extend(struct.pack('<I',len(r)));net.extend(struct.pack('<'+'I'*len(r),*r))
    return bytes(net),bytes(cmd)

def texts(im,xml,density):
    clean=im.copy();out=[];seen=set();H,W=im.shape[:2];missing=[]
    for n in ET.fromstring(xml).iter('node'):
        t=n.get('text','').strip();cl=n.get('class','');b=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
        if not t or n.get('package')!='com.mok.buylist.capture' or n.get('password')=='true' or len(b)!=4 or cl not in ('android.widget.TextView','android.widget.EditText','android.widget.Button','android.view.View'):continue
        x,y,x2,y2=b;x=max(0,x);y=max(0,y);x2=min(W,x2);y2=min(H,y2)
        if x2-x<3 or y2-y<3 or (t,x,y,x2,y2) in seen:continue
        seen.add((t,x,y,x2,y2));roi=im[y:y2,x:x2];cs=palette(roi,8)
        if len(cs)<2:
            missing.append(t);continue
        bg=cs[0];fg=next((c for c in cs[1:] if np.linalg.norm(c.astype(float)-bg)>28),None)
        if fg is None:
            missing.append(t);continue
        distance=np.linalg.norm(roi.astype(np.int16)-bg.astype(np.int16),axis=2);mask=(distance>max(18.,np.linalg.norm(fg.astype(float)-bg)*.35)).astype(np.uint8)
        if mask.mean()>.45 or mask.mean()<.001:
            missing.append(t);continue
        yy,xx=np.nonzero(mask);left,top,right,bottom=xx.min(),yy.min(),xx.max()+1,yy.max()+1;lines=max(1,t.count('\n')+1)
        size=min((10,11,12,13,14,15,16,17,18,20,22,24,26,27,28,30,32,36),key=lambda v:abs(v*density*.78-(bottom-top)/lines))
        m=cv2.dilate(mask,np.ones((3,3),np.uint8));clean[y:y2,x:x2][m>0]=bg
        out.append((t,(x+left)/density,y/density,max((x2-x-left)/density,(right-left)/density+2),(y2-y)/density,size,fg,'SemiBold' if size>=17 or cl=='android.widget.Button' else 'Regular'))
    return clean,out,missing

def main():
    p=argparse.ArgumentParser();p.add_argument('--source',type=pathlib.Path,required=True);p.add_argument('--handoff',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();m=json.loads((a.handoff/'manifest.json').read_text())
    if not m.get('coverage_complete') or not m.get('auth_complete'):raise ValueError('Incomplete capture')
    density=int(re.search(r'\d+',m['density']).group())/160;records=m['screens'];assert all(r['status']=='captured' for r in records);assert len({(r['theme'],r['screen']) for r in records})==len(records)
    assert records and {r['screen'] for r in records if r['theme']=='light'}=={r['screen'] for r in records if r['theme']=='dark'}
    with zipfile.ZipFile(a.source) as z:
        canvas=z.read('canvas.fig');codec,msg,n=base.decode(canvas);nodes=msg['nodeChanges'];old=copy.deepcopy(nodes);oldblobs=copy.deepcopy(msg['blobs']);session=max(k['guid']['sessionID'] for k in nodes)+1;seq=0;counts=collections.Counter();positions=collections.Counter();reports=[]
        def blob(b):
            k=len(msg['blobs']);msg['blobs'].append(dict(bytes=b));return k
        def node(typ,name,parent,x=0.,y=0.,w=None,h=None,**kw):
            nonlocal seq;seq+=1;g=dict(sessionID=session,localID=seq);key=(parent['sessionID'],parent['localID']);positions[key]+=1;q=dict(guid=g,phase=0,parentIndex=dict(guid=parent,position=f'~{positions[key]:08d}'),type=codec.enum('NodeType',typ),name=name,visible=1,opacity=1.,transform=base.matrix(x,y))
            if w is not None:q['size']=dict(x=float(w),y=float(h))
            q.update(kw);nodes.append(q);counts[typ]+=1;return g
        doc=next(x['guid'] for x in nodes if x.get('type')==codec.enum('NodeType','DOCUMENT'))
        for theme in ('light','dark'):
            shade=.91 if theme=='light' else .18;page=node('CANVAS',f'Editable · {theme.title()} · {m["version"]}',doc,backgroundOpacity=1.,backgroundEnabled=1,backgroundColor=dict(r=shade,g=shade,b=shade,a=1.))
            for i,s in enumerate(r for r in records if r['theme']==theme):
                raw=(a.handoff/s['screenshot']).read_bytes();assert hashlib.sha256(raw).hexdigest()==s['sha256'];im=cv2.imdecode(np.frombuffer(raw,np.uint8),cv2.IMREAD_COLOR)[:,:,::-1].copy();H,W=im.shape[:2];w,h=W/density,H/density
                clean,ts,missing=texts(im,(a.handoff/s['hierarchy']).read_bytes(),density);small=cv2.resize(clean,(W//3,H//3),interpolation=cv2.INTER_AREA);cs=palette(small);idx=index_colors(small,cs);scale=3/density
                frame=node('FRAME',s['screen'],page,(i%5)*(w+40),(i//5)*(h+40),w,h,fillPaints=[paint(cs[0])],fillGeometry=[dict(windingRule=0,commandsBlob=blob(base.rect(w,h)),styleID=0)],frameMaskDisabled=0,description='Native reconstruction; pixel parity not verified')
                group=node('FRAME','Поверхности · иконки · графика',frame,0,0,w,h,frameMaskDisabled=1);count=0
                for ci,color in enumerate(cs[1:],1):
                    contours,tree=cv2.findContours((idx==ci).astype(np.uint8),cv2.RETR_CCOMP,cv2.CHAIN_APPROX_SIMPLE)
                    if tree is None:continue
                    tree=tree[0]
                    for j,c in enumerate(contours):
                        if tree[j][3]!=-1 or cv2.contourArea(c)<5:continue
                        x,y,bw,bh=cv2.boundingRect(c)
                        if min(bw,bh)<2:continue
                        loops=[];child=j
                        while child!=-1:
                            pts=cv2.approxPolyDP(contours[child],.55,True).reshape(-1,2)
                            if len(pts)>=3:loops.append([((float(px)-x)*scale,(float(py)-y)*scale) for px,py in pts])
                            child=tree[child][2] if child==j else tree[child][0]
                        if not loops:continue
                        nw,cmd=vector(loops);node('VECTOR',('Поверхность' if bw*bh>500 else 'Контур')+f' {count+1}',group,x*scale,y*scale,bw*scale,bh*scale,fillPaints=[paint(color)],strokeWeight=0.,fillGeometry=[dict(windingRule=1,commandsBlob=blob(cmd),styleID=0)],vectorData=dict(vectorNetworkBlob=blob(nw),normalizedSize=dict(x=bw*scale,y=bh*scale),styleOverrideTable=[]));count+=1
                group=node('FRAME','Редактируемый текст',frame,0,0,w,h,frameMaskDisabled=1)
                for t,x,y,tw,th,size,c,weight in ts:node('TEXT',t[:80],group,x,y,tw,th,fontName=dict(family='Manrope',style=weight,postscript='Manrope-'+weight),fontSize=float(size),textData=dict(characters=t),textAutoResize=0,textAlignHorizontal=0,textAlignVertical=1,lineHeight=dict(value=100.,units=2),fillPaints=[paint(c)],strokeWeight=0.)
                reports.append(dict(theme=theme,screen=s['screen'],texts=len(ts),vectors=count,unresolved_text=missing));print(theme,s['screen'],len(ts),count,flush=True)
        writer=Writer();codec.encode(writer,codec.byname['Message'],msg);comp=compress(bytes(writer.b));a.output.parent.mkdir(parents=True,exist_ok=True);tmp=a.output.with_suffix('.tmp')
        with zipfile.ZipFile(tmp,'w',compression=zipfile.ZIP_DEFLATED) as out:
            for name in z.namelist():
                data=z.read(name)
                if name=='canvas.fig':data=canvas[:16+n]+struct.pack('<I',len(comp))+comp
                elif name=='meta.json':meta=json.loads(data);meta['file_name']='Shopping list · Editable · '+m['version'];data=json.dumps(meta,ensure_ascii=False).encode()
                out.writestr(name,data)
    with zipfile.ZipFile(tmp) as z:
        assert z.testzip() is None;_,r,_=base.decode(z.read('canvas.fig'));assert r['nodeChanges'][:len(old)]==old and r['blobs'][:len(oldblobs)]==oldblobs;assert all(p.get('type')!=5 for x in r['nodeChanges'][len(old):] for p in x.get('fillPaints',[]));assert len(reports)==len(records)
        ids={(x['guid']['sessionID'],x['guid']['localID']) for x in r['nodeChanges']};assert len(ids)==len(r['nodeChanges'])
    tmp.replace(a.output);a.output.with_suffix('.json').write_text(json.dumps(dict(version=m['version'],commit=m['commit'],screens=reports,new_nodes=dict(counts),original_nodes=len(old),raster_fills=0,figma_import_verified=False,pixel_parity_verified=False),ensure_ascii=False,indent=2))
if __name__=='__main__':main()
