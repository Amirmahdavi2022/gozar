#!/usr/bin/env bash
#
# Builds the two native programs the direct engine needs, from source, pinned by tag AND by the
# commit that tag resolves to. A tag can be moved; pinning the version alone would let the source
# change under a number that looks unchanged, and one of these shapes the user's traffic, which is
# the last thing to take on trust.
#
# Both land in jniLibs as libNAME.so even though neither is a library. Since Android 10 an app
# targeting API 29+ may not execute a file it wrote into its own data directory, and the
# installer's native library directory is the only place left. The installer puts the file there
# 0755 and ABI-correct, so there is no copy and no chmod at runtime.
#
# Neither is loaded with System.loadLibrary. They are started with ProcessBuilder, which is also
# why they cannot collide with IPtProxy: that one is a gomobile library and an app can carry only
# one of those. A separate process shares nothing with it.
set -euo pipefail

XRAY_VERSION="v26.3.27"
XRAY_COMMIT="d2758a023cd7f4174a5a5fa4ff66e487d4342ba0"

# Pinned the same way and for the same reason: a tag can be moved, a commit cannot.
HYSTERIA_VERSION="app/v2.6.5"
HYSTERIA_COMMIT="55e70a5f446fb4002f721de0b32bae6d6b03f164"
BYEDPI_VERSION="v0.17.3"
BYEDPI_COMMIT="7efde1b1296eaaa187b70e951894dde17527489c"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="$root/app/src/main/jniLibs"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT

abis=("arm64-v8a" "armeabi-v7a" "x86_64")

resolve_ndk() {
  local candidates=()
  [ -n "${ANDROID_NDK_HOME:-}" ] && candidates+=("$ANDROID_NDK_HOME")
  [ -n "${ANDROID_NDK_ROOT:-}" ] && candidates+=("$ANDROID_NDK_ROOT")
  [ -n "${ANDROID_HOME:-}" ] && candidates+=("$ANDROID_HOME/ndk/$NDK_VERSION")
  [ -n "${ANDROID_SDK_ROOT:-}" ] && candidates+=("$ANDROID_SDK_ROOT/ndk/$NDK_VERSION")
  for c in "${candidates[@]}"; do
    [ -d "$c" ] && { printf '%s' "$c"; return 0; }
  done
  echo "Android NDK $NDK_VERSION is required. Install it with: sdkmanager 'ndk;$NDK_VERSION'" >&2
  return 1
}

# Reads the ELF machine field, so a binary built for the wrong architecture is caught here rather
# than as an app that starts and instantly dies on one class of phone.
elf_machine() {
  od -An -tx1 -j18 -N2 "$1" 2>/dev/null | tr -d ' \n'
}

mkdir -p "$destination"
for abi in "${abis[@]}"; do mkdir -p "$destination/$abi"; done

# --- the proxy core -----------------------------------------------------------------------
#
# Built here rather than downloaded. The project's own release workflow builds android/arm64 and
# android/amd64 and has no 32-bit ARM target at all, and this app ships armeabi-v7a; building all
# three from one pinned commit beats mixing two provenances for one engine.
if ! command -v go >/dev/null 2>&1; then
  echo "Go is required to build the proxy core. Install Go 1.26 or newer." >&2
  exit 1
fi
echo "Building the proxy core $XRAY_VERSION with $(go version)"

xray_src="$temp/xray-core"
git clone --quiet --branch "$XRAY_VERSION" --depth 1 https://github.com/XTLS/Xray-core.git "$xray_src"
head="$(git -C "$xray_src" rev-parse HEAD)"
if [ "$head" != "$XRAY_COMMIT" ]; then
  echo "Proxy core commit mismatch. Expected $XRAY_COMMIT, got $head." >&2; exit 1
fi

goarch=("arm64" "arm" "amd64")
elf=("b700" "2800" "3e00")

for i in "${!abis[@]}"; do
  abi="${abis[$i]}"
  output="$destination/$abi/libxray.so"
  rm -f "$output"

  # android is tried first for consistency; linux is the fallback and is equally correct here,
  # because CGO is off so the binary is static and Android is Linux. The usual reason to care
  # about that difference is name resolution, and the config hands the core its own resolver
  # precisely so it never asks the platform.
  built=""
  for goos in android linux; do
    if CGO_ENABLED=0 GOOS="$goos" GOARCH="${goarch[$i]}" GOARM=7 \
        go build -C "$xray_src" -o "$output" -trimpath -buildvcs=false \
        -gcflags="all=-l=4" -ldflags="-s -w -buildid=" ./main 2>/dev/null; then
      built="$goos"; break
    fi
  done
  [ -n "$built" ] || { echo "Could not build the proxy core for $abi." >&2; exit 1; }
  [ -s "$output" ] || { echo "The proxy core for $abi is empty." >&2; exit 1; }

  machine="$(elf_machine "$output")"
  if [ "$machine" != "${elf[$i]}" ]; then
    echo "Proxy core for $abi has ELF machine $machine, expected ${elf[$i]}." >&2; exit 1
  fi
  chmod 0755 "$output"
  size_mb=$(( $(stat -c%s "$output" 2>/dev/null || stat -f%z "$output") / 1048576 ))
  echo "Built the proxy core for $abi ($built/${goarch[$i]}, ${size_mb} MB)"
done

# --- the quic core ------------------------------------------------------------------------
#
# 🚨 Why a second Go core rather than teaching the first one hysteria2: it cannot. The proxy core's
# hysteria client config carries a version, an address and a port and has nowhere to put the
# password, so it can parse those lines and can never connect with them. That is why a quarter of
# every public endpoint list was being fetched, parsed, and thrown away.
#
# Lands as libquic.so beside libxray.so. Same reason for the name and the location as everything
# else here: since Android 10 an app may only execute binaries from nativeLibraryDir, and the
# packager puts anything matching lib*.so there with the right mode and the right ABI.
#
# MIT, which matters - this app is MIT too, and the obvious alternative core is licensed in a way
# that would pull the whole app along with it.
echo "Building the quic core $HYSTERIA_VERSION with $(go version)"

quic_src="$temp/hysteria"
git clone --quiet --branch "$HYSTERIA_VERSION" --depth 1 https://github.com/apernet/hysteria.git "$quic_src"
head="$(git -C "$quic_src" rev-parse HEAD)"
if [ "$head" != "$HYSTERIA_COMMIT" ]; then
  echo "Quic core commit mismatch. Expected $HYSTERIA_COMMIT, got $head." >&2; exit 1
fi

for i in "${!abis[@]}"; do
  abi="${abis[$i]}"
  output="$destination/$abi/libquic.so"
  rm -f "$output"

  # The client lives in its own Go module under app/, not at the repository root.
  built=""
  for goos in android linux; do
    if CGO_ENABLED=0 GOOS="$goos" GOARCH="${goarch[$i]}" GOARM=7 \
        go build -C "$quic_src/app" -o "$output" -trimpath -buildvcs=false \
        -ldflags="-s -w -buildid=" . 2>/dev/null; then
      built="$goos"; break
    fi
  done
  [ -n "$built" ] || { echo "Could not build the quic core for $abi." >&2; exit 1; }
  [ -s "$output" ] || { echo "The quic core for $abi is empty." >&2; exit 1; }

  machine="$(elf_machine "$output")"
  if [ "$machine" != "${elf[$i]}" ]; then
    echo "Quic core for $abi has ELF machine $machine, expected ${elf[$i]}." >&2; exit 1
  fi
  chmod 0755 "$output"
  size_mb=$(( $(stat -c%s "$output" 2>/dev/null || stat -f%z "$output") / 1048576 ))
  echo "Built the quic core for $abi ($built/${goarch[$i]}, ${size_mb} MB)"
done

# --- the edge core ------------------------------------------------------------------------
#
# 🚨 Why this one is downloaded rather than built, when everything else here is built from
# source. Two reasons, and the second is the serious one.
#
# It is Rust, so building it would mean a second cross-compilation toolchain in this script for a
# single binary. That alone would be a fair trade. But the project publishes Android builds for
# all three ABIs with published checksums, and pinning a checksum we verified by hand is a
# stronger guarantee than a build we could not run anyway: the exact bytes that go in the apk are
# the exact bytes that were checked.
#
# What it is: a censorship circumvention client that speaks MASQUE over HTTP/3 to Cloudflare's
# edge, with WireGuard and warp-in-warp as alternates. It carries no endpoint list — the edge is
# anycast — so it is the one path here that can win on a fresh install with nothing fetched first.
#
# 🔑 It registers its own account, and unlike the core this replaces, it does not need a name
# server to do it. When the direct route fails it retries over what it calls a camouflaged route:
# a Cloudflare edge address dialled directly, no DNS lookup at all, with a split client hello.
# That matters enormously here, because a program on Android has no /etc/resolv.conf and cannot
# resolve a hostname — the exact trap that killed the previous core before it ran a single line.
#
# ⚖️ AGPL-3.0, while this app is MIT. It runs as its own process and speaks SOCKS over loopback,
# so the two stay separate works and the app's licence is unaffected — but the binary's source
# must be offered to anyone who receives the apk. NOTICE carries that offer. Do not link it in.
AETHER_VERSION="v1.9.0"

# Checked by hand against the project's published SHA256SUMS.txt, then each archive was unpacked
# and its ELF machine confirmed to match the ABI it claims. Any mismatch here stops the build.
aether_sha=(
  "a5a488b8cf05b3e83c28ca35cef78334130411c8df5314850e816b900e9d6cb9"
  "d49ee19423a33d905fb4fef3f163d2e3c88e5223940e0f03dbe6324bb2c7dcdb"
  "0c4dfcea54b5a39c0a3a52473d1fb9c2ff5c4ed92de5d240f84f18f54425f961"
)
aether_abi=("arm64" "armv7" "x86_64")

echo "Fetching the edge core $AETHER_VERSION"

for i in "${!abis[@]}"; do
  abi="${abis[$i]}"
  output="$destination/$abi/libaether.so"
  archive="$temp/aether-${aether_abi[$i]}.tar.gz"
  rm -f "$output"

  url="https://github.com/CluvexStudio/Aether/releases/download/$AETHER_VERSION/aether-android-${aether_abi[$i]}.tar.gz"
  curl -fsSL --retry 3 -o "$archive" "$url"

  got="$(sha256sum "$archive" | cut -d' ' -f1)"
  if [ "$got" != "${aether_sha[$i]}" ]; then
    echo "Edge core for $abi has checksum $got, expected ${aether_sha[$i]}." >&2; exit 1
  fi

  unpacked="$temp/aether-${aether_abi[$i]}"
  rm -rf "$unpacked"; mkdir -p "$unpacked"
  tar xzf "$archive" -C "$unpacked"
  [ -f "$unpacked/aether" ] || { echo "No aether binary inside the $abi archive." >&2; exit 1; }

  cp "$unpacked/aether" "$output"

  machine="$(elf_machine "$output")"
  if [ "$machine" != "${elf[$i]}" ]; then
    echo "Edge core for $abi has ELF machine $machine, expected ${elf[$i]}." >&2; exit 1
  fi
  chmod 0755 "$output"
  size_mb=$(( $(stat -c%s "$output" 2>/dev/null || stat -f%z "$output") / 1048576 ))
  echo "Placed the edge core for $abi (${size_mb} MB)"
done

# --- the local shaping proxy --------------------------------------------------------------
#
# A local SOCKS5 server that reshapes the TLS handshake on its way out: fragmentation, reordering,
# fake packets that expire before the far end. The core already knows how to dial through a local
# SOCKS hop, so this is one more outbound rather than a new mechanism.
ndk_root="$(resolve_ndk)"
toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$toolchain" ] || toolchain="$ndk_root/toolchains/llvm/prebuilt/darwin-x86_64/bin"
[ -d "$toolchain" ] || { echo "No usable NDK toolchain under $ndk_root." >&2; exit 1; }

byedpi_src="$temp/byedpi"
git clone --quiet --branch "$BYEDPI_VERSION" --depth 1 https://github.com/hufrea/byedpi.git "$byedpi_src"
head="$(git -C "$byedpi_src" rev-parse HEAD)"
if [ "$head" != "$BYEDPI_COMMIT" ]; then
  echo "Unexpected shaping proxy commit $head; expected $BYEDPI_COMMIT." >&2; exit 1
fi

sources=""
for f in "$byedpi_src"/*.c; do
  case "$(basename "$f")" in
    win_service.c) continue ;;   # the Windows service entry point; there is no such thing here
  esac
  sources="$sources $f"
done

for abi in "${abis[@]}"; do
  case "$abi" in
    arm64-v8a)   triple="aarch64-linux-android" ;;
    armeabi-v7a) triple="armv7a-linux-androideabi" ;;
    x86_64)      triple="x86_64-linux-android" ;;
    *) echo "No toolchain triple for $abi." >&2; exit 1 ;;
  esac
  # -D_DEFAULT_SOURCE comes from byedpi's own Makefile and is not optional: without it c99 hides
  # the POSIX declarations this source needs and the build fails in a way that reads like a broken
  # checkout rather than a missing flag.
  # shellcheck disable=SC2086
  "$toolchain/${triple}24-clang" \
    -D_DEFAULT_SOURCE -std=c99 -O2 -w \
    -I"$byedpi_src" \
    -o "$destination/$abi/libbyedpi.so" \
    $sources
  size="$(stat -c%s "$destination/$abi/libbyedpi.so" 2>/dev/null || stat -f%z "$destination/$abi/libbyedpi.so")"
  if [ "$size" -lt 20000 ]; then
    echo "The shaping proxy for $abi is only $size bytes; something did not link." >&2; exit 1
  fi
  echo "Built the shaping proxy for $abi ($size bytes)"
done

echo "Native programs staged in $destination"
