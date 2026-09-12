# Third-party programs shipped inside this app

Gozar itself is MIT. The apk also carries several programs that are not ours. Each runs as its own
process and is reached over a loopback socket, so they stay separate works and none of them changes
the licence of Gozar's own code — but the ones under a copyleft licence come with obligations to
whoever receives the apk, and this file is how those are met.

## Aether — `libaether.so`

**AGPL-3.0.** Source: <https://github.com/CluvexStudio/Aether>

The exact build shipped here is release `v1.9.0`, taken unmodified from that project's published
Android archives. The SHA-256 of each archive is pinned in `scripts/fetch-native.sh` and checked at
build time, so the bytes in the apk can be matched against the ones the project published.

Anyone who receives this apk is entitled to the complete corresponding source of that binary. It is
available at the link above under the same tag. If that link is ever unreachable, open an issue on
this repository and a copy will be provided.

Gozar runs it as a separate process and talks to it over SOCKS on loopback. It is not linked into
the app, and no part of it is copied into Gozar's own sources.

## hysteria — `libquic.so`

**MIT.** Source: <https://github.com/apernet/hysteria>

Shipped unmodified. Built from a pinned tag by `scripts/fetch-native.sh`.

## hev-socks5-tunnel — `libhev-socks5-tunnel.so`

**MIT.** Source: <https://github.com/heiher/hev-socks5-tunnel>

Shipped unmodified. Built from a pinned tag.

## Tor and its pluggable transports

**BSD-3-Clause** (Tor), **BSD-3-Clause** (Snowflake and obfs4proxy, via IPtProxy).

Sources: <https://github.com/torproject/tor>, <https://gitlab.torproject.org/tpo/anti-censorship>,
<https://github.com/tladesignz/IPtProxy>

Consumed as a published library rather than rebuilt here.
