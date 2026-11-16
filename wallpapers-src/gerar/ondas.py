#!/usr/bin/env python3
"""Ondas de vidro: folhas sobrepostas com bordas em S, sombra suave e um fio de luz na borda.
Desenho original, gerado por fórmula (sem foto de partida), no tom do papel Índigo.
Uso: ondas.py saida.jpg [largura=1920] [cor=indigo] [forma=a]"""
import sys
import numpy as np
from PIL import Image

dst = sys.argv[1]
W = int(sys.argv[2]) if len(sys.argv) > 2 else 1920
COR = sys.argv[3] if len(sys.argv) > 3 else "indigo"
FORMA = sys.argv[4] if len(sys.argv) > 4 else "a"
H = W * 9 // 16
SS = 2                      # desenha em dobro e reduz: bordas lisas
w, h = W * SS, H * SS
AR = 16 / 9

Y, X = np.mgrid[0:h, 0:w].astype(np.float64)
x = X / h                   # 0..1,78
y = Y / h                   # 0 (alto) .. 1 (baixo)
px = 1.0 / h


def rgb(s):
    return np.array([int(s[i:i + 2], 16) for i in (1, 3, 5)], np.float64) / 255.0


CORES = {
    # fundo, face da folha na sombra e na luz, fio de luz, brilho lateral
    "indigo": dict(dark=rgb("#0f0f17"), lo=rgb("#121220"), hi=rgb("#4a4769"), rim=rgb("#a3a3f5"), glow=rgb("#7d93c4")),
    "azul":   dict(dark=rgb("#0a0e19"), lo=rgb("#0c1222"), hi=rgb("#2d4670"), rim=rgb("#93b9f5"), glow=rgb("#6d9ccc")),
    "ameixa": dict(dark=rgb("#140d18"), lo=rgb("#181020"), hi=rgb("#55396a"), rim=rgb("#d7a3f5"), glow=rgb("#9a7cc4")),
}
C = CORES[COR]

# Campo F: cresce na direção de baixo-direita; cada borda é uma linha de nível F = c. Os termos em seno, ao longo
# da curva, fazem o S; o termo em s*n faz as curvas se abrirem como um leque.
if FORMA == "a":
    ang = np.radians(38)            # direção das curvas: sobem da esquerda-baixo para a direita-alto
    n = x * np.sin(ang) + y * np.cos(ang)
    s = x * np.cos(ang) - y * np.sin(ang)
    F = n + 0.27 * np.sin(1.75 * s + 0.75) + 0.05 * np.sin(4.4 * s + 1.3) + 0.14 * s * (n - 0.9)
    LEVELS = [0.36, 0.66, 0.97, 1.22]
    GLOW = (1.72, 0.55)
elif FORMA == "leque":              # as bordas nascem juntas embaixo à esquerda e se abrem em leque para o alto
    ang = np.radians(40)
    n = x * np.sin(ang) + y * np.cos(ang)
    s = x * np.cos(ang) - y * np.sin(ang)
    t = np.clip((s + 0.65) / 2.05, 0, 1)
    spread = 0.30 + 1.05 * t * t * (3 - 2 * t)
    F = 0.95 + (n - 0.95) / spread + 0.22 * np.sin(2.0 * s + 0.2) + 0.04 * np.sin(4.9 * s + 1.1)
    LEVELS = [0.22, 0.55, 0.80, 1.05, 1.30]
    GLOW = (1.74, 0.50)
else:                               # "b": dobras mais deitadas, como tecido
    ang = np.radians(14)
    n = x * np.sin(ang) + y * np.cos(ang)
    s = x * np.cos(ang) - y * np.sin(ang)
    F = n + 0.20 * np.sin(2.1 * s + 2.3) + 0.05 * np.sin(5.3 * s + 0.2) + 0.16 * s * (n - 0.6)
    LEVELS = [0.26, 0.50, 0.76, 0.98]
    GLOW = (1.55, 0.95)

gy, gx = np.gradient(F, px, px)
gn = np.sqrt(gx ** 2 + gy ** 2) + 1e-9
up = np.clip(gy / gn, 0, 1)         # borda virada para o alto (de onde vem a luz)


def smooth(t, e0, e1):
    t = np.clip((t - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


img = np.ones((h, w, 3)) * C["dark"]
for i, c in enumerate(LEVELS):
    dist = (F - c) / gn                                   # distância com sinal até a borda (>0: dentro da folha)
    inside = smooth(dist, -0.8 * px, 0.8 * px)

    # sombra que a folha joga sobre a de trás
    shadow = np.exp(np.minimum(dist, 0) / 0.06) * (dist < 0)
    img = img * (1 - 0.75 * shadow)[..., None]

    # face acetinada: clara depois da borda, escurecendo para dentro
    t = np.maximum(dist, 0)
    band = np.exp(-((t - 0.10) / 0.11) ** 2)
    deep = np.exp(-t / 0.30)
    shade = (0.03 + 0.55 * band + 0.10 * deep) * (0.45 + 0.75 * up)
    shade = np.clip(shade * (1.0 - 0.05 * i), 0, 1)
    face = C["lo"] + (C["hi"] - C["lo"]) * shade[..., None]

    # fio de luz: núcleo fino, mais forte onde a borda está virada para cima
    core = np.exp(-(dist / (1.7 * px)) ** 2)
    soft = np.exp(-np.abs(dist) / (7 * px))
    rimk = (0.45 + 0.55 * up)
    face = face + C["rim"] * ((0.95 * core + 0.16 * soft) * rimk)[..., None]

    img = img * (1 - inside[..., None]) + face * inside[..., None]
    img = img + C["rim"] * (0.10 * soft * rimk * (dist <= 0))[..., None]

# brilho lateral azulado
g = np.exp(-(((x - GLOW[0]) / 0.32) ** 2 + ((y - GLOW[1]) / 0.48) ** 2))
img = img + C["glow"] * (0.42 * g)[..., None]

# vinheta suave
r2 = ((x / AR - 0.5) / 0.5) ** 2 + ((y - 0.5) / 0.5) ** 2
img = img * (1 - 0.20 * np.clip(r2 / 2, 0, 1))[..., None]

img = np.clip(img, 0, 1)
im = Image.fromarray((img * 255 + 0.5).astype(np.uint8)).resize((W, H), Image.LANCZOS)
a8 = np.asarray(im).astype(np.float64)
a8 = a8 + (np.random.default_rng(3).random(a8.shape) - 0.5) * 1.2     # granulação mínima contra faixas
Image.fromarray(np.clip(a8 + 0.5, 0, 255).astype(np.uint8)).save(dst, quality=94)
