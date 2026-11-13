"""把警政署「測速執法設置點」CSV 轉成 App 用的測速點，更新 pack.json。
用法：python3 npa2pack.py NPA.csv pack.json [cams.json 輸出(選用)]
- pack.json 裡的熱點、科技執法點保留不動，只換測速照相點，版本號更新成現在時間。
- 資料看起來壞掉（太少筆）就不更新，避免把好資料蓋掉。"""
import csv,json,sys,datetime,io,re
_B={'北':0,'東北':45,'北東':45,'東':90,'東南':135,'南東':135,'南':180,'西南':225,'南西':225,'西':270,'西北':315,'北西':315}
_C='((?:[東西南北]){1,2})'
def dirdeg(d):
    """警政署「拍攝方向」→ 被拍車流的行進方向（度）；雙向、看不懂一律 -1（App 會照常提醒）。
    例：南向北/南往北/由南向北→0，北上/往北/北向→0，南下→180，東向西→270；南北雙向、南北向、雙向→-1。"""
    d=re.sub(r'[\s()（）　]','',d or '')
    if not d or '雙' in d or '双' in d or '區間' in d: return -1
    m=re.findall(_C+'[向往至到]'+_C,d)
    if m:
        v={_B.get(y,-1) for x,y in m if x!=y}
        return v.pop() if len(v)==1 and -1 not in v else -1
    if re.search('南北|北南|東西|西東',d): return -1          # 「南北向」＝雙向
    m=re.findall('[往向朝]'+_C+'|'+_C+'[向行]|([南北])[上下]',d)
    v=set()
    for x in m:
        k=x[0] or x[1] or x[2]
        v.add(_B.get(k,-1))
    return v.pop() if len(v)==1 and -1 not in v else -1
src,pack=sys.argv[1],sys.argv[2]
raw=open(src,'rb').read()
for enc in ('utf-8-sig','big5','cp950'):
    try: txt=raw.decode(enc); break
    except Exception: pass
rows=list(csv.DictReader(io.StringIO(txt)))
rows=[r for r in rows if r.get('Longitude') and r['Longitude'][:1].isdigit()]
out=[];seen=set();full=[]
for r in rows:
    try: lon=float(r['Longitude']); lat=float(r['Latitude'])
    except Exception: continue
    if not (118.0<=lon<=122.1 and 21.8<=lat<=26.7): continue
    if 25.4<lat<=26.7 and lon>119.5 and lon<=122.1:  # 本島緯度打錯成 26.x 的情況
        if abs(lat-26.645304)<0.001: lat-=2.0
        else: continue
    city=(r.get('CityName') or '').strip(); addr=(r.get('Address') or '').strip(); d=(r.get('direct') or '').strip()
    try: lim=int(float(r.get('limit') or 0))
    except Exception: lim=0
    t='區間測速' if ('區間' in d or '區間' in addr) else ('闖紅燈' if '紅燈' in addr else '固定測速(類型未標)')
    rk='國道' if city.startswith('國道') else '一般道路'
    k=(round(lon,5),round(lat,5),addr,d)
    if k in seen: continue
    seen.add(k)
    full.append([t,rk,round(lon,6),round(lat,6),city,str(lim or 50)])
    out.append([1 if t=='區間測速' else 2 if t=='闖紅燈' else 0, 1 if rk=='國道' else 0, round(lon,5), round(lat,5), lim or 50, -1 if t=='區間測速' else dirdeg(d)])
if len(out)<1000: sys.exit('資料太少（%d 筆），不更新'%len(out))
p=json.load(open(pack,encoding='utf-8'))
old=len(p.get('cams',[]))
if out==p.get('cams'): print('沒有變動',len(out)); sys.exit(0)
p['cams']=out
p['v']=(datetime.datetime.utcnow()+datetime.timedelta(hours=8)).strftime('%Y%m%d%H%M')
json.dump(p,open(pack,'w',encoding='utf-8'),ensure_ascii=False,separators=(',',':'))
if len(sys.argv)>3: json.dump(full,open(sys.argv[3],'w',encoding='utf-8'),ensure_ascii=False)
print('更新完成：測速點 %d → %d，版本 %s；有標方向 %d 筆'%(old,len(out),p['v'],sum(1 for c in out if c[5]>=0)))
import collections,os
cnt=collections.Counter((r.get('direct') or '').strip() for r in rows)
for k,v in cnt.most_common(60): print('  方向欄',repr(k),v,'→',dirdeg(k))
# 方向欄的寫法統計（檢查用）：[原文, 筆數, 轉出來的度數]
json.dump([[k,v,dirdeg(k)] for k,v in cnt.most_common()],open(os.path.join(os.path.dirname(pack) or '.','dirs.json'),'w',encoding='utf-8'),ensure_ascii=False,indent=0)
