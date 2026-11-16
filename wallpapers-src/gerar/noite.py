#!/usr/bin/env python3
"""Noite de lua: transforma uma foto de duna feita de dia numa cena noturna em azul.
Uso: noite.py entrada.jpg saida.jpg [--largura 1920] [--posicao 0.5] [--corte-baixo 0] [--sem-estrelas]
  --posicao: onde fica o recorte 16:9 (0 = alto ou esquerda, 1 = baixo ou direita)
  --corte-baixo: fração da foto descartada embaixo antes do recorte (tira chão ou estrada)"""
import argparse
import numpy as np
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("dst")
ap.add_argument("--largura", type=int, default=1920)
ap.add_argument("--posicao", type=float, default=0.5)
ap.add_argument("--corte-baixo", type=float, default=0.0)
ap.add_argument("--limiar", type=float, default=0.07, help="força da borda que separa céu e chão")
ap.add_argument("--sem-estrelas", action="store_true")
o = ap.parse_args()
src, dst, W, pos, stars = o.src, o.dst, o.largura, o.posicao, not o.sem_estrelas
H = W * 9 // 16

Image.MAX_IMAGE_PIXELS = None
im = Image.open(src).convert("RGB")
if o.corte_baixo > 0:
    im = im.crop((0, 0, im.size[0], int(im.size[1] * (1 - o.corte_baixo))))
w, h = im.size
th = w * 9 // 16
if th <= h:
    top = int((h - th) * pos)
    im = im.crop((0, top, w, top + th))
else:
    tw = h * 16 // 9
    left = int((w - tw) * pos)
    im = im.crop((left, 0, left + tw, h))
im = im.resize((W, H), Image.LANCZOS)
a = np.asarray(im).astype(np.float32) / 255.0
lum = 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]


def smooth(x, e0, e1):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def _box(x, k, axis):
    # média móvel de largura 2k+1 ao longo de um eixo, com borda repetida
    pad = [(0, 0)] * x.ndim; pad[axis] = (k + 1, k)
    c = np.cumsum(np.pad(x, pad, mode="edge"), axis=axis)
    n = x.shape[axis]
    return (np.take(c, range(2 * k + 1, 2 * k + 1 + n), axis=axis) - np.take(c, range(0, n), axis=axis)) / (2 * k + 1)


def blur(x, r):
    # três passadas de caixa ~ desfoque gaussiano de raio r
    k = max(1, int(round(r * 0.8)))
    x = x.astype(np.float64)
    for _ in range(3):
        x = _box(_box(x, k, 0), k, 1)
    return x


# Céu = o que fica acima da primeira borda forte de cada coluna (a silhueta das dunas ou dos morros).
bl = np.stack([blur(a[..., c], W / 480) for c in range(3)], -1)
d = max(2, W // 480)
g = np.abs(bl[2 * d:] - bl[:-2 * d]).sum(-1)
g = np.vstack([np.zeros((d, W)), g, np.zeros((d, W))])
thr = o.limiar
hit = g > thr
first = np.where(hit.any(0), hit.argmax(0) + 3 * d, H)  # fim da transição, não o começo
k = max(3, W // 60)
pad = np.pad(first, k, mode="edge")
first = np.array([np.median(pad[j:j + 2 * k + 1]) for j in range(W)])
rows = np.arange(H)[:, None]
sky = np.clip((first[None, :] - rows) / max(2, W / 480) + 0.5, 0, 1)
sky = np.clip(blur(sky, W / 960), 0, 1)

# Areia: rampa de duas cores, sombra azul-marinho e luz de lua prata-azulada.
lo, hi = np.percentile(lum[sky < 0.5] if (sky < 0.5).any() else lum, [1, 99.5])
f = np.clip((lum - lo) / max(hi - lo, 1e-3), 0, 1) ** 1.35
S = np.array([20, 29, 56]) / 255.0
L = np.array([156, 166, 194]) / 255.0
sand = S + (L - S) * f[..., None]

# Céu: degradê noturno, mais escuro no alto, mantendo um pouco das nuvens.
yy = np.linspace(0, 1, H)[:, None]
topc = np.array([11, 18, 38]) / 255.0
horc = np.array([44, 58, 92]) / 255.0
grad = topc + (horc - topc) * (yy ** 1.6)[..., None]
skyl = lum / max(np.percentile(lum[sky > 0.5], 50) if (sky > 0.5).any() else 1, 1e-3)
skyc = grad * (0.75 + 0.25 * np.clip(skyl, 0, 2))[..., None]

# Morros ao fundo (azulados pela névoa, mais frios que a areia): bem mais escuros, como silhuetas.
warm = blur(a[..., 0] - a[..., 2], W / 500)
sandw = np.median(warm[int(H * 0.6):])
haze = smooth(sandw - warm, 0.04, 0.10) * (1 - sky)
sand = sand * (1 - haze[..., None]) + (S + (L - S) * (f * 0.28)[..., None]) * haze[..., None]
out = sand * (1 - sky[..., None]) + skyc * sky[..., None]

if stars:
    rng = np.random.default_rng(7)
    n = int(W * H / 2600)
    st = np.zeros((H, W), np.float32)
    ys = rng.integers(0, H, n); xs = rng.integers(0, W, n)
    st[ys, xs] = rng.random(n) ** 3 * 2.2
    st = blur(st, W / 2400)
    out = out + (st * sky * (1 - yy[..., 0:1].repeat(W, 1) ** 2))[..., None] * np.array([0.85, 0.9, 1.0])

# Vinheta suave e granulação mínima para o degradê não fazer faixas.
Y, X = np.mgrid[0:H, 0:W]
v = 1 - 0.18 * (((X - W / 2) / (W / 2)) ** 2 + ((Y - H / 2) / (H / 2)) ** 2)
out = out * v[..., None]
out = out + (np.random.default_rng(1).random(out.shape) - 0.5) / 255.0
Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8)).save(dst, quality=92)
