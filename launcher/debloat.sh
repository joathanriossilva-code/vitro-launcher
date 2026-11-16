#!/bin/sh
# Desativa (sem apagar) apps da Amazon na Fire TV, por grupo, e desfaz quando quiser.
#
#   ./debloat.sh <ip> ver                            mostra cada grupo: o que está ligado, rodando e quanto ocupa
#   ./debloat.sh <ip> simular anuncios,telemetria    mostra o que seria desativado, sem alterar nada
#   ./debloat.sh <ip> desativar anuncios,telemetria  desativa os grupos escolhidos
#   ./debloat.sh <ip> desfazer [grupos]              reativa tudo o que está na lista (ou só os grupos dados)
#
# Grupos: anuncios, telemetria, extras, alexa, musica-alexa, atalhos, apps-amazon, espelhamento, tv-ao-vivo
# (ver debloat.txt).
# "ver" e "simular" não alteram nada na TV. "desativar" usa "pm disable-user": o app continua no aparelho
# e volta com "desfazer" ou com "pm enable". Um reset de fábrica também devolve tudo.
set -e
cd "$(dirname "$0")"
[ -n "$1" ] && [ -n "$2" ] || { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
D="$1:5555"; MODE="$2"; GROUPS_WANTED="$3"
LIST=debloat.txt
sh_() { "$ADB" -s "$D" shell "$@" </dev/null 2>/dev/null | tr -d '\r'; }

"$ADB" connect "$D" >/dev/null
"$ADB" -s "$D" wait-for-device

# Linhas "grupo pacote descrição" dos grupos pedidos (todos, se nenhum foi pedido).
selected() {
  grep -v -E '^\s*(#|$)' "$LIST" | while read -r g pkg rest; do
    if [ -z "$GROUPS_WANTED" ] || echo ",$GROUPS_WANTED," | grep -q ",$g,"; then
      echo "$g $pkg ${rest#\# }"
    fi
  done
}

case "$MODE" in
  ver)
    TMP=$(mktemp -d)
    sh_ pm list packages | sed 's/^package://' > "$TMP/all"
    sh_ pm list packages -d | sed 's/^package://' > "$TMP/dis"
    sh_ dumpsys meminfo > "$TMP/mem"
    python3 -I - "$LIST" "$TMP" <<'EOF'
import sys, re, os
lst, tmp = sys.argv[1], sys.argv[2]
inst = set(open(os.path.join(tmp, "all")).read().split())
dis = set(open(os.path.join(tmp, "dis")).read().split())
t = open(os.path.join(tmp, "mem")).read()
pss = {}
if "Total PSS by process:" in t:
    for ln in t[t.index("Total PSS by process:"):t.index("Total PSS by OOM adjustment:")].split("\n")[1:]:
        m = re.match(r"\s*([\d,]+)K: (\S+) \(pid", ln)
        if m:
            base = m.group(2).split(":")[0]
            pss[base] = pss.get(base, 0) + int(m.group(1).replace(",", "")) / 1024.0
groups, order = {}, []
for ln in open(lst):
    m = re.match(r"([\w-]+)\s+(\S+)\s+#\s*(.*)", ln)
    if not m or ln.startswith("#"): continue
    g, p, note = m.groups()
    if g not in groups: groups[g] = []; order.append(g)
    groups[g].append((p, note))
total = 0
for g in order:
    run = sum(pss.get(p, 0) for p, _ in groups[g])
    on = sum(1 for p, _ in groups[g] if p in inst and p not in dis)
    off = sum(1 for p, _ in groups[g] if p in dis)
    total += run
    print("== %-13s %2d ligados, %2d desativados, %5.1f MB rodando agora" % (g, on, off, run))
    for p, note in groups[g]:
        st = "não instalado" if p not in inst else ("DESATIVADO" if p in dis else ("rodando %5.1f MB" % pss[p] if p in pss else "ligado, parado"))
        print("   %-44s %-17s %s" % (p, st, note))
print("\nTotal rodando agora nos grupos: %.0f MB" % total)
m = re.search(r"Free RAM:\s*([\d,]+)K", t)
if m: print("Memória livre do aparelho (com cache): %.0f MB" % (int(m.group(1).replace(",", "")) / 1024.0))
EOF
    rm -rf "$TMP"
    ;;
  simular)
    echo "Seria desativado (nada foi alterado):"
    selected | while read -r g pkg note; do
      if sh_ pm list packages | grep -q "^package:$pkg$"; then echo "  [$g] $pkg  ($note)"; fi
    done
    ;;
  desativar)
    [ -n "$GROUPS_WANTED" ] || { echo "Diga os grupos, separados por vírgula. Exemplo: ./debloat.sh $1 desativar anuncios,telemetria" >&2; exit 1; }
    SEL=$(mktemp); selected > "$SEL"
    ok=0; pulados=0; recusados=0
    while read -r g pkg note; do
      if ! sh_ pm list packages | grep -q "^package:$pkg$"; then pulados=$((pulados+1)); continue; fi
      out=$(sh_ pm disable-user --user 0 "$pkg")
      case "$out" in
        *"new state: disabled"*) echo "  desativado  [$g] $pkg  ($note)"; echo "$pkg" >> debloat-desativados.txt; ok=$((ok+1)) ;;
        *) echo "  RECUSADO    [$g] $pkg: $out"; recusados=$((recusados+1)) ;;
      esac
    done < "$SEL"
    rm -f "$SEL"
    [ -f debloat-desativados.txt ] && sort -u debloat-desativados.txt -o debloat-desativados.txt
    echo "Pronto: $ok desativados, $recusados recusados pelo Fire OS, $pulados não instalados."
    echo "Reinicie a TV para a memória ser liberada de vez. Para voltar atrás: ./debloat.sh $1 desfazer"
    echo "A guarda do Vitro só conhece os apps que estavam nesta lista quando ele foi compilado: rode ./build.sh e ./instalar.sh $1."
    ;;
  desfazer)
    SEL=$(mktemp); selected > "$SEL"
    n=0
    while read -r g pkg note; do
      if sh_ pm list packages -d | grep -q "^package:$pkg$"; then
        out=$(sh_ pm enable "$pkg")
        echo "  reativado   [$g] $pkg: ${out##*: }"; n=$((n+1))
      fi
    done < "$SEL"
    rm -f "$SEL"
    if [ -z "$GROUPS_WANTED" ]; then rm -f debloat-desativados.txt; fi
    echo "Pronto: $n pacotes reativados."
    ;;
  *)
    echo "Modo desconhecido: $MODE (use ver, simular, desativar ou desfazer)" >&2; exit 1 ;;
esac
