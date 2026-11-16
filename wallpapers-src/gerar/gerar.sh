#!/bin/bash
# Refaz os quatro papéis de parede livres feitos para o Vitro (01a, 01b, 02a, 02b) em launcher/assets/wallpapers.
# Precisa de Python 3 com numpy e Pillow. As duas fotos de partida (domínio público, BLM) são baixadas do
# Wikimedia Commons se ainda não estiverem em wallpapers-src/.
set -e
cd "$(dirname "$0")"
SRC=..
OUT=../../launcher/assets/wallpapers
UA="VitroWallpaper/1.0 (launcher wallpaper build)"
baixa() { [ -f "$SRC/$1" ] || curl -fsSL -A "$UA" -o "$SRC/$1" "$2"; }
baixa "Dumont_Dunes_East.jpg" "https://upload.wikimedia.org/wikipedia/commons/1/1f/Dumont_Dunes_East.jpg"
baixa "Dumont_Dunes_(49279424871).jpg" "https://upload.wikimedia.org/wikipedia/commons/c/c3/Dumont_Dunes_%2849279424871%29.jpg"

# Desenhos originais, sem foto de partida.
python3 ondas.py "$OUT/01a-leque.jpg" 1920 indigo leque
python3 ondas.py "$OUT/01b-ondas.jpg" 1920 indigo a
# Fotos de dia das Dumont Dunes (deserto de Mojave) transformadas em noite de lua.
python3 noite.py "$SRC/Dumont_Dunes_East.jpg" "$OUT/02a-luar.jpg" --posicao 0.45 --corte-baixo 0.07
python3 noite.py "$SRC/Dumont_Dunes_(49279424871).jpg" "$OUT/02b-crista.jpg" --posicao 0
echo "pronto"
