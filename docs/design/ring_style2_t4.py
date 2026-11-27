import math, sys, os
from xml.sax.saxutils import escape
OUT = sys.argv[1]
D=320.0; SW=D*0.068; R=D/2-SW/2; FS=D*0.245; CAP=FS*0.72
A3=D*0.3125
FG="#FFFFFF"; TRACK="#FFFFFF"; TRACK_OP=0.3; RED="#FF3B30"
FONT="vivo Sans, HarmonyOS Sans SC, Roboto, Arial, sans-serif"
CN="HarmonyOS Sans SC, vivo Sans, PingFang SC, Microsoft YaHei, sans-serif"

def pt(cx,cy,r,a):
    t=math.radians(a); return cx+r*math.cos(t), cy+r*math.sin(t)
def arcp(cx,cy,r,a0,a1):
    x0,y0=pt(cx,cy,r,a0); x1,y1=pt(cx,cy,r,a1)
    return "M%.2f,%.2f A%.2f,%.2f 0 %d 1 %.2f,%.2f"%(x0,y0,r,r,1 if a1-a0>180 else 0,x1,y1)
def poly(p,x,y,w,h):
    s="M%.2f,%.2f"%(x+p[0]*w,y+p[1]*h)
    for i in range(2,len(p),2): s+=" L%.2f,%.2f"%(x+p[i]*w,y+p[i+1]*h)
    return s+" Z"
BOLT=[0.62,0,0.05,0.58,0.44,0.58,0.36,1,0.95,0.40,0.56,0.40]
PLANE=[0.50,0.00,0.57,0.07,0.57,0.37,0.98,0.60,0.98,0.71,0.57,0.57,0.57,0.80,0.71,0.90,0.71,0.98,0.50,0.92,
       0.29,0.98,0.29,0.90,0.43,0.80,0.43,0.57,0.02,0.71,0.02,0.60,0.43,0.37,0.43,0.07]

# 第二款（t2）：电量环为第一层（右侧 3 点偏下起，顺时针经底部到左上），卡 1 / 卡 2 为第二 / 三层信号环（底部到左上，每环 4 段）
# 右上方开口放 Wi-Fi / 制式 / 飞机，顶部为电量数字
BAT0, BAT1 = 15.0, 230.0          # 电量环起止角（度，0 = 3 点方向，顺时针）
SIM0, SIM1 = 84.0, 219.0          # 信号环起止角（t2 手改：整体逆时针转 11°）
SSW = SW*0.85                     # 信号环线宽
RGAP = SW*0.35                    # 环与环之间的空隙
R1 = R - SW/2 - RGAP - SSW/2      # 卡 1 信号环半径（第二层）
R2 = R1 - SSW - RGAP              # 卡 2 信号环半径（第三层）
SEG_GAP = SSW*0.6                 # 段与段之间的可见空隙
ICX, ICY = D*0.152, -D*0.026      # 主图标中心相对圆心的偏移（t2 手改：左移、下移）
WA = D*0.44                       # 主 Wi-Fi 大小（t2 手改：放大约 1.18 倍）
W2X, W2Y = D*0.35, -D*0.33        # 副 Wi-Fi 中心：右上方电量环开口处（约 317°，压在环线上）
WA2 = D*0.15                      # 副 Wi-Fi 大小
TYX = D*0.126                     # 4G / 5G 中心相对圆心的水平偏移（t3 手改：左移）
TYPE_K, TYPE_SX = 1.75, 0.92      # 4G / 5G 字号（× 电量数字字号）与横向压缩（t3 手改：放大约 1.5 倍）
PLX, PLY = D*0.029, -D*0.032      # 飞行模式（未连 Wi-Fi）飞机中心：圆环中间
PLW, PLH = D*0.535, D*0.48        # 飞机宽、高（t3 手改：放大）
PSX, PSY, PS = -D*0.165, D*0.171, D*0.24   # 飞行模式 + Wi-Fi 时的小飞机：圆环左下内侧

def seg_angles(r):
    g = math.degrees((SEG_GAP + SSW)/r)   # 圆头会各伸出 SSW/2，所以空隙要加一个线宽
    s = (SIM1 - SIM0 - 3*g)/4
    return [(SIM0 + i*(s+g), SIM0 + i*(s+g) + s) for i in range(4)]

def wifi(o,u,name,ix,iy,size,n):
    ws=size*0.18; a1=size*0.34; a2=size*0.66-ws/2; a3c=size-ws/2
    oy=iy+size*0.5
    p0=pt(ix,oy,a1,225); p1=pt(ix,oy,a1,315)
    col=lambda k:(FG,1) if n>=k else (TRACK,TRACK_OP)
    c,op=col(1)
    o.append('<path id="%s-%s-1" d="M%.2f,%.2f L%.2f,%.2f A%.2f,%.2f 0 0 1 %.2f,%.2f Z" fill="%s" fill-opacity="%s"/>'%(u,name,ix,oy,p0[0],p0[1],a1,a1,p1[0],p1[1],c,op))
    for k,rr in ((2,a2),(3,a3c)):
        c,op=col(k)
        o.append('<path id="%s-%s-%d" d="%s" fill="none" stroke="%s" stroke-opacity="%s" stroke-width="%.2f" stroke-linecap="round"/>'%(u,name,k,arcp(ix,oy,rr,225,315),c,op,ws))

def ring(cx,cy,st,u):
    o=[]; lv=st['level']; num=str(lv); numW=len(num)*FS*0.6
    bh=CAP*1.2; bw=bh*0.6; bg=CAP*0.16
    bolts=(bw*1.55 if st.get('quick') else bw) if st.get('charging') else 0
    gw=numW+(bg+bolts if st.get('charging') else 0)
    lit=st.get('lit',FG)
    # 第一层：电量环
    o.append('<g id="%s-battery">'%u)
    o.append('<path id="%s-battery-track" d="%s" fill="none" stroke="%s" stroke-opacity="%s" stroke-width="%.2f" stroke-linecap="round"/>'%(u,arcp(cx,cy,R,BAT0,BAT1),TRACK,TRACK_OP,SW))
    if lv>0:
        o.append('<path id="%s-battery-level" d="%s" fill="none" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>'%(u,arcp(cx,cy,R,BAT0,BAT0+(BAT1-BAT0)*min(1,lv/100.0)),lit,SW))
    o.append('</g>')
    # 顶部电量数字 + 闪电
    x=cx-gw/2; base=cy-R+CAP/2
    o.append('<g id="%s-number">'%u)
    o.append('<text id="%s-number-text" x="%.2f" y="%.2f" font-family="%s" font-weight="700" font-size="%.2f" fill="%s" textLength="%.2f" lengthAdjust="spacingAndGlyphs">%s</text>'%(u,x,base,FONT,FS,FG,numW,num))
    if st.get('charging'):
        bx=x+numW+bg; by=cy-R-bh/2
        o.append('<path id="%s-bolt-1" d="%s" fill="%s"/>'%(u,poly(BOLT,bx,by,bw,bh),FG))
        if st.get('quick'): o.append('<path id="%s-bolt-2" d="%s" fill="%s"/>'%(u,poly(BOLT,bx+bw*0.55,by,bw,bh),FG))
    o.append('</g>')
    # 右上图标：Wi-Fi / 制式 / 飞机
    ix, iy = cx+ICX, cy+ICY
    o.append('<g id="%s-center">'%u)
    if st['net']=='wifi':
        wifi(o,u,'wifi',ix,iy,WA,st.get('arcs',3))
    elif st['net']=='cell':
        f=FS*TYPE_K; tx=cx+TYX
        o.append('<text id="%s-type" x="%.2f" y="%.2f" text-anchor="middle" font-family="%s" font-weight="700" font-size="%.2f" fill="%s" transform="translate(%.2f 0) scale(%.3f 1) translate(%.2f 0)">%s</text>'%(u,tx,iy+f*0.36,FONT,f,FG,tx,TYPE_SX,-tx,st['ctype']))
    elif st.get('airplane'):
        px,py=cx+PLX,cy+PLY
        o.append('<path id="%s-airplane" d="%s" fill="%s"/>'%(u,poly(PLANE,px-PLW/2,py-PLH/2,PLW,PLH),FG))
    o.append('</g>')
    if st.get('wifi2'):
        o.append('<g id="%s-wifi2">'%u)
        wifi(o,u,'wifi2',cx+W2X,cy+W2Y,WA2,st['wifi2'])
        o.append('</g>')
    # 第二 / 三层：卡 1 / 卡 2 信号环；飞行模式时隐藏，连着 Wi-Fi 时小飞机放在信号环的位置
    if st.get('airplane'):
        if st['net']=='wifi':
            s=PS; px,py=cx+PSX,cy+PSY
            o.append('<path id="%s-airplane-small" d="%s" fill="%s"/>'%(u,poly(PLANE,px-s/2,py-s/2,s,s),FG))
    else:
        for si,lvl in enumerate(st.get('sims',[])[:2]):
            rr = R1 if si==0 else R2
            o.append('<g id="%s-sim%d-ring">'%(u,si+1))
            for k,(a0,a1_) in enumerate(seg_angles(rr)):
                on = lvl is not None and k<lvl
                o.append('<path id="%s-sim%d-seg%d" d="%s" fill="none" stroke="%s" stroke-opacity="%s" stroke-width="%.2f" stroke-linecap="round"/>'%(u,si+1,k+1,arcp(cx,cy,rr,a0,a1_),FG if on else TRACK,1 if on else TRACK_OP,SSW))
            o.append('</g>')
    return "\n".join(o)

def label(x,y,t,size=22,anchor="start",fill="#1C1C20",w=500,op=1):
    return '<text x="%s" y="%s" font-family="%s" font-size="%s" font-weight="%s" fill="%s" fill-opacity="%s" text-anchor="%s">%s</text>'%(x,y,CN,size,w,fill,op,anchor,escape(t))

STATES=[
 ("a","双 Wi-Fi · 双卡 · 80%",dict(level=80,net='wifi',arcs=3,wifi2=2,sims=[4,2])),
 ("b","快充中 · Wi-Fi 两格 · 单卡",dict(level=46,charging=True,quick=True,net='wifi',arcs=2,sims=[3])),
 ("c","移动数据 5G · 双卡",dict(level=62,net='cell',ctype='5G',sims=[4,3])),
 ("d","飞行模式（未连 Wi-Fi）",dict(level=100,net='none',airplane=True)),
 ("e","飞行模式 + Wi-Fi",dict(level=35,net='wifi',arcs=3,airplane=True)),
 ("f","低电量 · 无网络（开启电量着色）",dict(level=15,net='none',sims=[None,None],lit=RED)),
]

W,H=1960,1320
S=['<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" width="%d" height="%d" viewBox="0 0 %d %d">'%(W,H,W,H),
   '<defs><linearGradient id="wall" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7C97CF"/><stop offset="1" stop-color="#4E6CAB"/></linearGradient></defs>',
   '<rect id="page" width="%d" height="%d" fill="#F4F4F7"/>'%(W,H),
   label(60,78,"状态栏圆环 · 第二款样式 设计草图（t4）",40,w=700),
   label(60,118,"三层圆环：电量环 / 卡 1 信号环 / 卡 2 信号环 · 主 Wi-Fi 居中偏右 · 副 Wi-Fi 压在右上开口 · 顶部电量数字（按 t3 手改结果整理）",22,op=0.6)]

S.append('<g id="layer-construction" inkscape:groupmode="layer" inkscape:label="结构图">')
bx,by,bw,bh=60,160,740,760
S.append('<rect x="%d" y="%d" width="%d" height="%d" rx="36" fill="url(#wall)"/>'%(bx,by,bw,bh))
k=1.45; cx0,cy0=bx+bw/2,by+bh/2+10
S.append('<g id="construction-ring" transform="translate(%s %s) scale(%s) translate(-200 -200)">'%(cx0,cy0,k))
S.append(ring(200,200,dict(level=80,net='wifi',arcs=3,wifi2=3,sims=[4,2],charging=True),"main"))
S.append('</g></g>')
S.append('<g id="guides" inkscape:groupmode="layer" inkscape:label="辅助线（可隐藏）" fill="none" stroke="#FFE066" stroke-width="2" stroke-dasharray="8 8">')
for rr in (R,R1,R2):
    S.append('<circle cx="%s" cy="%s" r="%.1f"/>'%(cx0,cy0,rr*k))
for a in (BAT0,SIM0,SIM1,BAT1):
    x2,y2=pt(cx0,cy0,(R+SW)*k,a)
    S.append('<line x1="%s" y1="%s" x2="%.1f" y2="%.1f" stroke-opacity="0.7"/>'%(cx0,cy0,x2,y2))
S.append('<circle cx="%.1f" cy="%.1f" r="%.1f" stroke-opacity="0.5"/>'%(cx0+ICX*k,cy0+ICY*k,WA*0.62*k))
S.append('<circle cx="%.1f" cy="%.1f" r="%.1f" stroke-opacity="0.5"/>'%(cx0+W2X*k,cy0+W2Y*k,WA2*0.62*k))
S.append('</g>')
S.append('<g id="construction-notes">')
gl=lambda x,y,t: label(x,y,t,20,"start","#FFE066",600)
S.append(gl(bx+24,by+44,"① 第一层电量环：15° 起顺时针经底部到 230°，从右端开始填充"))
S.append(gl(bx+24,by+74,"② 顶部电量数字居中，充电时右侧加闪电（快充双闪电）"))
S.append(gl(bx+24,by+bh-80,"③ 第二层卡 1、第三层卡 2：84°~219°，每环 4 段，自下而上 1→4 格"))
S.append(gl(bx+24,by+bh-50,"④ 主 Wi-Fi 右端伸进开口；副 Wi-Fi 小图标压在开口处（双 WLAN 时）"))
S.append('</g>')

S.append('<g id="layer-states" inkscape:groupmode="layer" inkscape:label="状态示例">')
gx,gy,cw,ch=840,160,340,380
for i,(u,title,st) in enumerate(STATES):
    x0=gx+(i%3)*(cw+20); y0=gy+(i//3)*(ch+20)
    S.append('<g id="state-%s">'%u)
    S.append('<rect x="%d" y="%d" width="%d" height="%d" rx="28" fill="url(#wall)"/>'%(x0,y0,cw,ch))
    S.append('<g transform="translate(%s %s) scale(0.82) translate(-200 -200)">'%(x0+cw/2,y0+170))
    S.append(ring(200,200,st,u)); S.append('</g>')
    S.append(label(x0+cw/2,y0+ch-30,title,20,"middle","#FFFFFF",600)); S.append('</g>')
S.append('</g>')

S.append('<g id="layer-preview" inkscape:groupmode="layer" inkscape:label="实际大小预览">')
py0=965
S.append(label(60,py0,"实际大小预览（圆环高度 32dp，3.5 倍密度约 112px）",24,w=700))
S.append('<rect x="60" y="%d" width="1840" height="150" rx="28" fill="url(#wall)"/>'%(py0+20))
for i,(u,title,st) in enumerate(STATES):
    x0=150+i*300
    S.append('<g transform="translate(%s %s) scale(%.4f) translate(-200 -200)">'%(x0+80,py0+95,112/D))
    S.append(ring(200,200,st,"pv"+u)); S.append('</g>')
    S.append('<text x="%s" y="%s" font-family="%s" font-weight="600" font-size="30" fill="#FFFFFF">13:52</text>'%(x0+150,py0+106,FONT))
S.append('</g>')

S.append('<g id="layer-spec" inkscape:groupmode="layer" inkscape:label="参数说明">')
lines=["参数（D = 圆环高度，sw = 线宽 = 0.068·D）",
 "电量环 r = D/2 − sw/2，15°~230°；信号环线宽 0.85·sw、环间空隙 0.35·sw，84°~219°，每环 4 段，段间空隙 0.6 × 信号环线宽；数字字号 0.245·D",
 "主 Wi-Fi 大小 0.44·D，中心在圆心右 0.15·D、上 0.03·D；副 Wi-Fi 大小 0.15·D，中心在圆心右 0.35·D、上 0.33·D（压在电量环开口上，约 317°）",
 "4G / 5G 字号 0.43·D、横向 0.92，中心在圆心右 0.13·D；飞行模式隐藏信号环，未连 Wi-Fi 时飞机 0.54·D × 0.48·D 放在圆环中间，连着 Wi-Fi 时小飞机 0.24·D 放在左下内侧"]
for j,t in enumerate(lines):
    S.append(label(60,1190+j*32,t,22 if j==0 else 20,w=700 if j==0 else 400,op=1 if j==0 else 0.75))
S.append('</g></svg>')
os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
open(OUT,"w",encoding="utf-8").write("\n".join(S))
print("ok", OUT)
