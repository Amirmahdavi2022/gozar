#!/usr/bin/env bash
#
# Puts a starting endpoint list inside the apk.
#
# 🚨 Why this exists. The fast engine stands down when its pool is empty, and the pool is filled
# by fetching public lists — which are hosted exactly where they are blocked, so the fetch has to
# go through a tunnel that is already up. On a fresh install there is no tunnel and no pool, so
# the fast engine was skipped on every single launch, for ever, and the app was Tor and nothing
# else. Shipping a list breaks that circle: the engine has something to dial on the first press.
#
# The list goes stale — feeds rebuild every quarter of an hour and a build is older than that by
# the time anyone installs it. That is fine and is not the point. Enough of it survives to get one
# tunnel up, and the moment one is up the app refreshes the pool itself and never reads this again.
#
# Fetched as data. Nobody's code is copied into this repo, and the sources are read from
# ConfigSources.java so the two cannot drift apart.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sources_java="$root/app/src/main/java/xyz/jmc/gozar/direct/ConfigSources.java"
out="$root/app/src/main/assets/seed.txt"
mkdir -p "$(dirname "$out")"

mapfile -t urls < <(python3 - "$sources_java" <<'PY'
import re, sys
body = open(sys.argv[1], encoding="utf-8").read()
block = re.search(r"SOURCES\s*=\s*\{(.*?)\n\s*\};", body, re.S).group(1)
for host, path, _label in re.findall(r'\{\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\}', block):
    print("https://" + host + path)
PY
)

[ "${#urls[@]}" -gt 0 ] || { echo "no sources found in ConfigSources.java" >&2; exit 1; }

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
ok=0
for url in "${urls[@]}"; do
  if curl -fsS --max-time 45 -A "Mozilla/5.0" "$url" >> "$tmp" 2>/dev/null; then
    printf '\n' >> "$tmp"
    ok=$((ok + 1))
    echo "seed: read $url"
  else
    echo "seed: could not read $url" >&2
  fi
done

# A source that answers with a base64 blob is left as it is: the parser on the phone handles both
# shapes, and decoding here would only add a second place for it to be got wrong.
# 🚨 hysteria2 and hy2, and nothing else. Adding or removing a protocol in ConfigSources.java is
# only half the job — it has to survive this line too, and the last time the two drifted apart the
# engine shipped with nothing whatsoever to dial and stood down on every launch with "nothing to
# bootstrap from yet". The other schemes are gone because the core that spoke them is gone; a seed
# full of vless lines would just be weight nothing can dial.
grep -aoE '(hysteria2|hy2)://[^[:space:]"<]+' "$tmp" | sort -u | head -900 > "$out" || true

lines="$(wc -l < "$out" | tr -d ' ')"
echo "seed: $ok/${#urls[@]} sources, $lines endpoints -> $out"

# A build that quietly ships an empty seed is the bug this script exists to prevent, so it fails
# loudly instead. The app still works without one; it just goes back to being Tor-only.
[ "$lines" -ge 50 ] || { echo "seed came out too small to be worth shipping" >&2; exit 1; }
