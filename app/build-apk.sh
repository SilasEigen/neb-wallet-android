#!/bin/bash
# Builds neb-wallet.apk without Gradle: aapt2, javac, d8, zipalign and apksigner from the Android SDK, and a JDK.
# Run ../build.sh first. The package is signed with the debug key of the Android SDK: a test build, not a release.
set -euo pipefail
unset MSYS_NO_PATHCONV  # Git Bash must convert the paths given to the Windows tools
cd "$(dirname "$0")"
unix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi; }
sdk=$(unix "${ANDROID_HOME:-${ANDROID_SDK_ROOT:?set ANDROID_HOME}}")
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
jar=$(ls -d "$sdk"/platforms/android-* | sort -V | tail -1)/android.jar
bat=""; [ -f "$bt/d8.bat" ] && bat=".bat"
rm -rf build && mkdir -p build/classes build/apk
# Android only lets an app execute files that are packaged as native libraries: lib<name>.so
for abi in arm64-v8a x86_64; do
  mkdir -p build/apk/lib/$abi
  cp ../bin/$abi/neb-wallet build/apk/lib/$abi/libnebwallet.so
done

"$bt/aapt2" compile --dir res -o build/res.zip
"$bt/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$jar" --java build/gen build/res.zip
javac -encoding UTF-8 --release 11 -cp "$jar" -d build/classes java/org/neuralenginebyte/wallet/*.java build/gen/org/neuralenginebyte/wallet/R.java
"$bt/d8$bat" --lib "$jar" --min-api 29 --output build/apk $(find build/classes -name '*.class')
(cd build/apk && "$bt/aapt" add ../base.apk classes.dex lib/*/*.so >/dev/null)
"$bt/zipalign" -f -p 4 build/base.apk build/aligned.apk
"$bt/apksigner$bat" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out ../neb-wallet.apk build/aligned.apk
rm -rf build
ls -l ../neb-wallet.apk
