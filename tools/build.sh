#!/usr/bin/env bash
# qrxx を Android SDK の道具だけで組み立てる。Gradle は使わない。
#   使い方:  bash tools/build.sh
#   出来上がり: build/qrxx.apk  (ついでに qrxx.apk としてルートにも置く)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PKG="io.qrxx"
VERSION_CODE="${VERSION_CODE:-2}"
VERSION_NAME="${VERSION_NAME:-1.1.0}"
MIN_SDK=28          # Android 9
TARGET_SDK=36       # Android 16

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
[ -d "$SDK" ] || { echo "Android SDK が見つかりません: $SDK" >&2; exit 1; }

pick_newest() { ls "$1" 2>/dev/null | sort -V | tail -1; }
BT_VER="$(pick_newest "$SDK/build-tools")"
BT="$SDK/build-tools/$BT_VER"
PLAT_VER="$(pick_newest "$SDK/platforms")"
ANDROID_JAR="$SDK/platforms/$PLAT_VER/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "android.jar が見つかりません: $ANDROID_JAR" >&2; exit 1; }
echo "build-tools: $BT_VER / platform: $PLAT_VER"

ZXING="$ROOT/app/libs/zxing-core-3.5.3.jar"
[ -f "$ZXING" ] || { echo "ZXing の jar がありません: $ZXING" >&2; exit 1; }

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# --- 1. マニフェスト (package 属性はここで入れる) ---------------------------
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">|" \
    app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"
grep -q "package=\"$PKG\"" "$OUT/AndroidManifest.xml" || { echo "package の差し込みに失敗" >&2; exit 1; }

# --- 2. リソース ------------------------------------------------------------
"$BT/aapt2.exe" compile --dir app/src/main/res -o "$OUT/res.zip"
"$BT/aapt2.exe" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    "$OUT/res.zip" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --no-version-vectors

# --- 3. Java ----------------------------------------------------------------
# javac は Windows のプログラムなので、引数ファイルには Windows 風の綴りを渡す
find app/src/main/java "$OUT/gen" -name '*.java' -print0 | xargs -0 cygpath -m > "$OUT/sources.txt"
javac -encoding UTF-8 -source 11 -target 11 -nowarn -Xlint:-options \
    -classpath "$(cygpath -m "$ANDROID_JAR");$(cygpath -m "$ZXING")" \
    -d "$OUT/classes" "@$OUT/sources.txt"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .

# --- 4. dex -----------------------------------------------------------------
# ZXing は全バーコード形式を抱えているので、R8 に使っていない分を落とさせる。
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 \
    --release --min-api "$MIN_SDK" \
    --lib "$ANDROID_JAR" \
    --pg-conf app/proguard-rules.pro \
    --output "$OUT/dex" \
    "$OUT/classes.jar" "$ZXING"

# --- 5. 詰める --------------------------------------------------------------
( cd "$OUT/dex" && "$BT/aapt.exe" add -f "$OUT/base.apk" classes.dex >/dev/null )
"$BT/zipalign.exe" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

# --- 6. 署名 ----------------------------------------------------------------
KS="$ROOT/keystore/qrxx.jks"
KS_PASS="${KS_PASS:-qrxxstore}"
if [ ! -f "$KS" ]; then
    mkdir -p "$ROOT/keystore"
    keytool -genkeypair -keystore "$KS" -alias qrxx -keyalg RSA -keysize 2048 \
        -validity 10950 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=qrxx, OU=qrxx, O=qrxx, C=JP" >/dev/null
    echo "署名鍵を作りました: keystore/qrxx.jks (更新版を配るのに要るので無くさないこと)"
fi
"$BT/apksigner.bat" sign \
    --ks "$KS" --ks-key-alias qrxx \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v4-signing-enabled false \
    --out "$OUT/qrxx.apk" "$OUT/aligned.apk"

rm -f "$OUT/aligned.apk.idsig" "$OUT/base.apk" "$OUT/aligned.apk" "$OUT/res.zip" "$OUT/classes.jar"
cp -f "$OUT/qrxx.apk" "$ROOT/qrxx.apk"

echo
echo "できました: build/qrxx.apk  ($(du -h "$OUT/qrxx.apk" | cut -f1))"
"$BT/apksigner.bat" verify --print-certs "$OUT/qrxx.apk" | head -3
