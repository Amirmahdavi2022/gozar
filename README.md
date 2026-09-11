# Gozar VPN

One button. Gozar figures out how to get out by itself.

Nothing to paste, no subscription link, no server to rent. Open it, press the button, wait a few seconds.

## Download

Latest APK is on the [releases page](https://github.com/Amirmahdavi2022/gozar/releases/latest).

Grab the Universal one if you're not sure which. It installs on any phone.

## What it does

Most apps like this hand you a list of servers and leave the sorting to you. Gozar treats that as its own job.

Press connect and it starts more than one way out at the same time, then keeps whichever one actually carries traffic. Starting isn't enough. A dead server and a live one both accept a connection on your phone in about a millisecond, so nothing counts as connected here until a real request has gone out and come back.

While you're using the one that won, a second one stays running quietly in the background. When the first one dies, and sooner or later it will, there's already a live tunnel sitting there to move onto. You get a hiccup instead of a reconnect.

It also remembers. Results are scored per network, so whatever worked on your mobile data last night gets tried first the next time you're on mobile data. After the first couple of connects it usually comes up straight away instead of racing everything.

None of this shows up on screen. No engine list, no protocol dropdown, no ping numbers to stare at.

## The two ways out

They're deliberately nothing alike on the wire. Whoever learns to spot one hasn't learned anything about the other.

The fast one dials a public server directly, speaking something that looks like a normal TLS session to a normal website. One hop, usually a few tens of milliseconds away. It ships with a starting list of servers baked into the APK so it can work on a fresh install, and it refreshes that list itself once a tunnel is up.

It can also reshape the TLS handshake on the way out, which gets past the kind of equipment that reads the first packet and decides from there.

The slow one is Tor with pluggable transports. Three hops through volunteer relays, so it's never going to be quick, and it can't carry UDP at all. What it does have is a much better chance of coming up when things are bad. It's the safety net, not the main road.

When the fast one is blocked you fall back to the slow one and stay online. When the slow one is up and the server lists are blocked, it's what fetches them so the fast one has somewhere to go next time. They feed each other.

## What it can't do

During a full shutdown all of it fails, because there's nothing left to fall back into.

It also won't change your apparent country reliably. You come out wherever the server it managed to reach happens to sit.

## Building

Builds run on GitHub Actions. Push a tag starting with `v` and the release workflow builds and publishes the APK.

If you want to build it yourself you'll need the Android SDK, the NDK and Go, then:

```
git clone --recursive https://github.com/Amirmahdavi2022/gozar
cd gozar
bash scripts/fetch-native.sh
bash scripts/fetch-seed.sh
gradle :app:assembleRelease
```

The first script builds the proxy core and the handshake shaper from pinned source. The second pulls the starting server list. Neither of them vendors anyone else's code into this repo.

## Where this is going

There's a companion project, [driftkite](https://github.com/Amirmahdavi2022/driftkite), where people outside a censored country lend a browser tab and someone inside gets a way through it. Those proxies only live for minutes, so there's nothing stable enough to be worth blocking. Once there are enough volunteers it becomes a third way out here, and a fast one, because it's a direct route.

## Channel

News, fresh builds and help: [@parsv2r](https://t.me/parsv2r)

## License

MIT. Built on Tor's pluggable transports, hev-socks5-tunnel, Xray-core, hysteria and byedpi.
