#!/bin/sh
# Instala o Vitro na Fire TV pela rede e abre a tela inicial.
# Uso: ./instalar.sh 192.168.1.50      (o IP está em Configurações > Minha Fire TV > Sobre > Rede)
# Antes: na Fire TV, ative Opções do desenvolvedor > Depuração ADB.
set -e
cd "$(dirname "$0")"
[ -n "$1" ] || { echo "Uso: ./instalar.sh <ip da Fire TV>" >&2; exit 1; }
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
[ -f out/vitro.apk ] || sh build.sh

"$ADB" connect "$1:5555"
echo "Se a TV perguntar, escolha \"Sempre permitir\" e aperte OK."
"$ADB" -s "$1:5555" wait-for-device
"$ADB" -s "$1:5555" install -r out/vitro.apk
"$ADB" -s "$1:5555" shell am start -n app.vitro.launcher/.HomeActivity
echo "Vitro instalado e aberto. Para remover: $ADB -s $1:5555 uninstall app.vitro.launcher"
