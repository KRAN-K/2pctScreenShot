#!/usr/bin/env bash
# Builds 2%ScreenShot without Gradle: aapt2 + javac + d8 + zipalign + apksigner. Debug-signed.
# Usage: ANDROID_SDK=/path/to/sdk [KEYSTORE=/path/dbg.keystore] ./build-apk.sh
set -euo pipefail
SDK="${ANDROID_SDK:?set ANDROID_SDK to your Android SDK folder}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$SDK/platforms/android-34/android.jar"
KS="${KEYSTORE:-$HOME/dbg.keystore}"
HERE="$(cd "$(dirname "$0")" && pwd)"
VER="$(grep -oP 'versionName "\K[^"]+' "$HERE/app/build.gradle")"
VCODE="$(grep -oP 'versionCode \K[0-9]+' "$HERE/app/build.gradle")"
B="$HERE/build"; rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/dex"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias dbg \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
"$BT/aapt2" compile --dir "$HERE/app/src/main/res" -o "$B/res.zip"
# Gradle injects the package from "namespace"; do the same here
sed 's|<manifest |<manifest package="com.twopercent.screenshot" |' "$HERE/app/src/main/AndroidManifest.xml" > "$B/AndroidManifest.xml"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest "$B/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 34 --version-code "$VCODE" --version-name "$VER" \
  --java "$B/gen" "$B/res.zip"
javac -source 8 -target 8 -g:none -Xlint:-options -cp "$JAR" -d "$B/classes" \
  $(find "$HERE/app/src/main/java" "$B/gen" -name '*.java')
java -cp "${R8_JAR:-$BT/lib/d8.jar}" com.android.tools.r8.D8 --release --min-api 29 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -j "$B/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
OUT="$HERE/releases/2pctScreenShot-v$VER.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias dbg --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT" && echo "Built $OUT"
