#!/bin/sh
# Compara a fluidez do Vitro com o relógio de texto antigo e com o relógio de vidro, na mesma versão,
# em rodadas alternadas (texto, vidro, texto, vidro). Cada rodada é o medir-fluidez.sh completo.
# Uso: ./comparar-relogio.sh 192.168.1.50 [rodadas]
#
# Ocupa a tela da TV por uns 2 minutos por rodada dupla: o Vitro abre e navega sozinho.
# Por isso o script se recusa a rodar se outro app estiver na frente (alguém assistindo).
# Para rodar mesmo assim: FORCAR=1 ./comparar-relogio.sh ...
# Não muda nada guardado na TV: o modo "relógio de texto" é só um diagnóstico e volta ao vidro no fim.
set -e
[ -n "$1" ] || { echo "Uso: ./comparar-relogio.sh <ip da Fire TV> [rodadas]" >&2; exit 1; }
cd "$(dirname "$0")"
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
D="$1:5555"; RODADAS=${2:-2}
"$ADB" connect "$D" >/dev/null

frente=$("$ADB" -s "$D" shell "dumpsys window windows | grep -m1 mCurrentFocus" </dev/null | tr -d '\r')
case "$frente" in
  *app.vitro.launcher*|*com.amazon.tv.launcher*|"") ;;
  *)
    if [ "${FORCAR:-0}" != 1 ]; then
      echo "Outro app está na frente da TV:$frente" >&2
      echo "Alguém pode estar assistindo. Rode quando a TV estiver livre (ou com FORCAR=1)." >&2
      exit 2
    fi ;;
esac

volta() { "$ADB" -s "$D" shell am start -n app.vitro.launcher/.HomeActivity --ez relogioTexto false >/dev/null 2>&1 </dev/null || true; }
trap volta EXIT

r=1
while [ "$r" -le "$RODADAS" ]; do
  for modo in texto vidro; do
    if [ "$modo" = texto ]; then flag=true; else flag=false; fi
    "$ADB" -s "$D" shell am start -n app.vitro.launcher/.HomeActivity --ez relogioTexto $flag >/dev/null 2>&1 </dev/null
    sleep 2
    echo "### $modo, rodada $r ($(date '+%H:%M:%S'))"
    PERF=1 ./medir-fluidez.sh "$1" "$modo $r" 2>&1 | grep -E "Fluidez|janela"
  done
  r=$((r + 1))
done
echo "Pronto. O relógio de vidro está de volta."
