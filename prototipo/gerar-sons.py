#!/usr/bin/env python3
"""
Sons de navegação do Vitro, feitos do zero por síntese: nenhuma gravação nem amostra de terceiros entra aqui, só
senos, ruído filtrado e envelopes calculados neste arquivo. O resultado é original e pode ir para a versão pública.

A ideia segue o jeito de interfaces de TV bem cuidadas: sons curtíssimos (de 40 a 140 ms), baixos, sem melodia, da
mesma família, e o de mover bem mais discreto que o de confirmar, para não cansar quando se navega rápido.

Quatro famílias, cada uma com quatro papéis:
  mover   foco passa para outro botão (o mais frequente: o mais leve)
  ok      confirmar / abrir
  voltar  voltar / fechar (dois toques descendo)
  limite  tentar passar do fim da fileira (um toque abafado)

Uso: python3 prototipo/gerar-sons.py   →   prototipo/sons/<família>-<papel>.wav (48 kHz, mono, 16 bits)
Prévia: prototipo/sons.html
"""
import os
import wave

import numpy as np
from scipy.signal import butter, lfilter

SR = 48000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sons")
rng = np.random.default_rng(7)  # semente fixa: rodar de novo dá os mesmos arquivos


def axis(dur):
    return np.arange(int(SR * dur)) / SR


def env(t, attack, decay):
    """Subida rápida e queda exponencial (os tempos em segundos)."""
    return (1 - np.exp(-t / attack)) * np.exp(-t / decay)


def modes(t, freqs, amps, decays, attack=0.0004):
    """Parciais que soam juntos e se apagam, como um objeto pequeno tocado de leve."""
    s = np.zeros_like(t)
    for f, a, d in zip(freqs, amps, decays):
        s += a * np.sin(2 * np.pi * f * t) * env(t, attack, d)
    return s


def bandpass(x, f0, q):
    w0 = 2 * np.pi * f0 / SR
    alpha = np.sin(w0) / (2 * q)
    return lfilter([alpha, 0, -alpha], [1 + alpha, -2 * np.cos(w0), 1 - alpha], x)


def lowpass(x, f):
    b, a = butter(2, f / (SR / 2))
    return lfilter(b, a, x)


def highpass(x, f):
    b, a = butter(2, f / (SR / 2), "high")
    return lfilter(b, a, x)


def burst(t, decay, f0, q, attack=0.0002):
    """Um sopro de ruído curtíssimo, filtrado numa faixa: o "tique" do contato."""
    return bandpass(rng.standard_normal(len(t)) * env(t, attack, decay), f0, q)


def glide(t, f0, f1, sweep, decay, attack=0.0005):
    """Seno cujo tom desliza de f0 para f1 em `sweep` segundos (gota)."""
    f = f0 * (f1 / f0) ** np.clip(t / sweep, 0, 1)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * env(t, attack, decay)


def room(x, amount, length=0.06):
    """Um espaço mínimo em volta do som, para ele não soar seco demais."""
    t = axis(length)
    ir = lowpass(rng.standard_normal(len(t)) * np.exp(-t / (length / 5)), 6000)
    ir /= np.sqrt(np.sum(ir ** 2))
    out = amount * np.convolve(x, ir)
    out[: len(x)] += x
    return out


def place(parts, total):
    """Junta pedaços em instantes diferentes: [(som, início em segundos), ...]."""
    out = np.zeros(int(SR * total))
    for s, start in parts:
        i = int(SR * start)
        n = min(len(s), len(out) - i)
        out[i:i + n] += s[:n]
    return out


def finish(x, peak_db):
    x = highpass(np.concatenate([x, np.zeros(int(SR * 0.012))]), 40)
    fade = int(SR * 0.004)
    x[-fade:] *= np.linspace(1, 0, fade)
    return x * (10 ** (peak_db / 20) / np.max(np.abs(x)))


# ---- Famílias ------------------------------------------------------------------------------------------------

def cristal():
    """Vidro fino: parciais agudos e não harmônicos, como tocar de leve uma taça com a unha."""
    def tick(f, decay=0.016, air=0.35):
        t = axis(0.08)
        return modes(t, [f, f * 2.32], [1, 0.22], [decay, decay * 0.45]) + air * burst(t, 0.0018, f * 1.65, 1.3)

    t = axis(0.14)
    ok = (modes(t, [1480, 3434, 6290], [1, 0.33, 0.12], [0.042, 0.018, 0.009])
          + 0.40 * modes(t, [410], [1], [0.018], attack=0.001)
          + 0.40 * burst(t, 0.0025, 3200, 1.2))
    t = axis(0.07)
    limite = modes(t, [620], [1], [0.012], attack=0.0008) + 0.5 * lowpass(burst(t, 0.003, 900, 0.8), 1500)
    return {
        "mover": finish(room(tick(3150), 0.05), -15),
        "ok": finish(room(ok, 0.07), -10),
        "voltar": finish(room(place([(tick(2650, 0.014, 0.3), 0), (0.85 * tick(1960, 0.016, 0.3), 0.05)], 0.13), 0.05), -13),
        "limite": finish(limite, -18),
    }


def toque():
    """Toque macio: um "toc" curto de material leve, com o tom quase escondido. O mais neutro dos quatro."""
    def tap(f, decay=0.0035, body=0.5):
        t = axis(0.05)
        return lowpass(burst(t, decay, f, 7) + body * modes(t, [f], [1], [0.006]), 7000)

    t = axis(0.10)
    ok = (burst(t, 0.006, 1150, 6) + 0.6 * modes(t, [1150, 2650], [1, 0.15], [0.014, 0.006])
          + 0.7 * modes(t, [180], [1], [0.022], attack=0.0015))
    t = axis(0.07)
    limite = 0.8 * modes(t, [260], [1], [0.02], attack=0.002) + 0.4 * lowpass(burst(t, 0.004, 500, 1), 900)
    return {
        "mover": finish(room(tap(2300), 0.04), -15),
        "ok": finish(room(ok, 0.06), -10),
        "voltar": finish(room(place([(tap(1550), 0), (0.85 * tap(1180), 0.04)], 0.11), 0.04), -13),
        "limite": finish(limite, -17),
    }


def gota():
    """Gota: um seno curto que sobe de tom, liso e brilhante. Volta e limite descem."""
    t = axis(0.06)
    mover = glide(t, 1650, 2350, 0.012, 0.013) + 0.2 * glide(t, 3300, 4700, 0.012, 0.006)
    t = axis(0.12)
    ok = (glide(t, 880, 1500, 0.02, 0.032) + 0.25 * glide(t, 1760, 3000, 0.02, 0.015)
          + 0.15 * burst(t, 0.0015, 4000, 1))
    t = axis(0.09)
    voltar = glide(t, 1700, 1050, 0.025, 0.028) + 0.2 * glide(t, 3400, 2100, 0.025, 0.012)
    t = axis(0.07)
    limite = glide(t, 520, 430, 0.02, 0.02)
    return {
        "mover": finish(room(mover, 0.05), -16),
        "ok": finish(room(ok, 0.07), -11),
        "voltar": finish(room(voltar, 0.05), -13),
        "limite": finish(limite, -18),
    }


def seda():
    """Seda: quase só ar, um "tsk" agudo e macio. O mais discreto; o OK ganha um corpo leve."""
    def tsk(f, decay=0.0022):
        t = axis(0.04)
        return highpass(burst(t, decay, f, 1.0), 3000)

    t = axis(0.10)
    ok = 0.8 * highpass(burst(t, 0.003, 5000, 1), 2000) + 0.6 * modes(t, [920, 2150], [1, 0.2], [0.02, 0.008], attack=0.0008)
    t = axis(0.07)
    limite = lowpass(burst(t, 0.005, 700, 0.9), 1200)
    return {
        "mover": finish(room(tsk(7500), 0.05), -18),
        "ok": finish(room(ok, 0.06), -12),
        "voltar": finish(room(place([(tsk(6500), 0), (0.85 * tsk(4800, 0.0026), 0.035)], 0.09), 0.05), -15),
        "limite": finish(limite, -19),
    }


FAMILIES = {"cristal": cristal, "toque": toque, "gota": gota, "seda": seda}


def write_wav(path, x):
    dither = (rng.random(len(x)) - rng.random(len(x))) / 32768  # TPDF: some o chiado de arredondamento
    pcm = np.clip(np.round((x + dither) * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    os.makedirs(OUT, exist_ok=True)
    for fam, make in FAMILIES.items():
        for role, x in make().items():
            path = os.path.join(OUT, f"{fam}-{role}.wav")
            write_wav(path, x)
            spec = np.abs(np.fft.rfft(x))
            centroid = np.sum(np.fft.rfftfreq(len(x), 1 / SR) * spec) / np.sum(spec)
            print(f"{fam:8s} {role:7s} {len(x) / SR * 1000:4.0f} ms  pico {20 * np.log10(np.max(np.abs(x))):5.1f} dB"
                  f"  centro {centroid:5.0f} Hz  {os.path.getsize(path) / 1024:4.1f} KB")


if __name__ == "__main__":
    main()
