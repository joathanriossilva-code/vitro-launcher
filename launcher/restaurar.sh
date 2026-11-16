#!/bin/sh
# Restaura o Vitro e todos os ajustes numa Fire TV, com um comando só. Para quando o autorreparo do Vitro não
# basta: reset de fábrica, Vitro desinstalado, ou uma atualização que desligou o ADB e tirou as permissões.
# Uso: ./restaurar.sh 192.168.1.50 [--sem-debloat]
#
# Antes, na TV: Configurações > Minha Fire TV > Opções do desenvolvedor > Depuração ADB: ligada.
#   (Sem "Opções do desenvolvedor"? Em Minha Fire TV > Sobre, clique 7 vezes no nome do aparelho.)
# Durante: se a TV perguntar "Permitir depuração USB?", marque "Sempre permitir" e confirme.
# Depois: abra o Vitro e confirme os dois avisos dele (VPN local e chave do ADB).
#
# Refaz: o Vitro (a versão compilada em out/, ou o último backup), a leitura do registro, a permissão do
# autorreparo, o Home rápido (min_futurity=0), o serviço do botão Home e o debloat (os apps de
# debloat-desativados.txt; o grupo da Alexa nunca é tocado). Tudo é reversível: ./debloat.sh <ip> desfazer
# reativa os apps, e desinstalar o Vitro desfaz o resto.
set -e
cd "$(dirname "$0")"
[ -n "$1" ] || { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
D="$1:5555"; PKG=app.vitro.launcher; SVC=$PKG/$PKG.HomeService
sh_() { "$ADB" -s "$D" shell "$@" </dev/null | tr -d '\r'; }

"$ADB" connect "$D"
echo "Se a TV perguntar, escolha \"Sempre permitir\" e aperte OK."
"$ADB" -s "$D" wait-for-device

APK=out/vitro.apk
[ -f "$APK" ] || APK=$(ls -t ../backups/vitro-*.apk 2>/dev/null | head -1)
[ -n "$APK" ] && [ -f "$APK" ] || { echo "Nenhum APK do Vitro: rode ./build.sh antes." >&2; exit 1; }

echo "1/6  Instalando o Vitro ($APK)"
"$ADB" -s "$D" install -r "$APK"

echo "2/6  Permissões: leitura do registro e autorreparo"
sh_ pm grant $PKG android.permission.READ_LOGS || echo "     (não deu: leitura do registro)"
sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS || echo "     (não deu: autorreparo)"

echo "3/6  Home rápido (alarme imediato, min_futurity=0)"
atual=$(sh_ settings get global alarm_manager_constants)
case "$atual" in
  null|"") novo="min_futurity=0" ;;
  *min_futurity=*) novo=$(echo "$atual" | sed -E 's/min_futurity=[0-9]+/min_futurity=0/') ;;
  *) novo="$atual,min_futurity=0" ;;
esac
sh_ settings put global alarm_manager_constants "$novo"

echo "4/6  Serviço do botão Home"
atual=$(sh_ settings get secure enabled_accessibility_services)
case "$atual" in
  *"$SVC"*|*"$PKG/.HomeService"*) ;;
  null|"") sh_ settings put secure enabled_accessibility_services "$SVC" ;;
  *) sh_ settings put secure enabled_accessibility_services "$atual:$SVC" ;;
esac
sh_ settings put secure accessibility_enabled 1

if [ "$2" = "--sem-debloat" ]; then
  echo "5/6  Debloat pulado (--sem-debloat)"
elif [ -f debloat-desativados.txt ]; then
  echo "5/6  Debloat: os apps de debloat-desativados.txt"
  while read -r pkg; do
    [ -n "$pkg" ] || continue
    out=$(sh_ pm disable-user --user 0 "$pkg" 2>&1 || true)
    echo "     $pkg: ${out##*: }"
  done < debloat-desativados.txt
else
  echo "5/6  Debloat: sem lista (debloat-desativados.txt); nada a fazer"
fi

echo "6/6  Pré-compilando o Vitro"
sh_ cmd package compile -m speed -f $PKG || true

echo
echo "Pronto. O Prime Video o próprio Vitro desativa (e ativa quando você o abre pela barra)."
echo "Falta só: abrir o Vitro na TV e confirmar os avisos da VPN local e da chave do ADB."
