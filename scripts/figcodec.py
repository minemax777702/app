"""Kiwi binary reader/writer for the source FIG's embedded schema."""
import ctypes as C
import struct

lib=C.CDLL('libzstd.so.1')
lib.ZSTD_getFrameContentSize.argtypes=[C.c_void_p,C.c_size_t];lib.ZSTD_getFrameContentSize.restype=C.c_ulonglong
lib.ZSTD_decompress.argtypes=[C.c_void_p,C.c_size_t,C.c_void_p,C.c_size_t];lib.ZSTD_decompress.restype=C.c_size_t
lib.ZSTD_compressBound.argtypes=[C.c_size_t];lib.ZSTD_compressBound.restype=C.c_size_t
lib.ZSTD_compress.argtypes=[C.c_void_p,C.c_size_t,C.c_void_p,C.c_size_t,C.c_int];lib.ZSTD_compress.restype=C.c_size_t
lib.ZSTD_isError.argtypes=[C.c_size_t];lib.ZSTD_isError.restype=C.c_uint

def decompress(b):
    n=lib.ZSTD_getFrameContentSize(b,len(b))
    if n>=200_000_000:raise ValueError('Unsupported zstd frame size')
    out=C.create_string_buffer(n);r=lib.ZSTD_decompress(out,n,b,len(b))
    if r!=n:raise ValueError('Invalid zstd frame')
    return out.raw

def compress(b):
    n=lib.ZSTD_compressBound(len(b));o=C.create_string_buffer(n);r=lib.ZSTD_compress(o,n,b,len(b),8)
    if lib.ZSTD_isError(r):raise ValueError('Compression failed')
    return o.raw[:r]

class R:
    def __init__(self,b):self.b=b;self.i=0
    def take(self,n):
        x=self.b[self.i:self.i+n];self.i+=n
        if len(x)!=n:raise ValueError('Truncated Kiwi data')
        return x
    def byte(self):return self.take(1)[0]
    def uint(self):
        v=0
        for i in range(9):
            x=self.byte();v|=(x if i==8 else x&127)<<(7*i)
            if x<128 or i==8:return v
    def int(self):
        v=self.uint();return -(v//2)-1 if v&1 else v//2
    def string(self):
        j=self.b.index(0,self.i);return self.take(j-self.i+1)[:-1].decode('utf8')
    def float(self):
        x=self.byte()
        if not x:return 0.0
        b=int.from_bytes(bytes([x])+self.take(3),'little');b=((b<<23)|(b>>9))&0xffffffff
        return struct.unpack('<f',struct.pack('<I',b))[0]

class W:
    def __init__(self):self.b=bytearray()
    def take(self,b):self.b.extend(b)
    def byte(self,v):self.b.append(v)
    def uint(self,v):
        for i in range(8):
            if v<128:self.byte(v);return
            self.byte((v&127)|128);v>>=7
        self.byte(v)
    def int(self,v):self.uint(v*2 if v>=0 else -v*2-1)
    def string(self,v):self.take(v.encode()+b'\0')
    def float(self,v):
        b=struct.unpack('<I',struct.pack('<f',v))[0];b=((b>>23)|(b<<9))&0xffffffff
        if b&255:self.take(struct.pack('<I',b))
        else:self.byte(0)

class Codec:
    def __init__(self,s):
        r=R(s);self.ds=[]
        for i in range(r.uint()):
            name=r.string();kind=r.byte();fields=[]
            for j in range(r.uint()):fields.append((r.string(),r.int(),bool(r.byte()&1),r.uint()))
            self.ds.append((name,kind,fields))
        if r.i!=len(s):raise ValueError('Invalid embedded schema')
        self.byname={d[0]:i for i,d in enumerate(self.ds)}
        self.byid=[{f[3]:f for f in d[2]} for d in self.ds]
    def decode(self,r,t):
        if t<0:return [r.byte,r.byte,r.int,r.uint,r.float,r.string,r.int,r.uint][~t]()
        name,kind,fields=self.ds[t]
        if kind==0:return r.uint()
        d={}
        def field(f):
            n,t,a,v=f
            if a:
                length=r.uint();d[n]=r.take(length) if t==-2 else [self.decode(r,t) for i in range(length)]
            else:d[n]=self.decode(r,t)
        if kind==1:
            for f in fields:field(f)
        else:
            while (v:=r.uint()):field(self.byid[t][v])
        return d
    def encode(self,w,t,d):
        if t<0:[w.byte,w.byte,w.int,w.uint,w.float,w.string,w.int,w.uint][~t](d);return
        name,kind,fields=self.ds[t]
        if kind==0:w.uint(d);return
        byname={f[0]:f for f in fields}
        for n in (d if kind==2 else byname):
            _,ft,a,v=byname[n];val=d[n]
            if kind==2:w.uint(v)
            if a:
                w.uint(len(val))
                if ft==-2:w.take(val)
                else:
                    for item in val:self.encode(w,ft,item)
            else:self.encode(w,ft,val)
        if kind==2:w.uint(0)
    def enum(self,name,v):return next(f[3] for f in self.ds[self.byname[name]][2] if f[0]==v)
