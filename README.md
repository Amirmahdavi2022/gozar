# Gozar

One button. Gozar works out how to get out on its own.

No config to paste, no subscription link, no server to rent. You open it, you
press the button, and it deals with the rest.

## What it actually does

Most apps in this space hand you a list and let you sort it out. Gozar treats
that as the app's job, not yours.

When you press connect it starts several ways out at once and keeps whichever
one proves it works. Not whichever one starts — whichever one carries real
traffic, checked with a probe before anything is called connected. A second one
is kept running quietly behind it, so when the first dies, and it will, there is
already a live tunnel to move to. You get a half second hiccup instead of a
reconnect.

It also remembers. Every result is scored per network, so what worked on your
mobile data last night goes first next time you are on mobile data, and the
whole thing usually comes up on the first try instead of racing.

None of this is shown to you. There is no engine list, no protocol dropdown, no
ping numbers to squint at.

## The ways out

They're picked so no two of them look alike on the wire. A censor that learns
to spot one has learned nothing about the others.

| | looks like |
|---|---|
| WebRTC | a video call |
| WebTunnel | an ordinary HTTPS site |
| obfs4 | random bytes with no structure |
| your own config | whatever you brought |

The WebRTC one needs nothing to start, which is why it goes first on a fresh
install. The other two need bridge lines, and getting those is itself blocked
in a lot of places — so Gozar fetches them through whichever tunnel is already
up and saves them for next time. First launch is slow, every launch after it is
not.

Anyone with their own config can paste it in, and it gets raced alongside the
rest. It usually wins, because it is a direct route.

## Where this is going

There is a companion project, [driftkite](https://github.com/Amirmahdavi2022/driftkite),
where people outside a censored country lend a browser tab or run a small app,
and someone inside gets a way through it. Those proxies live minutes, so there
is nothing stable enough for a censor to block. When there are enough
volunteers, that becomes another way out here — and unlike the Tor based ones,
it is a direct route, so it is fast.

## If nothing works

During a full shutdown everything in here fails, because there is nothing left
to fail into. When that happens the app points you at the channel:

**[@parsv2r](https://t.me/parsv2r)**

That is also where fresh configs and news get posted.

## State

Early. The core is being written now: the racer, the scoreboard and the health
watcher are in, the engines are next, and the Android layer after that. Nothing
has been built into an APK yet.

## Building

Not yet. This section gets written when there is something to build.

## License

MIT
