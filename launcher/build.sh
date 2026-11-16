#!/bin/sh
# Gera out/vitro.apk. Sem Gradle: só as ferramentas do próprio Android SDK, então compila em segundos.
# Precisa de Java 17 e do Android SDK (platforms;android-34 e build-tools;34.0.0).
# Abordagem de build emprestada do FireFrame e do TV Detox, ambos sob licença MIT.
#
# Os arquivos intermediários ficam fora da pasta do projeto (em ~/Library/Caches/vitro-build, ou em VITRO_BUILD):
# serviços de sincronização como o OneDrive renomeiam uma pasta apagada e recriada no meio da sincronização.
# Só o APK final vem para out/vitro.apk.
set -e
cd "$(dirname "$0")"

[ -n "$JAVA_HOME" ] || for j in "$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" /opt/homebrew/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64; do [ -d "$j" ] && JAVA_HOME=$j && break; done
[ -n "$JAVA_HOME" ] && export JAVA_HOME && export PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || for d in "$HOME/Library/Android/sdk" /opt/homebrew/share/android-commandlinetools "$HOME/Android/Sdk"; do [ -d "$d" ] && SDK=$d && break; done
BT=$SDK/build-tools/34.0.0; JAR=$SDK/platforms/android-34/android.jar
[ -f "$JAR" ] && [ -d "$BT" ] || { echo "Android SDK não encontrado. Defina ANDROID_HOME e instale platforms;android-34 e build-tools;34.0.0." >&2; exit 1; }

B=${VITRO_BUILD:-$HOME/Library/Caches/vitro-build}
rm -rf "$B"; mkdir -p "$B/classes" "$B/dex" "$B/gen" out

# Assets do APK, montados fora da pasta do projeto: os da pasta assets/ e as duas listas do debloat.
#  - debloat-grupos.txt: "grupo pacote descrição" de cada linha do debloat.txt, para a primeira configuração
#    oferecer os grupos e mostrar o diagnóstico (ver SetupActivity; a Alexa vai junto só para ser reconhecida e
#    nunca é oferecida);
#  - debloat.txt: a lista da guarda (ver Upkeep). Com debloat-desativados.txt (o que foi desativado pelo computador
#    com o debloat.sh), é ela; sem ele, são os grupos que a primeira configuração oferece. A guarda só mexe no
#    que já estava desativado, então a lista maior não desativa nada sozinha.
A="$B/assets"; mkdir -p "$A"; cp -R assets/. "$A/"; rm -f "$A/debloat.txt"; find "$A" -name .DS_Store -delete
grep -v '^[[:space:]]*#' debloat.txt | awk -F'#' '{ n = split($1, a, " "); if (n < 2) next; d = $2
  for (i = 3; i <= NF; i++) d = d "#" $i; gsub(/^[ \t]+|[ \t]+$/, "", d); print a[1], a[2], d }' > "$A/debloat-grupos.txt"
if [ -f debloat-desativados.txt ]; then cp debloat-desativados.txt "$A/debloat.txt"
else awk '$1~/^(anuncios|telemetria|extras|apps-amazon|tv-ao-vivo|atalhos)$/ {print $2}' "$A/debloat-grupos.txt" > "$A/debloat.txt"; fi
# Android TV e Google TV (ver Platform): as mesmas duas listas, a partir do debloat-androidtv.txt. O grupo "voz"
# (Assistente) vai junto só para ser reconhecido e nunca é oferecido, como a Alexa no Fire OS.
grep -v '^[[:space:]]*#' debloat-androidtv.txt | awk -F'#' '{ n = split($1, a, " "); if (n < 2) next; d = $2
  for (i = 3; i <= NF; i++) d = d "#" $i; gsub(/^[ \t]+|[ \t]+$/, "", d); print a[1], a[2], d }' > "$A/debloat-grupos-androidtv.txt"
awk '$1~/^(anuncios|telemetria|extras|apps-google|apps-tcl)$/ {print $2}' "$A/debloat-grupos-androidtv.txt" > "$A/debloat-androidtv.txt"

"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml -A "$A" --java "$B/gen" \
  --min-sdk-version 22 --target-sdk-version 28 "$B/res.zip"
# shellcheck disable=SC2046 # um argumento por arquivo, de propósito
javac -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
  -d "$B/classes" $(find src "$B/gen" -name "*.java")
# shellcheck disable=SC2046
"$BT/d8" --min-api 22 --release --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"; (cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"

# A chave de assinatura é criada no primeiro build. Guarde key.jks: atualizações precisam da mesma chave.
KS=${KEYSTORE:-key.jks}; PASS=${KEYSTORE_PASS:-vitro-dev}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias vitro \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Vitro" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out "$B/vitro.apk" "$B/aligned.apk"
cp "$B/vitro.apk" out/vitro.apk
echo "Pronto: out/vitro.apk ($(du -k out/vitro.apk | cut -f1) KB)"
