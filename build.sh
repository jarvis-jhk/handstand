#!/usr/bin/env bash
# Self-contained build, no gradle, one resource (the launcher icon):
# aapt2 compile + link -> javac -> d8 -> zipalign -> apksigner.
# Downloads a JDK + Android build-tools into ./.tools on first run (nothing
# system-wide), so this also works unmodified on a clean CI runner.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
TOOLS=$ROOT/.tools
OUT=$ROOT/.build
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$TOOLS/sdk}}
EXTERNAL_SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
UNSIGNED=${UNSIGNED:-0}
BT_VER=35.0.0
PLATFORM=android-35
MIN_SDK=34
TARGET_SDK=35

mkdir -p "$TOOLS"

if [ -n "${JAVA_HOME:-}" ]; then
  [ -x "$JAVA_HOME/bin/javac" ] || { echo "JAVA_HOME must contain a JDK" >&2; exit 1; }
elif [ ! -x "$TOOLS/jdk/bin/javac" ]; then
  echo ">> downloading JDK 17"
  curl -fL --progress-bar -o "$TOOLS/jdk.tar.gz" \
    'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse'
  rm -rf "$TOOLS/jdk" "$TOOLS/jdk-tmp"; mkdir -p "$TOOLS/jdk-tmp"
  tar -xzf "$TOOLS/jdk.tar.gz" -C "$TOOLS/jdk-tmp" --strip-components=1
  mv "$TOOLS/jdk-tmp" "$TOOLS/jdk"; rm -f "$TOOLS/jdk.tar.gz"
fi
export JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk}
export PATH=$JAVA_HOME/bin:$PATH

BT=$SDK/build-tools/$BT_VER
ANDROID_JAR=$SDK/platforms/$PLATFORM/android.jar
if [ ! -x "$BT/aapt2" ] || [ ! -f "$ANDROID_JAR" ]; then
  [ -z "$EXTERNAL_SDK" ] || { echo "Supplied SDK needs build-tools $BT_VER and $PLATFORM" >&2; exit 1; }
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo ">> downloading Android command line tools"
    curl -fL --progress-bar -o "$TOOLS/clt.zip" \
      'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
    rm -rf "$SDK/cmdline-tools"; mkdir -p "$SDK/cmdline-tools"
    (cd "$SDK/cmdline-tools" && unzip -q "$TOOLS/clt.zip" && mv cmdline-tools latest)
    rm -f "$TOOLS/clt.zip"
  fi
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
    "build-tools;$BT_VER" "platforms;$PLATFORM" | grep -v '^\[' || true
fi

# A disposable signing key so anyone can build: CI restores a stable one from a
# secret first (see .github/workflows/release.yml), a local build just makes its own.
KS=$ROOT/keystore.jks
# the store and key password: "android" for the throwaway key, the release key's from a secret in CI
export KS_PASS=${KS_PASS:-android}
if [ "$UNSIGNED" != 1 ] && [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias handstand -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Handstand' >/dev/null
fi

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"
# Releases are tagged vN, and N is the versionCode (strictly increasing, checked by the
# release workflow). An untagged local build uses the code in VERSION_CODE, the one the
# tree was exported at, so it never claims to be newer than the release it came from.
TAG=${GITHUB_REF_NAME:-$(git describe --tags --exact-match 2>/dev/null || true)}
if [[ $TAG =~ ^v([1-9][0-9]*)$ ]]; then
  VERSION_CODE=${VERSION_CODE:-${BASH_REMATCH[1]}}
  VERSION_NAME=${VERSION_NAME:-$TAG}
else
  VERSION_CODE=${VERSION_CODE:-$(cat VERSION_CODE 2>/dev/null || echo 1)}
  VERSION_NAME=${VERSION_NAME:-dev-$(git describe --always --dirty 2>/dev/null || echo local)}
fi

[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || { echo "VERSION_CODE must be a positive integer" >&2; exit 1; }

echo ">> aapt2"
# the only resources are the launcher icon (adaptive + themed), so this is compile + link
mkdir -p "$OUT/res"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unaligned.apk" --manifest AndroidManifest.xml -I "$ANDROID_JAR" "$OUT/res.zip" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo ">> javac"
find src -name '*.java' | LC_ALL=C sort > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 -proc:none \
  -bootclasspath "$ANDROID_JAR" -classpath "$BT/core-lambda-stubs.jar" -d "$OUT/classes" @"$OUT/sources.txt"

echo ">> d8"
find "$OUT/classes" -name '*.class' | LC_ALL=C sort > "$OUT/classes.txt"
"$BT/d8" --release --min-api $MIN_SDK --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"
touch -d @0 "$OUT/dex/classes.dex"
(cd "$OUT/dex" && TZ=UTC jar -uf "$OUT/unaligned.apk" classes.dex)

echo ">> zipalign + sign"
"$BT/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
if [ "$UNSIGNED" = 1 ]; then
  cp "$OUT/aligned.apk" "$ROOT/handstand.apk"
else
"$BT/apksigner" sign --alignment-preserved --ks "$KS" --ks-key-alias handstand --ks-pass env:KS_PASS --key-pass env:KS_PASS \
  --out "$ROOT/handstand.apk" "$OUT/aligned.apk"
fi
ls -l "$ROOT/handstand.apk"
