# Gozar VPN

One button. Gozar works out how to get out on its own.

Nothing to paste, no subscription link, no server to rent. Open it, press the button, wait a few seconds.

## Download

Latest APK is on the [releases page](https://github.com/Amirmahdavi2022/gozar/releases/latest).

Grab the Universal one if you're not sure which. It installs on any phone.

## What it does

Most apps like this hand you a list of servers and leave the sorting to you. Gozar treats that as its own job.

Press connect and it starts three different ways out at once, then keeps whichever one actually carries traffic. Starting isn't enough. A dead server and a live one both accept a connection on your phone in about a millisecond, so nothing counts as connected here until a real request has gone out and come back.

While you're using the winner, another one keeps running quietly behind it. When the first one dies, and sooner or later it does, there's already a live tunnel sitting there to move onto. You get a hiccup instead of a reconnect. If the reserve dies while it's waiting, it gets replaced too.

There's also a floor on the live tunnel. Answering a health check isn't the same as working, and a connection handing over a few hundred bytes a second will pass every check you give it while nothing on your phone loads. So the traffic counters get watched as well, and a tunnel that's technically up and practically useless gets swapped out like a dead one.

It remembers, per network. Whatever worked on your mobile data last night gets tried first the next time you're on mobile data, and wifi keeps its own opinions. After the first couple of connects it usually comes up straight away instead of racing anything.

None of this shows up on screen. No engine list, no protocol dropdown, no ping numbers to stare at.

## The three ways out

They're deliberately nothing alike on the wire. Whoever learns to spot one hasn't learned anything about the other two.

**The quic one** is UDP with the handshake obfuscated to random bytes, dialling public hysteria2 servers. One hop, it exits wherever that server sits, and it's the one that gets first refusal on every connect. It ships with a starting list inside the APK and tops that list up itself once something is up.

**The edge one** speaks MASQUE over HTTP/3 to Cloudflare's own anycast edge. It carries no server list at all, which makes it the only one that can work on a fresh install, on an operator the app has never seen, or on a day when every list has gone stale. It has three gears — quick, then thorough with obfuscation and a split handshake, then tunnel-inside-tunnel — and it remembers which gear worked here.

It's deliberately held back six seconds at the start. It comes up in three or four seconds nearly every time, so left alone it won every race — and what it wins with is a tunnel that comes out in the same country your phone is in, because that network is location-preserving by design. It's meant to be the thing that always works, not the thing that always wins.

**Tor** is three hops of volunteer relays, reached through a pluggable transport. Slow, can't carry UDP, and gets through things nothing else does. It's the safety net, not the main road.

## They're wired into each other

Three separate programs that only take turns aren't worth much. These lean on each other.

Tor bootstrapping to 100% and then carrying nothing is a real thing that happens — the bridge answers, the circuit builds, and the streams through it go nowhere. So when Tor comes up on its own and proves it's carrying nothing, and something else is already working, Tor is told to make its connections through that instead. Plain Tor, no bridges needed, because nobody on your network can see anything except the tunnel underneath anyway. Which route won is remembered per network, so you don't pay for the discovery twice.

The server lists live exactly where they're blocked, so they're fetched through whatever tunnel is already up, never directly. Whichever path won this time is the one that goes and gets the servers the other path will dial next time.

Honest about the cost: Tor going out through another path stops being an independent bet, so the direct route with bridges is always tried first and the chained one is the fallback.

## What used to be here

There was a fourth path that dialled vless and trojan servers out of the big mixed public dumps. It's gone, and not on taste. Its search rounds came back with one or two live servers out of forty, over and over, on two different operators. Sitting behind a working tunnel as the reserve, it went quiet about once a minute and paid for a full forty-eight-port search each time — out of the connection you were actually using. The ranking, probing and scoring all worked fine. All of it was rearranging dead addresses.

Dropping it took a TLS core and a handshake shaper out of the build with it, so the APK got a lot smaller.

The same measurement cut the server feeds down. Two of them were 3.5 MB between them and contributed not one server the small files didn't already have. What's left is three files, 82 KB, 166 distinct servers — cheap enough to refresh on a bad link without the refresh being the reason the link is bad.

## What it can't do

During a full shutdown all of it fails, because there's nothing left to fall back into.

Two of the three paths are UDP, so a network that throttles UDP hard leaves you on Tor.

It won't change your apparent country reliably either. You come out wherever the path it managed to bring up happens to sit.

## Building

Builds run on GitHub Actions. Push a tag starting with `v` and the release workflow builds and publishes the APK.

To build it yourself you need the Android SDK and Go:

```
git clone --recursive https://github.com/Amirmahdavi2022/gozar
cd gozar
bash scripts/fetch-native.sh
bash scripts/fetch-seed.sh
gradle :app:assembleRelease
```

The first script builds the quic core from pinned source and pulls the edge core from its project's published Android builds, checking every archive against a pinned SHA-256 and every binary's ELF machine field. The second pulls the starting server list. Neither of them copies anyone else's code into this repo.

## Where this is going

There's a companion project, [driftkite](https://github.com/Amirmahdavi2022/driftkite), where people outside a censored country lend a browser tab and someone inside gets a way through it. Those proxies only live for minutes, so there's nothing stable enough to be worth blocking. Once there are enough volunteers it becomes a fourth way out here, and a fast one, because it's a direct route.

## Channel

News, fresh builds and help: [@parsv2r](https://t.me/parsv2r)

## License

MIT. Built on Tor's pluggable transports, hev-socks5-tunnel, hysteria and Aether. Full list in [NOTICE.md](NOTICE.md).
