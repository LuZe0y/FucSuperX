import math, sys, os
from xml.sax.saxutils import escape
OUT = sys.argv[1]
D=320.0; SW=D*0.068; R=D/2-SW/2; FS=D*0.245; CAP=FS*0.72
RD=R-SW*1.5; DOT=SW*1.31; A3=D*0.3125          # 信号点直径 1.31·sw（s3 手改：放大约 1.45 倍）
# 信号点（按 ring_style3 初稿左右平均、对称整理）：每组 4 点排在一条半径 DA 的圆弧上，
# 弧心在圆心另一侧 DE 处，所以两组点呈括号形、与电量环大致平行；自下而上 1→4 格
DA, DE = D*0.456, D*0.1125
DANG = (34.5, 11.5, -11.5, -34.5)                # 相对水平方向的角度，下正上负
# 中间图标的排法（偏移相对圆心，单位 D）
MAIN_DUAL = (0.025, -0.018)                     # 双 Wi-Fi：主 Wi-Fi（两个图标整体居中）
W2_OFF, W2_SIZE = (-0.163, 0.095), 0.1234       # 副 Wi-Fi：主 Wi-Fi 左下方
MAIN_AIR = (0.108, -0.108)                      # 飞行模式 + Wi-Fi：主 Wi-Fi 在右上
PLS_OFF, PLS_SIZE = (-0.108, 0.108), 0.25       # 小飞机在左下，与主 Wi-Fi 关于圆心对角对称
# 飞行模式 + 双 Wi-Fi：三个图标排成正三角，主 Wi-Fi 在上，小飞机左下，副 Wi-Fi 右下
TRI_MAIN, TRI_MAIN_SIZE = (0.0, -0.095), 0.27
TRI_PLANE, TRI_PLANE_SIZE = (-0.15, 0.13), 0.21
TRI_W2, TRI_W2_SIZE = (0.15, 0.13), 0.165
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

def wifi(o,u,name,ix,iy,size,n):
    oy=iy+size*0.5; ws=size*0.2; a1=size*0.34; a2=size*0.66-ws/2; a3c=size-ws/2
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
    g=math.degrees(math.asin(min(1,(gw/2+SW*1.2)/R)))
    lit=st.get('lit',FG)
    o.append('<g id="%s-battery">'%u)
    o.append('<path id="%s-battery-track" d="%s" fill="none" stroke="%s" stroke-opacity="%s" stroke-width="%.2f" stroke-linecap="round"/>'%(u,arcp(cx,cy,R,270+g,630-g),TRACK,TRACK_OP,SW))
    if lv>0:
        end=270+g+(360-2*g)*min(1,lv/100.0)
        o.append('<path id="%s-battery-level" d="%s" fill="none" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>'%(u,arcp(cx,cy,R,270+g,end),lit,SW))
    o.append('</g>')
    x=cx-gw/2; base=cy-R+CAP/2
    o.append('<g id="%s-number">'%u)
    o.append('<text id="%s-number-text" x="%.2f" y="%.2f" font-family="%s" font-weight="700" font-size="%.2f" fill="%s" textLength="%.2f" lengthAdjust="spacingAndGlyphs">%s</text>'%(u,x,base,FONT,FS,FG,numW,num))
    if st.get('charging'):
        bx=x+numW+bg; by=cy-R-bh/2
        o.append('<path id="%s-bolt-1" d="%s" fill="%s"/>'%(u,poly(BOLT,bx,by,bw,bh),FG))
        if st.get('quick'): o.append('<path id="%s-bolt-2" d="%s" fill="%s"/>'%(u,poly(BOLT,bx+bw*0.55,by,bw,bh),FG))
    o.append('</g>')
    o.append('<g id="%s-center">'%u)
    if st['net']=='wifi':
        # 单 Wi-Fi 居中；双 Wi-Fi 两个图标整体居中；飞行模式 + Wi-Fi 对角对称；飞行模式 + 双 Wi-Fi 三角排列
        air, two = st.get('airplane'), st.get('wifi2')
        if air and two: off, ms = TRI_MAIN, TRI_MAIN_SIZE*D
        elif air:       off, ms = MAIN_AIR, A3
        elif two:       off, ms = MAIN_DUAL, A3
        else:           off, ms = (0,0), A3
        wifi(o,u,'wifi',cx+off[0]*D,cy+off[1]*D,ms,st.get('arcs',3))
        if two:
            w2, ws2 = (TRI_W2, TRI_W2_SIZE) if air else (W2_OFF, W2_SIZE)
            wifi(o,u,'wifi2',cx+w2[0]*D,cy+w2[1]*D,ws2*D,two)
    elif st['net']=='cell':
        f=FS*1.15
        o.append('<text id="%s-type" x="%.2f" y="%.2f" text-anchor="middle" font-family="%s" font-weight="700" font-size="%.2f" fill="%s">%s</text>'%(u,cx,cy+f*0.36,FONT,f,FG,st['ctype']))
    elif st.get('airplane'):
        s=A3*1.15
        o.append('<path id="%s-airplane" d="%s" fill="%s"/>'%(u,poly(PLANE,cx-s/2,cy-s/2,s,s),FG))
    o.append('</g>')
    if st.get('airplane') and st['net']=='wifi':
        po, ps = (TRI_PLANE, TRI_PLANE_SIZE) if st.get('wifi2') else (PLS_OFF, PLS_SIZE)
        w=h=ps*D; px,py=cx+po[0]*D,cy+po[1]*D
        o.append('<path id="%s-airplane-small" d="%s" fill="%s"/>'%(u,poly(PLANE,px-w/2,py-h/2,w,h),FG))
    elif not st.get('airplane'):
        for si,lvl in enumerate(st.get('sims',[])):
            o.append('<g id="%s-sim%d-dots">'%(u,si+1))
            for k,a in enumerate(DANG):
                if si==0: px,py=pt(cx+DE,cy,DA,180-a)
                else:     px,py=pt(cx-DE,cy,DA,a)
                on=lvl is not None and k<lvl
                o.append('<circle id="%s-sim%d-dot%d" cx="%.2f" cy="%.2f" r="%.2f" fill="%s" fill-opacity="%s"/>'%(u,si+1,k+1,px,py,DOT/2,FG if on else TRACK,1 if on else TRACK_OP))
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
 ("g","飞行模式 + 双 Wi-Fi",dict(level=58,net='wifi',arcs=3,wifi2=1,airplane=True)),
 ("f","低电量 · 无网络（开启电量着色）",dict(level=15,net='none',sims=[None,None],lit=RED)),
]

W,H=2320,1320
S=['<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" width="%d" height="%d" viewBox="0 0 %d %d">'%(W,H,W,H),
   '<defs><linearGradient id="wall" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7C97CF"/><stop offset="1" stop-color="#4E6CAB"/></linearGradient></defs>',
   '<rect id="page" width="%d" height="%d" fill="#F4F4F7"/>'%(W,H),
   label(60,78,"状态栏圆环 · 第三款样式 设计草图（s3-t4）",40,w=700),
   label(60,118,"顶部电量数字 · 外层电量圆环 · 中间 Wi-Fi / 制式 / 飞机 · 两侧括号形大号信号点（左卡 1、右卡 2）· 全部左右对称（按 ring_style3 初稿居中整理）",22,op=0.6)]

S.append('<g id="layer-construction" inkscape:groupmode="layer" inkscape:label="结构图">')
bx,by,bw,bh=60,160,740,760
S.append('<rect x="%d" y="%d" width="%d" height="%d" rx="36" fill="url(#wall)"/>'%(bx,by,bw,bh))
k=1.45; cx0,cy0=bx+bw/2,by+bh/2+10
S.append('<g id="construction-ring" transform="translate(%s %s) scale(%s) translate(-200 -200)">'%(cx0,cy0,k))
S.append(ring(200,200,dict(level=80,net='wifi',arcs=3,sims=[4,2],charging=True),"main"))
S.append('</g></g>')
S.append('<g id="guides" inkscape:groupmode="layer" inkscape:label="辅助线（可隐藏）" fill="none" stroke="#FFE066" stroke-width="2" stroke-dasharray="8 8">')
S.append('<circle cx="%s" cy="%s" r="%.1f"/>'%(cx0,cy0,R*k))
for sgn in (1,-1):   # 左组弧心在右（+DE），右组弧心在左（−DE）
    c=(cx0+sgn*DE*k, cy0); a0,a1=(180-45,180+45) if sgn>0 else (-45,45)
    x0_,y0_=pt(c[0],c[1],DA*k,a0); x1_,y1_=pt(c[0],c[1],DA*k,a1)
    S.append('<path d="M%.1f,%.1f A%.1f,%.1f 0 0 1 %.1f,%.1f"/>'%(x0_,y0_,DA*k,DA*k,x1_,y1_))
    S.append('<circle cx="%.1f" cy="%.1f" r="5" stroke-opacity="0.9"/>'%c)
S.append('<line x1="%s" y1="%.1f" x2="%s" y2="%.1f" stroke-opacity="0.5"/>'%(cx0,cy0-R*k-30,cx0,cy0+R*k+30))
S.append('</g>')
S.append('<g id="construction-notes">')
gl=lambda x,y,t: label(x,y,t,20,"start","#FFE066",600)
S.append(gl(bx+24,by+44,"① 电量圆环：12 点方向起顺时针，顶部缺口跟随数字宽度"))
S.append(gl(bx+24,by+74,"② 顶部数字压在圆环上，充电时右侧加闪电（快充双闪电）"))
S.append(gl(bx+24,by+bh-80,"③ 信号点排在两条括号形圆弧上，左卡 1、右卡 2，左右对称"))
S.append(gl(bx+24,by+bh-50,"④ 每张卡 4 个大点（直径 1.31·sw），间隔 23°，自下而上 1→4 格"))
S.append('</g>')

S.append('<g id="layer-states" inkscape:groupmode="layer" inkscape:label="状态示例">')
gx,gy,cw,ch=840,160,340,380
for i,(u,title,st) in enumerate(STATES):
    x0=gx+(i%4)*(cw+20); y0=gy+(i//4)*(ch+20)
    S.append('<g id="state-%s">'%u)
    S.append('<rect x="%d" y="%d" width="%d" height="%d" rx="28" fill="url(#wall)"/>'%(x0,y0,cw,ch))
    S.append('<g transform="translate(%s %s) scale(0.82) translate(-200 -200)">'%(x0+cw/2,y0+170))
    S.append(ring(200,200,st,u)); S.append('</g>')
    S.append(label(x0+cw/2,y0+ch-30,title,20,"middle","#FFFFFF",600)); S.append('</g>')
S.append('</g>')

S.append('<g id="layer-preview" inkscape:groupmode="layer" inkscape:label="实际大小预览">')
py0=965
S.append(label(60,py0,"实际大小预览（圆环高度 32dp，3.5 倍密度约 112px）",24,w=700))
S.append('<rect x="60" y="%d" width="2200" height="150" rx="28" fill="url(#wall)"/>'%(py0+20))
for i,(u,title,st) in enumerate(STATES):
    x0=150+i*305
    S.append('<g transform="translate(%s %s) scale(%.4f) translate(-200 -200)">'%(x0+80,py0+95,112/D))
    S.append(ring(200,200,st,"pv"+u)); S.append('</g>')
    S.append('<text x="%s" y="%s" font-family="%s" font-weight="600" font-size="30" fill="#FFFFFF">13:52</text>'%(x0+150,py0+106,FONT))
S.append('</g>')

S.append('<g id="layer-spec" inkscape:groupmode="layer" inkscape:label="参数说明">')
lines=["参数（D = 圆环高度，sw = 线宽 = 0.068·D）",
 "圆环半径 r = D/2 − sw/2，顶部缺口跟随数字宽度；数字字号 0.245·D；信号点直径 1.31·sw，圆弧半径 0.456·D、弧心在对侧 0.1125·D，角度 ±11.5°、±34.5°",
 "Wi-Fi 0.3125·D：单 Wi-Fi 居中；双 Wi-Fi 时主 Wi-Fi 偏移 (0.025, −0.018)·D、副 Wi-Fi 0.12·D 偏移 (−0.163, 0.095)·D，两个图标整体居中",
 "飞行模式隐藏信号点：未连 Wi-Fi 时飞机居中；+ Wi-Fi 时主 Wi-Fi (0.108, −0.108)·D 与小飞机 0.25·D (−0.108, 0.108)·D 对角对称；+ 双 Wi-Fi 时三图标正三角：主 Wi-Fi 0.27·D 在上，小飞机 0.21·D 左下、副 Wi-Fi 0.165·D 右下"]
for j,t in enumerate(lines):
    S.append(label(60,1190+j*32,t,22 if j==0 else 20,w=700 if j==0 else 400,op=1 if j==0 else 0.75))
S.append('</g></svg>')
os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
open(OUT,"w",encoding="utf-8").write("\n".join(S))
print("ok", OUT)
