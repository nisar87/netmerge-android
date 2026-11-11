# NetMerge for Android (test builds)

Uses Wi-Fi and mobile data at the same time on an Android phone, the way NetMerge merges a PC's connections. The app
runs a VPN on the phone that catches every app's traffic; each **new connection** goes out on Wi-Fi or mobile data,
more of them on the faster one. Downloads, app stores, browsers and streaming open many connections and get faster;
one single connection still uses one network.

**Status: working test builds, not released.** Measured on a Galaxy S26 Ultra: about +38% for downloads with many
connections when both networks are fast; dropping a network in the middle of a download stalls it for under 3 s.
Results, costs and open questions: [docs/feasibility-results.md](docs/feasibility-results.md).

## What is in it

| Part | What it does |
| --- | --- |
| `engine/` (Go, built with gomobile) | The VPN's packet engine (tun2socks / gVisor) and the splitter that picks a network per connection (`split/`, unit-tested) |
| `app/` | The app: screen with live speed per network, settings, the VPN service, quick settings tile |
| `probe/` | A separate test app that behaves like any app (pages, downloads, battery and long-run tests) |
| `ci/` | What runs on the emulators for every change |

The splitter learns each network's speed from what it carries, keeps it while idle, gives a network that has not
shown its speed a fair chance, and gives a network far slower than the fastest only a trickle of new connections.
When a network leaves, its connections are closed at once so apps reconnect on the one that works.

Settings: mobile data only when it helps (let go after a quiet minute, back for big downloads; off by default), a
daily limit for mobile data used by merging, and a battery guard (pause merging when the battery falls to a chosen
level, start again by itself once it has charged back to a second level; off by default).

## How it is tested

1. `go test ./split` for the splitter.
2. Every change runs on Android emulators (API 34 and 35) with Wi-Fi and mobile data: both networks up, connections
   leave on each network, merged pages and downloads, Wi-Fi switched off in the middle of a download (fails on a stall
   of 5 s or more), mobile data on demand, the daily limit, and screenshots of the screen (in the CI log).
3. Every push to `main` puts test builds on the `test-build` pre-release; a real phone runs them over adb (speed,
   processor time, battery, dropouts, hours-long runs).

Test builds are signed with a public test key (`app/test-signing.jks`) so a new build installs over the last one;
a release will use a private key.
