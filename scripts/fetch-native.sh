#!/usr/bin/env bash
#
# Puts the native programs the paths need into jniLibs: the quic core, built from source and
# pinned by tag AND by the commit that tag resolves to, and the edge core, taken from its
# project's published Android builds and checked against their published sums. A tag can be
# moved; pinning the version alone would let the source change under a number that looks
# unchanged, and these carry the user's traffic, which is the last thing to take on trust.
#
# 🚨 The TLS core and the shaping proxy used to be built here too, and they are gone with the
# engine that used them. If anything ever brings them back, they come back together - the shaping
# proxy was only ever reachable through that core.
#
# They land in jniLibs as libNAME.so even though neither is a library. Since Android 10 an app
# targeting API 29+ may not execute a file it wrote into its own data directory, and the
# installer's native library directory is the only place left. The installer puts the file there
# 0755 and ABI-correct, so there is no copy and no chmod at runtime.
#
# Neither is loaded with System.loadLibrary. They are started with ProcessBuilder, which is also
# why they cannot collide with IPtProxy: that one is a gomobile library and an app can carry only
# one of those. A separate process shares nothing with it.
set -euo pipefail

HYSTERIA_VERSION="app/v2.6.5"
HYSTERIA_COMMIT="55e70a5f446fb4002f721de0b32bae6d6b03f164"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="$root/app/src/main/jniLibs"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT

abis=("arm64-v8a" "armeabi-v7a" "x86_64")

# 🚨 These three arrays are index-matched and every section below indexes all three. They used to
# be declared halfway down inside the TLS core's section; deleting that section took them with it
# and left the rest of the script referencing arrays that no longer existed. Up here with the abi
# list, where they belong, so the next section that goes cannot take them along.
goarch=("arm64" "arm" "amd64")
elf=("b700" "2800" "3e00")


# Reads the ELF machine field, so a binary built for the wrong architecture is caught here rather
# than as an app that starts and instantly dies on one class of phone.
elf_machine() {
  od -An -tx1 -j18 -N2 "$1" 2>/dev/null | tr -d ' \n'
}

mkdir -p "$destination"
for abi in "${abis[@]}"; do mkdir -p "$destination/$abi"; done

# --- the quic core ------------------------------------------------------------------------
#
# 🚨 Why a second Go core rather than teaching the first one hysteria2: it cannot. The proxy core's
# hysteria client config carries a version, an address and a port and has nowhere to put the
# password, so it can parse those lines and can never connect with them. That is why a quarter of
# every public endpoint list was being fetched, parsed, and thrown away.
#
# Lands as libquic.so beside the edge core. Same reason for the name and the location as everything
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

echo "Native programs staged in $destination"
