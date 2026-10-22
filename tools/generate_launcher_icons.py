from PIL import Image, ImageDraw, ImageFilter
from pathlib import Path
import math
r=Path(__file__).resolve().parents[1]/'app/src/main/res'
def lerp(a,b,t):return int(a*(1-t)+b*t)

def bg(size,transparent=False):
    im=Image.new('RGBA',(size,size))
    p=im.load()
    a=(8,17,48);b=(46,29,119);c=(0,170,189)
    for y in range(size):
        for x in range(size):
            t=max(0,min(1,.13+.7*(x+y)/(2*size)))
            z=max(0,min(1,(x/size*.67+y/size*.9-.65)))
            rgb=tuple(lerp(lerp(a[i],b[i],t),c[i],z*.56) for i in range(3))
            p[x,y]=(*rgb,0 if transparent else 255)
    return im

def bubbles(im, strength=.6):
    s=im.width
    layer=Image.new('RGBA',(s,s));d=ImageDraw.Draw(layer,'RGBA')
    for (x,y,r,a) in ((.22,.26,.20,70),(.80,.72,.26,55),(.76,.18,.105,92),(.20,.78,.10,90)):
        X,Y,R=int(x*s),int(y*s),int(r*s)
        d.ellipse((X-R,Y-R,X+R,Y+R),fill=(80,228,241,int(a*strength)),outline=(199,253,255,int(a*1.45*strength)),width=max(1,s//130))
        d.arc((X-R*.73,Y-R*.73,X+R*.73,Y+R*.73),195,298, fill=(240,255,255,int(a*1.8*strength)),width=max(1,s//90))
    im.alpha_composite(layer)

def central_symbol(im):
    s=im.width
    d=ImageDraw.Draw(im,'RGBA')
    # Interlocking liquid strokes: mint N slants with lavender reflected endpoint.
    pts=[(.28,.68),(.28,.32),(.40,.32),(.60,.60),(.60,.32),(.72,.32),(.72,.68),(.60,.68),(.40,.40),(.40,.68)]
    xy=[(round(x*s),round(y*s)) for x,y in pts]
    d.polygon(xy,fill=(222,255,255,248))
    d.line([(int(.30*s),int(.315*s)),(int(.42*s),int(.315*s))], fill=(112,252,245,255),width=max(1,s//110))
    d.line([(int(.58*s),int(.69*s)),(int(.72*s),int(.69*s))], fill=(152,145,255,255),width=max(1,s//110))
    # Reflected water droplet.
    d.ellipse((int(.695*s),int(.52*s),int(.747*s),int(.572*s)), fill=(80,239,253,215))

for density,scale in [('mdpi',1),('hdpi',1.5),('xhdpi',2),('xxhdpi',3),('xxxhdpi',4)]:
    target=int(48*scale)
    large=target*4
    out=bg(large)
    bubbles(out,1.0)
    central_symbol(out)
    out=out.resize((target,target),Image.Resampling.LANCZOS).convert('RGB')
    folder=r/f'mipmap-{density}';folder.mkdir(exist_ok=True)
    out.save(folder/'ic_launcher.png', optimize=True)
    # Circular-style bitmap for launchers that use legacy roundIcon.
    mask=Image.new('L',(target,target),0);maskd=ImageDraw.Draw(mask)
    maskd.ellipse((0,0,target-1,target-1),fill=255)
    rounded=out.convert('RGBA');rounded.putalpha(mask)
    rounded.save(folder/'ic_launcher_round.png',optimize=True)
    # Adaptive foreground is 108dp per side, with the entire logo in safe 72dp center.
    fs=int(108*scale);w=fs*3
    fg=Image.new('RGBA',(w,w),(0,0,0,0))
    bubbles(fg,.65)
    central_symbol(fg)
    fg.resize((fs,fs),Image.Resampling.LANCZOS).save(folder/'ic_launcher_foreground.png',optimize=True)

(r/'drawable').mkdir(exist_ok=True)
(r/'drawable'/'ic_launcher_background.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="315" android:startColor="#07142F" android:centerColor="#222668" android:endColor="#08788D"/>
</shape>
''')
(r/'mipmap-anydpi-v26').mkdir(exist_ok=True)
for name in ('ic_launcher','ic_launcher_round'):
    (r/'mipmap-anydpi-v26'/f'{name}.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
''')
print('Generated Liquid Aurora app icon resources')
