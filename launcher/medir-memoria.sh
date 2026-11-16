#!/bin/sh
# Mede a memória do Vitro e do launcher da Amazon na Fire TV, para conferir a meta de leveza.
# Uso: ./medir-memoria.sh 192.168.1.50
#
# Meta: Vitro na tela + launcher da Amazon em segundo plano devem somar MENOS do que o launcher
# da Amazon sozinho na tela. O script mede os três números e diz se a meta foi cumprida.
set -e
[ -n "$1" ] || { echo "Uso: ./medir-memoria.sh <ip da Fire TV>" >&2; exit 1; }
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
D="$1:5555"
VITRO=app.vitro.launcher
AMAZON=com.amazon.tv.launcher

"$ADB" connect "$D" >/dev/null
"$ADB" -s "$D" wait-for-device

# PSS total do processo, em KB. Zero se o processo não estiver rodando.
pss() {
  "$ADB" -s "$D" shell dumpsys meminfo "$1" 2>/dev/null | awk '/TOTAL/ {print $2; found=1; exit} END {if (!found) print 0}'
}
mb() { echo "$1" | awk '{printf "%.0f", $1/1024}'; }

echo "1/3  Abrindo a tela da Amazon e esperando assentar (8 s)..."
"$ADB" -s "$D" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $AMAZON >/dev/null 2>&1 || true
sleep 8
AMAZON_FRENTE=$(pss $AMAZON)
VITRO_FUNDO=$(pss $VITRO)

echo "2/3  Abrindo o Vitro e esperando assentar (8 s)..."
"$ADB" -s "$D" shell am start -n $VITRO/.HomeActivity >/dev/null
sleep 8
VITRO_FRENTE=$(pss $VITRO)
AMAZON_FUNDO=$(pss $AMAZON)

# O launcher da Amazon não devolve memória na hora: o sistema só a recolhe (ou comprime) quando precisa.
ESPERA=${ESPERA:-60}
echo "3/3  Esperando mais ${ESPERA} s com o Vitro na tela..."
sleep "$ESPERA"
VITRO_ASSENTADO=$(pss $VITRO)
AMAZON_ASSENTADO=$(pss $AMAZON)

echo
echo "Launcher da Amazon na tela:                    $(mb "$AMAZON_FRENTE") MB"
echo "Vitro na tela, logo ao abrir:                  $(mb "$VITRO_FRENTE") MB"
echo "Vitro na tela, assentado:                      $(mb "$VITRO_ASSENTADO") MB"
echo "Vitro em segundo plano (antes de soltar o OpenGL, o que ele faz após 20 s): $(mb "$VITRO_FUNDO") MB"
echo "Launcher da Amazon em 2º plano, logo ao sair:  $(mb "$AMAZON_FUNDO") MB"
echo "Launcher da Amazon em 2º plano, após ${ESPERA} s:     $(mb "$AMAZON_ASSENTADO") MB"
SOMA=$((VITRO_ASSENTADO + AMAZON_ASSENTADO))
echo "Vitro na tela + Amazon em 2º plano:            $(mb "$SOMA") MB"
echo
if [ "$SOMA" -lt "$AMAZON_FRENTE" ]; then
  echo "Meta cumprida: os dois juntos usam $(mb $((AMAZON_FRENTE - SOMA))) MB a menos que a Amazon sozinha."
else
  echo "Meta NÃO cumprida agora: os dois juntos usam $(mb $((SOMA - AMAZON_FRENTE))) MB a mais que a Amazon sozinha."
  echo "O que pesa é o launcher da Amazon parado em segundo plano; o Vitro não controla a memória dele."
fi
echo
echo "Memória livre do aparelho:"
"$ADB" -s "$D" shell cat /proc/meminfo | grep -E "MemTotal|MemAvailable"
