#!/bin/bash
# Builds neb-wallet for Android into bin/<abi>/: arm64-v8a for phones, x86_64 for the emulator.
# The sources are the tag below of the project, fetched from its repository (or from the clone named by NEB_REPO),
# plus patches/neb-wallet-commands.patch: sign, submit, received and export, which the application calls.
# Needs Rust with the two Android targets and the Android NDK: ANDROID_NDK_HOME, or the newest NDK under
# ANDROID_HOME/ndk. Source paths are mapped to fixed prefixes, as the release build of the project does.
set -euo pipefail
unset MSYS_NO_PATHCONV  # Git Bash must convert the paths given to the Windows tools
tag=v1.0.5
root=$(cd "$(dirname "$0")" && pwd)
project="$root/build/src"
rm -rf "$project" && mkdir -p "$project"
if [ -n "${NEB_REPO:-}" ]; then
  git -C "$NEB_REPO" archive "$tag" node pow LICENSE | tar -x -C "$project"
else
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$tag" https://github.com/SilasEigen/neb "$project"
  rm -rf "$project/.git"
fi
patch -d "$project" -p1 < "$root/patches/neb-wallet-commands.patch"
unix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi; }
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
sdk=$(unix "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}")
ndk=$(unix "${ANDROID_NDK_HOME:-$(ls -d "$sdk"/ndk/* | sort -V | tail -1)}")
tools=$(ls -d "$ndk"/toolchains/llvm/prebuilt/*/bin | head -1)
cmd=""; [ -f "$tools/aarch64-linux-android24-clang.cmd" ] && cmd=".cmd"
exe=""; [ -f "$tools/llvm-ar.exe" ] && exe=".exe"
flags=""
for pair in "$HOME=/home" "${RUSTUP_HOME:-$HOME/.rustup}=/rustup" "${CARGO_HOME:-$HOME/.cargo}=/cargo" "$project=/src"; do
  flags="$flags --remap-path-prefix=$(native "${pair%=*}")=${pair##*=}"
done
cd "$project/node"
for pair in aarch64:arm64-v8a x86_64:x86_64; do
  arch=${pair%:*}; abi=${pair#*:}; target=$arch-linux-android
  upper=$(printf '%s' "$target" | tr 'a-z-' 'A-Z_'); lower=$(printf '%s' "$target" | tr '-' '_')
  cc="$(native "$tools/${target}24-clang$cmd")"
  export "CARGO_TARGET_${upper}_LINKER=$cc" "CC_$lower=$cc" "AR_$lower=$(native "$tools/llvm-ar$exe")"
  RUSTFLAGS="$flags" cargo build --release --locked --target "$target" -p neb-wallet
  mkdir -p "$root/bin/$abi"
  cp "target/$target/release/neb-wallet" "$root/bin/$abi/"
done
ls -l "$root"/bin/*
