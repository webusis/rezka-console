#!/bin/bash
# Сборка APK консоли: aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"

# Тулчейн: build-tools android-14 (aapt2, d8, zipalign, apksigner) + android.jar 4.2.2.
# Ищем по порядку: $ANDROID_TOOLCHAIN, ./android, ~/android-toolchain.
find_toolchain() {
  for d in "${ANDROID_TOOLCHAIN:-}" "$PWD/android" "$HOME/android-toolchain"; do
    [ -n "$d" ] && [ -d "$d/android-14" ] && [ -f "$d/android-4.2.2/android.jar" ] && { echo "$d"; return 0; }
  done
  return 1
}
A=$(find_toolchain) || {
  echo "нет тулчейна." >&2
  echo "нужно: <dir>/android-14/{aapt2,d8,zipalign,apksigner} и <dir>/android-4.2.2/android.jar" >&2
  echo "положить в ./android или указать ANDROID_TOOLCHAIN=/путь" >&2
  exit 1
}
BT=$A/android-14
JAR=$A/android-4.2.2/android.jar
# ключ подписи: свой или тот же, что у прежних сборок, — иначе апдейт поверх
# установленного APK не встанет (INSTALL_FAILED_UPDATE_INCOMPATIBLE)
KS=${KEYSTORE:-$A/debug.keystore}
OUT=RezkaConsole.apk

echo "[0/6] нативная lib (PTY)"
JNI_H="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")/include"
mkdir -p lib/armeabi-v7a
if [ ! -f lib/armeabi-v7a/librezka_pty.so ] || [ jni/pty.c -nt lib/armeabi-v7a/librezka_pty.so ]; then
  # ВАЖНО: --hash-style=sysv и --build-id=none — старый linker Android 4.2
  # не знает .gnu.hash и падает SIGSEGV при dlopen (проверено на планшете).
  arm-linux-gnueabi-gcc -shared -fPIC -O2 -march=armv7-a -mfloat-abi=softfp -nostdlib \
    -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables -fno-stack-protector \
    -Wl,--hash-style=sysv -Wl,--build-id=none \
    -I "$JNI_H" -I "$JNI_H/linux" \
    -o lib/armeabi-v7a/librezka_pty.so jni/pty.c
  echo "  скомпилирована librezka_pty.so ($(du -h lib/armeabi-v7a/librezka_pty.so | cut -f1))"
fi

echo "[1/6] aapt2 compile+link"
rm -rf gen obj dex
mkdir -p gen obj dex
$BT/aapt2 compile --dir res -o compiled.flata
$BT/aapt2 link -o app.unsigned.apk \
    -I "$JAR" \
    --manifest AndroidManifest.xml \
    --java gen \
    -R compiled.flata \
    --auto-add-overlay

echo "[2/6] javac"
SRCS=$(find src gen -name "*.java")
javac -source 1.7 -target 1.7 -classpath "$JAR" -d obj $SRCS
CLASSES=$(find obj -name "*.class")

echo "[3/6] d8 -> classes.dex"
$BT/d8 --lib "$JAR" --release --min-api 17 --output dex $CLASSES
test -s dex/classes.dex || { echo "ОШИБКА: classes.dex пустой/отсутствует"; exit 1; }

echo "[4/6] dex + lib в apk"
( cd dex && zip -q ../app.unsigned.apk classes.dex )
zip -q app.unsigned.apk lib/armeabi-v7a/librezka_pty.so

echo "[5/6] zipalign"
$BT/zipalign -f 4 app.unsigned.apk app.aligned.apk

echo "[6/6] ключ и подпись"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias rezkadebug -keyalg RSA \
    -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=RezkaDebug,O=Rezka,C=BY" 2>/dev/null
fi
$BT/apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" app.aligned.apk

rm -f compiled.flata app.unsigned.apk app.aligned.apk
echo "Готово: $OUT ($(du -h $OUT | cut -f1))"
