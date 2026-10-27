#!/usr/bin/env python3
"""GKhata icon + launch-screen generator.
Usage:  python3 tools/make-icons.py        (needs: pip install pillow numpy opencv-python-headless)
Source: tools/gkhata-logo-source.png (the GKhata logo on its navy background).
The logo is separated from its smooth navy background (alpha extraction), then:
  adaptive icon  = clean navy background + the "G" emblem (scaled into the safe zone), plus Android 13 themed (mono) layer
  legacy icons   = mipmap-*/ic_launcher(.png/_round.png) for old Android
  launch screen  = navy gradient + the full logo (emblem + "GKhata" wordmark)
Writes Android resources to native/res/ (setup.sh copies them) + preview files in tools/out/."""
import os, shutil, base64
import numpy as np, cv2
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "native", "res"); OUT = os.path.join(ROOT, "tools", "out")
SRC = os.path.join(ROOT, "tools", "gkhata-logo-source.png")

# ---------- 1. separate logo from its background ----------
def nconv(img, w, sig):
    w = w.astype("float32"); num = cv2.GaussianBlur(img * w[..., None], (0, 0), sig)
    return num / np.maximum(cv2.GaussianBlur(w, (0, 0), sig)[..., None], 1e-4)

def extract():
    I = np.array(Image.open(SRC).convert("RGB")).astype("float32")
    bgm = (I.max(2) < 140) & (I[..., 2] > I[..., 0])
    for _ in range(6):                                   # iteratively estimate the smooth background
        bg = nconv(I, bgm, 45); dist = np.sqrt(((I - bg) ** 2).sum(2))
        bgm = cv2.erode((dist < 16).astype("uint8"), np.ones((5, 5), "uint8")).astype(bool)
    bg = nconv(I, bgm, 45); dist = np.sqrt(((I - bg) ** 2).sum(2))
    a = np.clip((dist - 10) / 45.0, 0, 1)
    P = np.clip(I - (1 - a[..., None]) * bg, 0, 255)       # premultiplied colour
    return I, bg, a, P

I, BG, A, P = extract()
H, W = A.shape
n, lab, st, _ = cv2.connectedComponentsWithStats((A > .3).astype("uint8"), connectivity=8)
big = max(range(1, n), key=lambda i: st[i][4])            # biggest blob = the G emblem
ex, ey, ew, eh, _ = st[big]
word = np.zeros((H, W), "uint8")                           # everything below the emblem = wordmark
for i in range(1, n):
    if i != big and st[i][1] + st[i][3] > ey + eh * .8 and st[i][4] > 1500: word[lab == i] = 255
word = cv2.GaussianBlur(cv2.dilate(word, np.ones((41, 41), "uint8")).astype("float32"), (0, 0), 6) / 255.0
rect = np.zeros((H, W), "float32"); rect[max(ey - 25, 0):ey + eh + 25, max(ex - 25, 0):ex + ew + 25] = 1
EM = rect * (1 - word)                                     # emblem-only mask
print("emblem bbox", ex, ey, ew, eh)

def layer(mask):                                          # -> RGBA float (premultiplied colour, alpha)
    return np.dstack([P * mask[..., None], A * mask])

def place(rgba_pm, cx, cy, scale, size):
    """scale premultiplied RGBA about (cx,cy) of the source onto a size x size canvas, centred."""
    M = np.float32([[scale, 0, size / 2 - cx * scale], [0, scale, size / 2 - cy * scale]])
    return cv2.warpAffine(rgba_pm, M, (size, size), flags=cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC,
                          borderMode=cv2.BORDER_CONSTANT, borderValue=0)

def to_img(pm):                                           # premultiplied -> straight RGBA PIL
    a = pm[..., 3:4]; rgb = np.where(a > 1e-3, pm[..., :3] / np.maximum(a, 1e-3), 0)
    return Image.fromarray(np.dstack([rgb.clip(0, 255), (a * 255).clip(0, 255)]).astype("uint8"), "RGBA")

# ---------- 2. adaptive icon layers (108dp viewport -> 648px) ----------
V = 648
SCALE = (V * 0.60) / max(ew, eh)                            # emblem fills ~60% of the viewport (inside the 66dp safe zone)
ecx, ecy = ex + ew / 2, ey + eh / 2
fgpm = place(layer(EM), ecx, ecy, SCALE, V)
fg = to_img(fgpm)
bg_img = Image.fromarray(cv2.resize(BG, (V, V), interpolation=cv2.INTER_AREA).clip(0, 255).astype("uint8"), "RGB")
Ipm = place(np.dstack([I * EM[..., None], EM]), ecx, ecy, SCALE, V)                      # original pixels inside the emblem
lum = (Ipm[..., :3] @ np.float32([.299, .587, .114])) / np.maximum(Ipm[..., 3], 1e-3)
mono_a = np.clip((lum - 72) / 30.0, 0, 1) * np.clip(Ipm[..., 3] * 1.5, 0, 1)             # bright shapes only; dark calculator body is carved out
mono_a = cv2.GaussianBlur(cv2.medianBlur((mono_a * 255).astype("uint8"), 5).astype("float32") / 255.0, (0, 0), 0.8)
mono = Image.fromarray(np.dstack([np.full((V, V, 3), 255, "uint8"), (mono_a * 255).astype("uint8")]), "RGBA")

# ---------- 3. full logo for the launch screen ----------
lx0, ly0, lx1, ly1 = 60, 60, W - 60, 1190
logo_pm = layer(np.ones((H, W), "float32"))[ly0:ly1, lx0:lx1]
LW = 820; lg = to_img(cv2.resize(logo_pm, (LW, int(LW * logo_pm.shape[0] / logo_pm.shape[1])), interpolation=cv2.INTER_AREA))

def masked(im, shape):
    s = im.size[0]; m = Image.new("L", (s * 4, s * 4), 0); d = ImageDraw.Draw(m)
    if shape == "circle": d.ellipse([0, 0, s * 4 - 1, s * 4 - 1], fill=255)
    elif shape == "squircle": d.rounded_rectangle([0, 0, s * 4 - 1, s * 4 - 1], radius=s * 4 * .28, fill=255)
    else: d.rounded_rectangle([0, 0, s * 4 - 1, s * 4 - 1], radius=s * 4 * .20, fill=255)
    o = im.copy(); o.putalpha(m.resize((s, s), Image.LANCZOS)); return o

def main():
    for sub in ("drawable", "drawable-nodpi", "mipmap-anydpi-v26"):          # clear old generated art
        shutil.rmtree(os.path.join(RES, sub), ignore_errors=True)
    for dpi in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"): shutil.rmtree(os.path.join(RES, "mipmap-" + dpi), ignore_errors=True)
    for d in ("drawable-nodpi", "drawable", "mipmap-anydpi-v26"): os.makedirs(os.path.join(RES, d))
    os.makedirs(OUT, exist_ok=True)
    bg_img.save(os.path.join(RES, "drawable-nodpi", "gkhata_icon_bg.png"), optimize=True)
    fg.save(os.path.join(RES, "drawable-nodpi", "gkhata_icon_fg.png"), optimize=True)
    mono.save(os.path.join(RES, "drawable-nodpi", "gkhata_icon_mono.png"), optimize=True)
    lg.save(os.path.join(RES, "drawable-nodpi", "gkhata_logo.png"), optimize=True)
    ns = 'xmlns:android="http://schemas.android.com/apk/res/android"'
    ad = f'''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon {ns}>
    <background android:drawable="@drawable/gkhata_icon_bg" />
    <foreground android:drawable="@drawable/gkhata_icon_fg" />
    <monochrome android:drawable="@drawable/gkhata_icon_mono" />
</adaptive-icon>
'''
    for nme in ("ic_launcher", "ic_launcher_round"): open(os.path.join(RES, "mipmap-anydpi-v26", nme + ".xml"), "w", encoding="utf-8").write(ad)
    lh = 300 * lg.size[1] / lg.size[0]
    open(os.path.join(RES, "drawable", "splash.xml"), "w", encoding="utf-8").write(f'''<?xml version="1.0" encoding="utf-8"?>
<layer-list {ns}>
    <item><shape android:shape="rectangle"><gradient android:type="linear" android:angle="45" android:startColor="#000B2B" android:endColor="#033077" /></shape></item>
    <item android:gravity="center" android:width="300dp" android:height="{lh:.0f}dp" android:drawable="@drawable/gkhata_logo" />
</layer-list>
''')
    full = bg_img.convert("RGBA"); full.alpha_composite(fg)
    legacy = bg_img.convert("RGBA")                                              # legacy icon: emblem a little bigger (no safe-zone crop)
    legacy.alpha_composite(to_img(place(layer(EM), ecx, ecy, SCALE * 1.22, V)))
    for dpi, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        os.makedirs(os.path.join(RES, "mipmap-" + dpi), exist_ok=True)
        masked(legacy.resize((px, px), Image.LANCZOS), "rounded").save(os.path.join(RES, "mipmap-" + dpi, "ic_launcher.png"))
        masked(legacy.resize((px, px), Image.LANCZOS), "circle").save(os.path.join(RES, "mipmap-" + dpi, "ic_launcher_round.png"))
    masked(full.resize((512, 512), Image.LANCZOS), "squircle").save(os.path.join(OUT, "gkhata-icon-512.png"))
    full.resize((192, 192), Image.LANCZOS).convert("RGB").save(os.path.join(OUT, "gkhata-icon-192.png"), optimize=True)
    # preview board
    B = Image.new("RGBA", (1100, 600), "#E9EEEC"); d = ImageDraw.Draw(B)
    B.alpha_composite(masked(full.resize((340, 340), Image.LANCZOS), "squircle"), (30, 30))
    B.alpha_composite(masked(full.resize((340, 340), Image.LANCZOS), "circle"), (400, 30))
    t = Image.new("RGBA", (340, 340), "#D9E4FF"); t.putalpha(masked(full.resize((340, 340)), "circle").split()[3])
    tint = Image.new("RGBA", (340, 340), (27, 59, 122, 255)); tint.putalpha(mono.resize((340, 340), Image.LANCZOS).split()[3]); t.alpha_composite(tint)
    B.alpha_composite(t, (770, 30))
    x = 30
    for px in (96, 72, 48, 36): B.alpha_composite(masked(full.resize((px, px), Image.LANCZOS), "circle"), (x, 420)); x += px + 22
    B.alpha_composite(masked(legacy.resize((72, 72), Image.LANCZOS), "rounded"), (x + 20, 432))
    for xx, tx in ((30, "Squircle (Samsung/Pixel style)"), (400, "Circle"), (770, "Themed icon (Android 13+)")): d.text((xx, 380), tx, fill="#22302b")
    d.text((30, 540), "Actual-size check 96 / 72 / 48 / 36 px  +  legacy Android 7 icon", fill="#22302b")
    B.convert("RGB").save(os.path.join(OUT, "gkhata-icon-preview.png"))
    print("icons written")
main()
