# 產生假 GPS 路線：每行「經度 緯度 節數 截圖名稱」，每秒一筆
import math,sys
LON,LAT=120.6484,24.11105   # 台中，固定測速，限速 60
def at(d): return LON, LAT-d/111320.0
def atW(d): return LON-d/(111320.0*math.cos(math.radians(LAT))), LAT   # 從西邊往東開（這支只拍往西的車＝對向）
def run(start,end,kmh,tags,phase,pos=None):
    d=start;out=[];done=set()
    while d>end:
        lo,la=(pos or at)(d);tag='-'
        for t,v in tags:
            if t not in done and d<=v: tag=phase+'_'+t;done.add(t);break
        out.append('%.6f %.6f %.2f %s'%(lo,la,kmh/1.852,tag)); d-=kmh/3.6
    return out
def wait(d,n):
    lo,la=at(d);return ['%.6f %.6f 0 -'%(lo,la)]*n
P=sys.argv[1]
if P=='A': L=run(1600,-80,50,[('1000m',995),('500m',495),('150m',148),('pass',0)],'A')
elif P=='B': L=wait(1600,3)+run(900,-80,80,[('590m',585),('190m',190),('90m',90),('pass',0)],'B')
elif P=='C': L=run(1500,-80,80,[('1000m',990),('500m',495),('150m',148),('90m',90)],'C')
elif P=='O': L=run(1600,-40,80,[('500m',495),('90m',90)],'O',atW)
elif P=='P': L=run(1600,-40,80,[('150m',148)],'P',atW)
elif P=='W': L=wait(1700,int(sys.argv[2]))
print('\n'.join(L))
