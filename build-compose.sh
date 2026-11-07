#!/bin/bash
# Manual Compose + MiUIX build for the PDD LiquidGlass About screen.
# Adapted from Example's build-manual-compose.sh (Gradle daemon does not
# work in this VM). The module itself is Java (libxposed); the launcher
# About UI is Kotlin/Compose/MiUIX 0.9.4, so this script builds both.
#
# Steps: aapt2 compile/link (app + library AAR resources, generating
# R classes) -> javac the generated R.java -> kotlinc 2.4 with the
# Compose compiler plugin (UI) -> javac the Java module sources ->
# d8 (app + R + library jars + kotlin-stdlib, excluding libxposed api
# which is compileOnly) -> zip assembly (classes.dex +
# META-INF/xposed/*) -> zipalign -> apksigner.
#
# R.java is compiled BEFORE kotlinc/javac here (unlike Example, whose
# Kotlin never references the app's R class): MainActivity.kt uses
# R.drawable / R.string, so the R classes must be on the classpath.
#
# Library jars are reused from Example's build-manual-compose/jars/
# (classes.jar extracted from each AAR); AAR resources come from
# ~/tools/compose-deps/*.aar. The libxposed API jar is compileOnly.
set -euo pipefail
cd "$(dirname "$0")"
PROJECT_DIR="$PWD"

SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/34.0.0"
AAPT2="$BT/aapt2"
D8="$HOME/tools/jdk17/bin/java -cp $HOME/tools/downloads/r8-8.11.32.jar com.android.tools.r8.D8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
KOTLINC="$HOME/tools/kotlinc/bin/kotlinc"
JAVAC="$HOME/tools/jdk17/bin/javac"
JAR="$HOME/tools/jdk17/bin/jar"
XAPI="$HOME/tools/libxposed/api-classes.jar"
EXAMPLE_JARS="$HOME/workspace/study/example/build-manual-compose/jars"

VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$PROJECT_DIR/AndroidManifest.xml")"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$PROJECT_DIR/AndroidManifest.xml")"
OUT="$PROJECT_DIR/build-compose"
rm -rf "$OUT/work"
mkdir -p "$OUT/work/compiled-res" "$OUT/work/R" "$OUT/work/classes" "$OUT/work/dex" "$OUT/classes"

echo "== 1. Compile + link resources (app + AARs) =="
i=0
for aar in "$HOME"/tools/compose-deps/*.aar; do
  case "$aar" in
    *lifecycle-common-android*|*lifecycle-livedata-android*|*lifecycle-runtime-2.8.7*|*lifecycle-runtime-2.9.2.aar|*lifecycle-runtime-ktx*|*lifecycle-viewmodel-2.9.2.aar|*savedstate-1.3.1.aar|*lifecycle-runtime-old*) continue ;;
  esac
  dir="$OUT/work/aar-res-$i"; mkdir -p "$dir"
  if unzip -q "$aar" 'res/*' -d "$dir" 2>/dev/null && [ -d "$dir/res" ]; then
    "$AAPT2" compile --dir "$dir/res" -o "$OUT/work/compiled-res/lib-$i.zip" 2>/dev/null || true
  fi
  i=$((i+1))
done
"$AAPT2" compile --dir "$PROJECT_DIR/res" -o "$OUT/work/compiled-res/app.zip"

cp "$PROJECT_DIR/AndroidManifest.xml" "$OUT/work/AndroidManifest.xml"
# (No package injection: this manifest already declares its package,
# unlike Example's Gradle-namespaced manifest.)

EXTRA=""
for pkg in androidx.activity androidx.activity.compose androidx.collection androidx.compose.animation androidx.compose.animation.core androidx.compose.animation.graphics androidx.compose.foundation androidx.compose.foundation.layout androidx.compose.material3.windowsizeclass androidx.compose.runtime androidx.compose.runtime.retain androidx.compose.runtime.saveable androidx.compose.ui androidx.compose.ui.geometry androidx.compose.ui.graphics androidx.compose.ui.text androidx.compose.ui.unit androidx.compose.ui.util androidx.core androidx.core.ktx androidx.core.viewtree androidx.customview androidx.customview.poolingcontainer androidx.emoji2 androidx.emoji2.widget androidx.lifecycle.runtime androidx.lifecycle.runtime.compose androidx.lifecycle.viewmodel androidx.lifecycle.viewmodel.savedstate androidx.navigationevent androidx.navigationevent.compose androidx.profileinstaller androidx.savedstate androidx.savedstate.compose androidx.startup androidx.window top.yukonga.miuix.kmp.core top.yukonga.miuix.kmp.shader top.yukonga.miuix.kmp.squircle top.yukonga.miuix.kmp.ui com.materialkolor.colorutilities org.jetbrains.androidx.compose.material3.windowsizeclass; do
  EXTRA="$EXTRA --extra-packages $pkg"
done

"$AAPT2" link -o "$OUT/work/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$OUT/work/AndroidManifest.xml" \
  --java "$OUT/work/R" \
  $EXTRA \
  --debug-mode \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION" \
  --auto-add-overlay \
  "$OUT/work/compiled-res/"*.zip

echo "== 2. Compile generated R.java (needed before kotlinc/javac) =="
find "$OUT/work/R" -name '*.java' > "$OUT/work/r-sources.txt"
"$JAVAC" -source 17 -target 17 -cp "$ANDROID_JAR" -d "$OUT/work/classes" @"$OUT/work/r-sources.txt"

echo "== 3. Compile Kotlin UI with Compose plugin =="
CP=$(ls "$EXAMPLE_JARS/"*.jar | tr '\n' ':')
CP="$CP$ANDROID_JAR:$XAPI:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar:$OUT/work/classes"
"$KOTLINC" -jvm-target 17 \
  -Xplugin="$HOME/tools/kotlinc/lib/compose-compiler-plugin.jar" \
  -P plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true \
  -cp "$CP" -d "$OUT/classes" \
  "$PROJECT_DIR/src/io/github/lengroo/pddliquidglass/"*.kt

echo "== 4. Compile Java module sources =="
find "$PROJECT_DIR/src" -name '*.java' > "$OUT/work/java-sources.txt"
"$JAVAC" -encoding UTF-8 --release 11 -Xlint:-options \
  -classpath "$ANDROID_JAR:$XAPI:$OUT/work/classes" \
  -d "$OUT/classes" \
  @"$OUT/work/java-sources.txt"

echo "== 5. Dex (excluding libxposed api) =="
DEX_CP=""
for j in "$EXAMPLE_JARS"/*.jar; do
  case "$j" in
    *lifecycle-runtime-2.6.2*) continue ;;
  esac
  DEX_CP="$DEX_CP $j"
done
(cd "$OUT/classes" && "$JAR" cf "$OUT/work/app-classes.jar" .)
(cd "$OUT/work/classes" && "$JAR" cf "$OUT/work/r-classes.jar" .)
$D8 --release --min-api 26 --output "$OUT/work/dex" \
  "$OUT/work/app-classes.jar" "$OUT/work/r-classes.jar" $DEX_CP "$HOME/tools/kotlinc/lib/kotlin-stdlib.jar"

echo "== 6. Assemble, zipalign, sign =="
python3 - "$OUT/work/dex" "$OUT/work/base.apk" "$OUT/work/unsigned.apk" << 'PY'
import sys, zipfile, pathlib
dexdir, base, out = sys.argv[1:4]
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    if item.filename.startswith('classes') and item.filename.endswith('.dex'):
        continue
    zout.writestr(item, zin.read(item.filename))
zin.close()
for dex in sorted(pathlib.Path(dexdir).glob('*.dex')):
    zout.write(dex, dex.name)
PY
# Add the LSPosed module files.
python3 - "$OUT/work/unsigned.apk" "$PROJECT_DIR/META-INF" << 'PY'
import sys, zipfile, pathlib
apk, metainf = sys.argv[1:3]
with zipfile.ZipFile(apk, 'a') as z:
    for f in sorted(pathlib.Path(metainf).rglob('*')):
        if f.is_file():
            z.write(f, f.relative_to(pathlib.Path(metainf).parent),
                    compress_type=zipfile.ZIP_DEFLATED)
PY
"$ZIPALIGN" -f 4 "$OUT/work/unsigned.apk" "$OUT/work/aligned.apk"
# Release signing: the release keystore is the default (since 1.0.18, the
# debug key is no longer used for releases). Override with KEYSTORE /
# KEYSTORE_PASS / KEY_ALIAS. The password is never stored in this file
# (it is public); it comes from KEYSTORE_PASS or from
# ~/workspace/keystores/pddlg-release.pass.
KS="${KEYSTORE:-$HOME/workspace/keystores/pddlg-release.keystore}"
[ -f "$KS" ] || { echo "keystore not found: $KS" >&2; exit 1; }
if [ -n "${KEYSTORE_PASS:-}" ]; then
    KS_PASS="$KEYSTORE_PASS"
elif [ -f "$HOME/workspace/keystores/pddlg-release.pass" ]; then
    KS_PASS="$(cat "$HOME/workspace/keystores/pddlg-release.pass")"
else
    echo "KEYSTORE_PASS is not set and no pass file found" >&2; exit 1
fi
KEY_PASS="${KEY_PASS:-$KS_PASS}"
KEY_ALIAS_FINAL="${KEY_ALIAS:-pddlg}"
SIGN_ARGS=(--ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS_FINAL")
FINAL="$PROJECT_DIR/PddLG-v$VERSION.apk"
"$APKSIGNER" sign "${SIGN_ARGS[@]}" --out "$FINAL" "$OUT/work/aligned.apk"
"$APKSIGNER" verify --print-certs "$FINAL" | head -3
ls -la "$FINAL"
