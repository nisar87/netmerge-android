# Feasibility results

Phone: Samsung Galaxy S26 Ultra (SM-S948B), One UI 9, Android 17. Home Wi-Fi + Pakistani 4G carrier (IPv4 + IPv6).

## 1. Both networks at once (2026-11-11) - passed

- Asking for mobile internet with `requestNetwork` while Wi-Fi is connected brings it up (`rmnet_data7`), and it stays up.
- A connection opened through each `Network` leaves from that network's own address, also while the app runs a
  VPN on the phone (own app excluded from the VPN).
- Same three tests pass on Android 14 and 15 emulators on GitHub for every change.
- Note: mobile data gets IPv4 (carrier NAT, 10.x) and IPv6; Google was reached over IPv6. The engine must handle IPv6.

## 2. Speed gain, app level (2026-11-11)

One download (Google Chrome .deb, 140 MB, ranges allowed), 2 MB pieces handed out one at a time, 30 s cap per run.

| Round | Wi-Fi only (16 conn.) | Mobile only (16 conn.) | Both (8 + 8) | Gain over Wi-Fi |
| --- | --- | --- | --- | --- |
| 1 | 65.4 Mbps | 18.0 Mbps | 93.8 Mbps | +43% |
| 2 | 62.9 Mbps | 16.7 Mbps | 71.7 Mbps | +14% |
| Average | 64.2 | 17.4 | 82.8 | **+29%** |

Reading: merging works and adds most of the mobile speed on top of Wi-Fi. The spread between rounds is the networks
themselves (single runs vary). The end of a split download is slowed by mobile connections holding the last pieces
(the "tail" known from NetMerge); a system-wide merge of many separate connections does not have that effect.
Mobile data used by the two rounds: about 200 MB.

## 3. Through the engine: every app's traffic merged (2026-11-11)

The engine (Go: gVisor stack via tun2socks, NetMerge-style split, each connection bound to Wi-Fi or mobile with
`android_setsocknetwork`) runs behind the app's VPN. A separate "probe" app plays an ordinary app: plain connections,
16 at a time, the same 140 MB file. Merging off = the phone's normal Wi-Fi.

| Round | Merging off | Merging on | Gain |
| --- | --- | --- | --- |
| 1 | 61.4 Mbps | 90.0 Mbps | +47% |
| 2 | 63.9 Mbps | 83.5 Mbps | +31% |
| 3 | 59.7 Mbps | 82.1 Mbps | +38% |
| Average | 61.7 | 85.2 | **+38%** |

- Mobile data carried about 25% of each merged download (35-38 MB of 140 MB); the engine learned Wi-Fi at about
  80-93 Mbps and mobile at about 26-29 Mbps and gave new connections 75% / 25%.
- No failed connections, no download errors; pages open (Google, gstatic) through the engine; IPv6 goes to mobile only.
- The engine is ready about 1 s after "start".

Cost:

- Processor: about 7.5 s of processor time per merged 140 MB download (about 50 s per GB), most of a core while
  downloading at full speed. Idle (merging on, nothing happening): 0.13 s per minute.
- Android's battery estimate (phone told to count as unplugged; model-based, not a measured drain), two 140 MB
  downloads: about 2.8 mAh not merged, about 4.0 mAh merged - roughly 10 against 14 mAh per GB, i.e. 0.2% against
  0.3% of a 5000 mAh battery per GB.
- Temperature: 29.5 to 30.8 C over the whole test (phone on the cable).

## 4. A network drops in the middle of a merged download (2026-11-11, first try)

Job 021: merging on, a 16-connection download running; Wi-Fi (then mobile data) switched off after 10 s and on again
25 s later.

- Pages opened before and after each drop; merging went on by itself when the network came back.
- The test download itself was wrong: it asked for 300 MB of a file of about 120 MB, so every piece past the end
  failed at once, and a failed piece was not fetched again. The errors (131 and 73) and the early end came from the
  test, not from merging. Found on the emulator, where the engine's own timeline showed mobile data carrying about
  20 MB/s all through a Wi-Fi drop while the test reported a 24-second stall.
- The engine kept connections that were on the lost network open until they timed out, so apps waited for them.
- The phone's log (logcat) keeps only about 50 KB on this phone; the engine's lines were often gone before the test
  read them, so job 021 showed no engine lines although merging was on (checked in job 022).

Changes after this (branch `dropout-failover`): the engine closes every connection of a network that left at once (the
app then opens a new one, which goes to the network that works), tries another network after 5 s instead of 10 s; the
test download fetches failed pieces again and reports its longest stall; the app writes its events and statistics to
files that tests read with `adb shell run-as`.

Retest on the phone with these changes (job 023, 2026-11-11, merging on, 16-connection download for 60 s, the
network switched off at 10 s and on again at 30 s; Wi-Fi was slower than usual that morning, about 40 Mbps):

| | Download | Errors | Longest stall | What the engine did |
| --- | --- | --- | --- | --- |
| Nothing dropped (30 s) | 32.8 Mbps | 0 | 0.6 s | Wi-Fi about 75%, mobile 25% |
| Wi-Fi drops | 34.0 Mbps, 256 MB | 11 | 2.9 s | Wi-Fi out of the merge within a second, its 19 connections closed, mobile carried everything; Wi-Fi back in the merge about 1 s after it reconnected and carrying most again within 6 s |
| Mobile data drops | 53.7 Mbps, 416 MB | 1 | 1.5 s | Mobile out within a second, 16 connections closed, Wi-Fi carried everything; mobile back in after it reconnected |

Pages opened after each drop. The emulators in CI now run the same Wi-Fi drop on every change and fail on a stall of
5 s or more (both: 0-3 errors, longest stall 0.1 s).

## 5. Processor cost and the VPN's packet size (job 029, 2026-11-11)

The engine's own stack handles every packet between the apps and the VPN, so fewer, bigger packets cost less. Same
merged 16-connection download for 30 s, alternating, twice each:

| VPN packet size (MTU) | Speed | Processor per GB |
| --- | --- | --- |
| 1500 | 57.0 / 61.4 Mbps | 62.5 / 64.0 s |
| 9000 | 64.4 / 63.9 Mbps | 44.3 / 44.7 s |
| 16000 | 59.6 / 56.2 Mbps | 42.6 / 45.2 s |

9000 is now the default: about 30% less processor for the same speed (the networks themselves still use their own
packet size; only the path between the apps and the engine changes). 16000 gains nothing more.

Battery: the first battery test (4 x 12 minutes) measured nothing: on this phone the battery's charge counter stays
at its "full" value while the battery shows 100%. The battery test now also adds up the battery current every second.

## 6. Battery, measured (job 026/029 battery part, 2026-11-11, unplugged, screen off)

Four blocks of 12 minutes, alternating; each block: a 140 MB download every 5 minutes (3 per block), idle in
between; the battery current added up every second. Build with VPN packet size 1500, mobile data always kept up.

| Block | Battery used | Per hour | Downloads |
| --- | --- | --- | --- |
| Not merged 1 | 18.5 mAh | 92 mAh | 62, 64, 66 Mbps |
| Merged 1 | 36.9 mAh | 185 mAh | 51, 39, 31 Mbps |
| Not merged 2 | 17.3 mAh | 86 mAh | 67, 69, 68 Mbps |
| Merged 2 | 30.0 mAh | 150 mAh | 50, 70, 67 Mbps |

- Merging roughly doubled the drain for this kind of use: about +65-95 mAh per hour, 1.3-1.9% of the 5000 mAh
  battery per hour.
- Merged downloads were not faster this time: mobile data was slow that morning (its learned speed fell to about
  1 Mbps) and still got about a quarter of the new connections, so the last pieces of each download waited for it.
  Two causes found in the splitter: (1) learned speeds were forgotten while idle (between downloads both fell to the
  same floor, so the next download split about evenly); (2) a network far slower than the other still got its share,
  which only delays a download of fixed size. Fixed on the next branch (see below).

## 7. Three hours of use (job 031/033, 2026-11-11 10:44-13:44, unplugged, screen off)

Merging on the whole time (packet size 9000, mobile data always up); every 10 minutes three pages and a 30 s
download with 16 connections; the engine's health read every minute.

- 18 rounds, 0 pages failed, downloads 48.1 Mbps on average. Two rounds were bad: one at 0.6 Mbps with a 25 s stall,
  one at 12.5 Mbps (both in the first hour; the engine counted 8 and 5 failed connection attempts at those times, so
  probably a network hiccup; the next round was normal each time).
- No leak: goroutines went back to 31-45 after each download (100-110 during), open connections to 3-14, the Go
  heap to 1-2 MB, open files to 141-153. The app's memory (RSS) rose slowly (165 to 196 MB) and fell back to about
  170-180 MB twice (garbage collection).
- Battery fell from 95% to 85% in 3 hours of this use (about 170 mAh per hour).

Speed after the splitter fixes (job 033, same afternoon; mobile data was slow, about 15 Mbps):

| | 30 s download | 140 MB download |
| --- | --- | --- |
| Not merged | 58.0 / 59.1 / 59.2 Mbps | 47.5 / 43.2 Mbps |
| Merged | 60.9 / 52.3 Mbps | 49.4 / 56.5 Mbps |

With mobile data this slow merging gains little (Wi-Fi carried 85%), but it no longer makes downloads slower, as it
did in the morning's battery test. The Wi-Fi link to the phone for testing (adb) dropped after this, so battery round
2 did not start.

## 8. Evening checks on the phone (jobs 037 and 041, 2026-11-11)

- Stop and start at once, three times in a row: merging was on again within 1 s every time (the start-up fix).
- An app left out of merging: its 77 MB download went past the engine entirely (0 MB through it); put back, 51 MB
  went through it.
- Speed (16 connections, 30 s): not merged 50.2 and 53.2 Mbps; merged 56.5 Mbps (Wi-Fi 71% of the data). Earlier
  in the evening everything ran at 5-9 Mbps, merged or not (the line itself was slow).
- The block lists downloaded on the phone (ads 1.9 MB, malware 3.4 MB).
- Installing the new 43 MB debug build failed on the phone (twice: once it hung, once the installer threw an error).
  The phone tests now install a shrunk release build instead (R8, about a tenth of the size), checked on the
  emulator first.

## 9. Still open (updated 13 November 2026)

The newer tests are in [test-results.md](test-results.md). Of what was open here on 11 November:

- Always-on VPN after a restart: tested on the phone. It first failed (no internet after the restart, a leftover test
  VPN service started instead); fixed and passed.
- The test build with apps left out, only networks with internet, the start-up fix and the icon: tested on the phone,
  passed.
- Battery round 2 (mobile data always up against on demand): still open; needs about 80 minutes unplugged.
- A Wi-Fi network without internet (a hotel login page): still untested on a real phone (NetMerge only uses networks
  Android has checked for internet).
- Samsung's limits overnight: still untested.
- Two download rounds in the 3-hour run stalled (25 s and 7 s): not seen again in later runs; not explained.
