#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK:?Set ANDROID_HOME to your Android SDK directory}"
TOOLS="$SDK/build-tools/36.0.0"
ANDROID="$SDK/platforms/android-36/android.jar"
for tool in aapt2 d8 zipalign; do
  test -x "$TOOLS/$tool" || { echo "Missing Android build tool: $TOOLS/$tool" >&2; exit 1; }
done
test -f "$ANDROID" || { echo "Missing Android 36 platform" >&2; exit 1; }
test -f "$TOOLS/lib/apksigner.jar" || { echo "Missing APK signer" >&2; exit 1; }
for cmd in javac java jar python3; do command -v "$cmd" >/dev/null; done

mkdir -p out/host out/classes out/dex
javac --release 8 -encoding UTF-8 -d out/host \
  src/app/jabs/apkcatcher/ApkArchive.java \
  src/app/jabs/apkcatcher/GithubShare.java \
  test/app/jabs/apkcatcher/ApkArchiveTest.java \
  test/app/jabs/apkcatcher/GithubShareTest.java
java -cp out/host app.jabs.apkcatcher.ApkArchiveTest
java -cp out/host app.jabs.apkcatcher.GithubShareTest

"$TOOLS/aapt2" compile --dir res -o out/resources.zip
"$TOOLS/aapt2" link -I "$ANDROID" --manifest AndroidManifest.xml -o out/unsigned.apk out/resources.zip
javac -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID" -d out/classes src/app/jabs/apkcatcher/*.java
jar cf out/classes.jar -C out/classes .
"$TOOLS/d8" --release --min-api 26 --lib "$ANDROID" --output out/dex out/classes.jar
python3 - <<'PY'
from zipfile import ZipFile, ZIP_DEFLATED
with ZipFile('out/unsigned.apk', 'a') as apk:
    apk.write('out/dex/classes.dex', 'classes.dex', compress_type=ZIP_DEFLATED)
PY
"$TOOLS/zipalign" -f 4 out/unsigned.apk out/APK-Catcher-unsigned.apk
"$TOOLS/zipalign" -c 4 out/APK-Catcher-unsigned.apk
"$TOOLS/aapt2" dump badging out/APK-Catcher-unsigned.apk | tee out/badging.txt
"$TOOLS/aapt2" dump xmltree --file AndroidManifest.xml out/APK-Catcher-unsigned.apk > out/manifest.txt
java -cp out/host app.jabs.apkcatcher.ApkArchiveTest out/APK-Catcher-unsigned.apk | tee out/tests.txt
java -cp out/host app.jabs.apkcatcher.GithubShareTest | tee -a out/tests.txt
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
b=Path('out/badging.txt').read_text()
m=Path('out/manifest.txt').read_text()
assert "package: name='app.jabs.apkcatcher'" in b
assert "versionCode='2'" in b and "versionName='1.1'" in b
assert ("minSdkVersion:'26'" in b or "sdkVersion:'26'" in b) and "targetSdkVersion:'36'" in b
assert 'launchable-activity:' not in b
assert 'android.intent.category.LAUNCHER' not in m
assert 'android.permission.REQUEST_INSTALL_PACKAGES' in b
assert 'android.permission.INTERNET' in m
assert 'READ_EXTERNAL_STORAGE' not in m and 'MANAGE_EXTERNAL_STORAGE' not in m
with ZipFile('out/APK-Catcher-unsigned.apk') as z:
    assert z.testzip() is None
    assert z.read('classes.dex').startswith(b'dex\n')
print('APK structure and manifest checks passed')
PY
cp "$TOOLS/lib/apksigner.jar" out/apksigner.jar
