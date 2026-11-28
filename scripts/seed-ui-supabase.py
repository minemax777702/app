#!/usr/bin/env python3
import os, json, urllib.request, subprocess, uuid, time, zlib, base64
URL="http://127.0.0.1:54321"
key=os.environ["CAPTURE_SERVICE_KEY"]
def request(path, data, token=key):
    req=urllib.request.Request(URL+path, json.dumps(data).encode(), {"apikey":token,"Authorization":"Bearer "+token,"Content-Type":"application/json"})
    with urllib.request.urlopen(req,timeout=20) as r: return json.load(r)
def sql(query):
    return subprocess.check_output(["psql",os.environ["CAPTURE_DB_URL"],"-XAt","-v","ON_ERROR_STOP=1","-c",query],text=True).strip()
def quote(s): return "'"+str(s).replace("'","''")+"'"
def uid(): return str(uuid.uuid4())
def user(alias,name):
    r=request("/auth/v1/admin/users",{"email":alias+"@example.test","password":"CaptureOnly-2026!","email_confirm":True,"user_metadata":{"display_name":name}})
    return str(uuid.UUID(r["id"]))
admin=user("admin","Алексей")
member=user("member","Мария")
inactive=user("inactive","Олег")
sql(f"update public.profiles set role='admin' where id='{admin}'; update public.profiles set active=false where id='{inactive}'")
for name in ("Семья","Дача","Пустая группа"):
    g=uid()
    sql(f"insert into public.groups(id,owner_id,name) values('{g}','{admin}',{quote(name)})")
    if name!="Пустая группа":
        sql(f"insert into public.group_members(group_id,user_id) values('{g}','{member}')")
        sql(f"insert into public.shopping_lists(group_id,name) values('{g}','Покупки на неделю')")
# SyncCodec v13: zigzag integers, UTF-8 strings, zlib compression.
# Keep this fixture synchronized with SyncCodec.kt.
now=int(time.time()*1000)-10000
b=bytearray([13])
def n(v):
    v=(v<<1)^(v>>63)
    while v>127: b.append((v&127)|128); v>>=7
    b.append(v)
def s(v):
    v=v.encode();n(len(v));b.extend(v)
products=[("Молоко 1,4 л",12999,False),("Хлеб ржаной",8999,False),("Яблоки",11999,True),("Яйца С0",10999,False),("Пакет",-1,False)]
ids=[uid() for _ in products]
n(2)
for u,name in ((admin,"Алексей"),(member,"Мария")): s(u);s(name);n(now)
n(len(products))
for i,(name,price,weighted) in enumerate(products):
    s(name);n(now);n(now);b.append(0);s(admin);n(1 if i!=1 else 2);s("");n(0);n(750 if weighted else -1);s(ids[i])
n(0);n(0);n(0)
n(3)
for i in range(3):
    b.append(0);s(uid());n(now-(i+1)*86400000);n(1800000);n(1);n(0);n(2)
    for name in ("Молоко 1,4 л","Хлеб ржаной"): s(name);s(admin);n(1);s("")
    n(0);s("")
n(0);n(0);n(0)
n(len(products))
for i,(name,price,weighted) in enumerate(products):
    s(ids[i]);s(name);s("");n(now);b.append(0);n(price);b.append(int(weighted))
stores=[(uid(),name) for name in ("Лента","Магнит","Пятёрочка","Красное и Белое")]
n(len(stores))
for ident,name in stores: s(ident);s(name);s(name.lower());n(now);b.append(0)
n(0)
n(len(products)*2)
for i,(_,price,_) in enumerate(products):
    for j in range(2):
        s(stores[j][0]);s("custom:"+ids[i]);n(price+j*100 if price>=0 else -1);n(now);b.append(0)
payload=base64.b64encode(zlib.compress(b,9)).decode()
sql(f"update public.list_states set revision=revision+1,payload={quote(payload)} where list_id in (select id from public.shopping_lists where owner_id='{admin}' or group_id in (select id from public.groups where owner_id='{admin}'))")
assert sql("select count(*) from public.profiles")=="3"
assert sql("select count(*) from public.groups")=="3"
print("Isolated fixture seeded")
