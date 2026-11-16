#!/bin/sh
# Mede a fluidez das animações do Vitro na Fire TV: quadros realmente apresentados na tela,
# lidos do SurfaceFlinger (o compositor do Android), que é a medida que vale para o olho.
# Uso: ./medir-fluidez.sh 192.168.1.50 [rotulo]
#
# Não altera nada na TV: só abre o Vitro, aperta teclas do controle e lê estatísticas.
set -e
[ -n "$1" ] || { echo "Uso: ./medir-fluidez.sh <ip da Fire TV> [rotulo]" >&2; exit 1; }
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
D="$1:5555"; LABEL=${2:-medicao}
PKG=app.vitro.launcher
TMP=$(mktemp -d)
# </dev/null: sem isso o adb engole a entrada do laço que percorre as camadas.
sh_() { "$ADB" -s "$D" shell "$@" </dev/null 2>/dev/null | tr -d '\r'; }

"$ADB" connect "$D" >/dev/null
sh_ input keyevent KEYCODE_WAKEUP
# PERF=1 liga a medição interna de tempo por quadro; PERF=3 também desliga o redesenho parcial.
sh_ am start -n $PKG/.HomeActivity --ei perf ${PERF:-0} --ei skip ${SKIP:-0} >/dev/null
"$ADB" -s "$D" logcat -c </dev/null
sh_ 'sleep 3'
# Volta para um estado conhecido: fecha menus e leva o foco para o primeiro app da barra.
sh_ 'input keyevent 4 4 4; input keyevent 20; input keyevent 21 21 21 21 21 21 21 21 21'
sh_ 'sleep 2'

# Camadas do Vitro no compositor (a janela e, na versão antiga, a superfície de OpenGL).
sh_ dumpsys SurfaceFlinger --list | grep "$PKG" | grep -v -E "AppWindowToken|Background for|^[0-9a-f]+ " > "$TMP/layers.txt"

CENARIOS=${CENARIOS:-foco menu gaveta appmenu}
run() { # $1 = nome do cenário, $2 = comandos de tecla (rodam na TV)
  case " $CENARIOS " in *" $1 "*) ;; *) return 0 ;; esac
  sh_ dumpsys gfxinfo $PKG reset >/dev/null
  sh_ dumpsys SurfaceFlinger --latency-clear
  sh_ "$2"
  sh_ 'sleep 1'
  n=0
  while IFS= read -r layer; do
    n=$((n+1))
    sh_ dumpsys SurfaceFlinger --latency "\"$layer\"" > "$TMP/$1.$n.lat"
    echo "$layer" > "$TMP/$1.$n.name"
  done < "$TMP/layers.txt"
  sh_ dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" > "$TMP/$1.gfx" || true
}

# Cada "input keyevent" leva perto de 1 s para iniciar na TV, então as teclas saem espaçadas:
# cada uma dispara uma animação completa.
run foco    'for i in 1 2 3 4 5 6; do input keyevent 22; done; for i in 1 2 3 4 5 6; do input keyevent 21; done'
run menu    'input keyevent 19; input keyevent 23; input keyevent 20; input keyevent 20; input keyevent 4; input keyevent 23; input keyevent 4; input keyevent 20'
run gaveta  'input keyevent 22 22 22 22 22 22 22 22; input keyevent 23; input keyevent 22; input keyevent 20; input keyevent 4; input keyevent 23; input keyevent 4; input keyevent 21 21 21 21 21 21 21 21'
run appmenu 'input keyevent 22; input keyevent 82; input keyevent 20; input keyevent 20; input keyevent 4; input keyevent 82; input keyevent 4; input keyevent 21'

"$ADB" -s "$D" logcat -d -s VitroPerf:I 2>/dev/null </dev/null | grep "quadro GPU" > "$TMP/perf.txt" || true

python3 -I - "$TMP" "$LABEL" <<'EOF'
import sys, os, glob
tmp, label = sys.argv[1], sys.argv[2]
print("== Fluidez (%s) ==" % label)
for scen in ("foco", "menu", "gaveta", "appmenu"):
    for f in sorted(glob.glob(os.path.join(tmp, scen + ".*.lat"))):
        name = open(f[:-4] + ".name").read().strip()
        kind = "OpenGL" if name.startswith("SurfaceView") else "janela"
        lines = open(f).read().split("\n")
        ts = []
        for ln in lines[1:]:
            p = ln.split()
            if len(p) == 3 and p[1] not in ("0", "9223372036854775807"):
                ts.append(int(p[1]) / 1e6)
        # Intervalos acima de 100 ms são a pausa entre uma tecla e a próxima, não quadros de animação.
        d = [b - a for a, b in zip(ts, ts[1:]) if 0 < b - a < 100]
        if len(d) < 5:
            print("%-8s %-7s poucos quadros (%d)" % (scen, kind, len(d)))
            continue
        d.sort()
        mean = sum(d) / len(d)
        pct = lambda q: d[min(len(d) - 1, int(q * len(d)))]
        ontime = 100.0 * sum(1 for x in d if x <= 18.5) / len(d)
        late = [sum(1 for x in d if lo < x <= hi) for lo, hi in ((18.5, 35), (35, 52), (52, 100))]
        print("%-8s %-7s %3d quadros | mediana %5.1f ms | p90 %5.1f | pior %5.1f | no prazo de 60 Hz: %3.0f%% | atrasados: %d de 1 quadro, %d de 2, %d de 3 ou mais"
              % (scen, kind, len(d), pct(0.5), pct(0.9), d[-1], ontime, late[0], late[1], late[2]))
    g = os.path.join(tmp, scen + ".gfx")
    if os.path.exists(g):
        txt = " ".join(open(g).read().split())
        if "Total frames rendered: 0" not in txt and txt:
            print("         gfxinfo: " + txt)
EOF
if [ -s "$TMP/perf.txt" ]; then
  echo "-- custo por quadro medido pelo próprio Vitro (GPU + CPU, blocos de 30 quadros) --"
  sed -E 's/.*VitroPerf: //' "$TMP/perf.txt"
fi
rm -rf "$TMP"
