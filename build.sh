#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="KimiRC"
VERSION_NAME="0.1.11"
VERSION_CODE=10
MIN_SDK=28
TARGET_SDK=34

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -d "$HOME/AppData/Local/Android/Sdk" ]; then
    SDK="$HOME/AppData/Local/Android/Sdk"
fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    echo "error: Android SDK not found; set ANDROID_HOME or ANDROID_SDK_ROOT" >&2
    exit 1
fi

BUILD_TOOLS=""
if [ -d "$SDK/build-tools" ]; then
    BUILD_TOOLS=$(ls -1 "$SDK/build-tools" | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)
fi
if [ -z "$BUILD_TOOLS" ]; then
    echo "error: no build-tools found under $SDK/build-tools" >&2
    exit 1
fi

# d8 in build-tools 34.0.0 (R8 8.2.2) chokes on the empty-name MethodParameters
# attribute that JDK 21 javac emits for inner-class constructors.
MIN_BUILD_TOOLS="35.0.0"
if [ "$(printf '%s\n%s\n' "$MIN_BUILD_TOOLS" "$BUILD_TOOLS" \
        | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)" != "$BUILD_TOOLS" ]; then
    echo "error: build-tools $MIN_BUILD_TOOLS or newer is required (found $BUILD_TOOLS)" >&2
    exit 1
fi

PLATFORM=$(ls -d "$SDK"/platforms/android-* | sed 's/.*android-//' | sort -n | tail -n 1)
ANDROID_JAR="$SDK/platforms/android-$PLATFORM/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
    echo "error: android.jar not found for platform android-$PLATFORM" >&2
    exit 1
fi

pick() {
    local base="$1"
    local candidate
    for candidate in "$base" "$base.exe" "$base.bat" "$base.cmd"; do
        if [ -f "$candidate" ]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    return 1
}

AAPT2=$(pick "$SDK/build-tools/$BUILD_TOOLS/aapt2") || { echo "error: aapt2 missing in build-tools $BUILD_TOOLS" >&2; exit 1; }
D8=$(pick "$SDK/build-tools/$BUILD_TOOLS/d8") || { echo "error: d8 missing in build-tools $BUILD_TOOLS" >&2; exit 1; }
ZIPALIGN=$(pick "$SDK/build-tools/$BUILD_TOOLS/zipalign") || { echo "error: zipalign missing in build-tools $BUILD_TOOLS" >&2; exit 1; }
APKSIGNER=$(pick "$SDK/build-tools/$BUILD_TOOLS/apksigner") || { echo "error: apksigner missing in build-tools $BUILD_TOOLS" >&2; exit 1; }

JAVAC=$(command -v javac || true)
if [ -z "$JAVAC" ]; then
    echo "error: javac (a JDK) is required on PATH" >&2
    exit 1
fi

REQUIRED_JAVA=21
JAVAC_VERSION=$("$JAVAC" -version 2>&1 | sed -n '1s/^javac \([0-9][0-9.]*\).*/\1/p')
case "$JAVAC_VERSION" in
    1.*) JAVAC_MAJOR=${JAVAC_VERSION#1.}; JAVAC_MAJOR=${JAVAC_MAJOR%%.*} ;;
    *)   JAVAC_MAJOR=${JAVAC_VERSION%%.*} ;;
esac
if [ -z "$JAVAC_MAJOR" ] || [ "$JAVAC_MAJOR" -lt "$REQUIRED_JAVA" ]; then
    echo "error: JDK $REQUIRED_JAVA or newer is required to build (found: ${JAVAC_VERSION:-unknown})" >&2
    exit 1
fi

KEYTOOL=$(command -v keytool || true)
if [ -z "$KEYTOOL" ]; then
    KEYTOOL=$(pick "$(dirname "$JAVAC")/keytool") || { echo "error: keytool not found next to javac" >&2; exit 1; }
fi

PYTHON=""
if command -v python3 >/dev/null 2>&1 && python3 -c 'pass' >/dev/null 2>&1; then
    PYTHON=python3
elif command -v python >/dev/null 2>&1 && python -c 'pass' >/dev/null 2>&1; then
    PYTHON=python
fi
HAVE_ZIP=0
if command -v zip >/dev/null 2>&1; then
    HAVE_ZIP=1
fi
if [ -z "$PYTHON" ] && [ "$HAVE_ZIP" -eq 0 ]; then
    echo "error: need python3/python or zip to package the APK" >&2
    exit 1
fi

ZXING_VERSION="3.5.3"
ZXING_JAR="libs/zxing-core-${ZXING_VERSION}.jar"
ZXING_URL="https://repo1.maven.org/maven2/com/google/zxing/core/${ZXING_VERSION}/core-${ZXING_VERSION}.jar"
ZXING_SHA256="8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82"

if [ ! -f "$ZXING_JAR" ]; then
    echo "==> downloading zxing-core $ZXING_VERSION"
    mkdir -p libs
    curl -fSL -o "$ZXING_JAR" "$ZXING_URL"
fi
echo "$ZXING_SHA256  $ZXING_JAR" | sha256sum -c - >/dev/null || {
    echo "error: zxing-core jar checksum mismatch" >&2
    exit 1
}

echo "==> SDK: $SDK (platform android-$PLATFORM, build-tools $BUILD_TOOLS)"

rm -rf build
mkdir -p build/gen build/classes build/dex dist

echo "==> compiling resources"
"$AAPT2" compile --dir res -o build/res.zip

echo "==> linking"
"$AAPT2" link build/res.zip \
    --manifest AndroidManifest.xml \
    -I "$ANDROID_JAR" \
    --java build/gen \
    -o build/app.ap_ \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME"

echo "==> compiling java"
find src build/gen -name '*.java' | sort > build/sources.txt
"$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" -classpath "$ZXING_JAR" \
    -d build/classes @build/sources.txt

echo "==> dexing"
# shellcheck disable=SC2046
"$D8" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output build/dex \
    $(find build/classes -name '*.class') "$ZXING_JAR"

echo "==> packaging"
if [ -n "$PYTHON" ]; then
    "$PYTHON" - <<'PYEOF'
import zipfile

with zipfile.ZipFile("build/app.ap_") as zin, \
        zipfile.ZipFile("build/app-unaligned.apk", "w",
                        zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write("build/dex/classes.dex", "classes.dex")
PYEOF
else
    cp build/app.ap_ build/app-unaligned.apk
    (cd build/dex && zip -q ../app-unaligned.apk classes.dex)
fi

echo "==> aligning"
"$ZIPALIGN" -f -p 4 build/app-unaligned.apk build/app-unsigned.apk

if [ -n "${RELEASE_STORE_FILE:-}" ]; then
    KS="$RELEASE_STORE_FILE"
    KS_PASS="${RELEASE_STORE_PASSWORD:?RELEASE_STORE_PASSWORD is required}"
    KEY_ALIAS="${RELEASE_KEY_ALIAS:?RELEASE_KEY_ALIAS is required}"
    KEY_PASS="${RELEASE_KEY_PASSWORD:?RELEASE_KEY_PASSWORD is required}"
    echo "==> signing with release keystore"
else
    KS="$HOME/.android/debug.keystore"
    KS_PASS=android
    KEY_ALIAS=androiddebugkey
    KEY_PASS=android
    if [ ! -f "$KS" ]; then
        echo "==> generating debug keystore"
        mkdir -p "$(dirname "$KS")"
        "$KEYTOOL" -genkeypair -v -keystore "$KS" \
            -storepass "$KS_PASS" -keypass "$KEY_PASS" \
            -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=KimiRC Debug, OU=KimiRC, O=KimiRC, C=CN" >/dev/null 2>&1
    fi
    echo "==> signing with debug keystore"
fi

OUT="dist/${APP_NAME}-${VERSION_NAME}.apk"
"$APKSIGNER" sign --ks "$KS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT" build/app-unsigned.apk

echo "==> verifying"
"$ZIPALIGN" -c -p 4 "$OUT"
"$APKSIGNER" verify --print-certs "$OUT" | grep -i "SHA-256" || true

echo "==> done: $OUT"
sha256sum "$OUT" 2>/dev/null || certutil -hashfile "$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")" SHA256 2>/dev/null || true
