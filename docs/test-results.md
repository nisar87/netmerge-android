# Test results

Where NetMerge for Android stands, test by test. As of 13 November 2026, version 0.1.0 (pull request #14).

| Where | What | Result |
| --- | --- | --- |
| Galaxy S26 Ultra, Android 17 (the owner's phone, over adb) | Every feature, real Wi-Fi and real mobile data | All pass after the fixes below |
| Firebase Test Lab, 5 real phones of other makers + 2 virtual | Start, VPN, pages through it, stop, screens | 6 of 7 pass; Xiaomi 14: a test-only problem, fixed, rerun pending |
| Android emulators, API 34 and 35 | Full suite on every change | All pass |
| Unit tests (Go engine) | Splitter, DNS blocking | All pass |

## On a real phone: Galaxy S26 Ultra, Android 17

Run from a PC over adb (scripts in `E:\NetMerge\tools\jobs` on the test PC), 12–13 November 2026.

| Test | Result |
| --- | --- |
| Clean install of the release build (R8, 15 MB arm64) | Pass |
| Start, stop, 20 fast start/stop in a row | Pass |
| Speed, Wi-Fi alone / mobile alone / merged (20 s, 16 connections) | Wi-Fi 56–63 Mbps, mobile 13–14 Mbps, merged 61–65 Mbps (slow mobile that night; +38% earlier with fast mobile) |
| Upload: what the app shows against what really left | Peak 74 Mbps shown, 68 Mbps real (the false spike is gone) |
| Wi-Fi off 15 s during a download | Longest stall 0.4 s |
| Mobile data off 15 s during a download | Longest stall 0.5 s |
| Wi-Fi off and on 6 times in 2 minutes | Longest stall 0.5 s |
| Mobile data off and on 4 times | Longest stall 0.3 s |
| Both off, airplane mode on and off | Pages open again |
| Screen off, Doze, system kills background apps | Merging continues |
| App force-stopped | VPN closes, internet works (no dead VPN) |
| Mobile data only when it helps | Let go after a quiet minute, back for downloads |
| Daily limit | Stops mobile data, pages keep working |
| Apps left out of merging | Their traffic bypasses NetMerge |
| Battery guard (level set over adb) | Paused at 20% (VPN closed, internet works), still paused at 40%, back at 55% |
| Ad blocking: lists, blocking on/off | Ad names blocked, normal sites open; off undoes it |
| Ads in Chrome (cnn.com) | 144 of 352 look-ups blocked, page loads |
| Private DNS set to a fixed provider | Pages work (blocking cannot work then: Android's limit) |
| Restart with Always-on VPN | NetMerge starts by itself, pages work through it |
| Quick settings tile, tapped on the panel | Starts and stops merging |
| 30 rounds of web pages, 64 connections at once, YouTube 60 s, IPv6 | All pass |
| 10 minutes of normal use | No failed page; no memory or connection leak (heap about 33 MB) |
| Rotation and app switching while merging | No crash, merging continues |
| Built-in speed test | Wi-Fi, mobile and both together measured |
| Web pages with very slow mobile data, merged against Wi-Fi alone | First 4 pages slower while NetMerge learns, then as fast as Wi-Fi alone (1.4–1.6 s) |
| Screens in light, dark and the largest font | Correct |
| 3 hours of use (11 November) | No leaks |

## On other phones: Firebase Test Lab

Project `netmerge-tests`, workflow `.github/workflows/testlab.yml` (weekly and by hand). The phones there have Wi-Fi
only, so the test (`probe/.../DeviceSmokeTest.kt`) checks the app, not merging: NetMerge opens, Android's VPN question
is answered, the VPN comes up, pages open through it, each screen is photographed, merging stops, pages still open.

| Phone | Android | Result |
| --- | --- | --- |
| Samsung Galaxy S22 (SC-51C) | 16 | Pass |
| Google Pixel 8a | 15 | Pass |
| Motorola razr plus 2024 | 14 | Pass |
| Oppo CPH2195 | 13 | Pass |
| Virtual phone | 10 | Pass |
| Virtual phone | 14 | Pass |
| Xiaomi 14 | 15 | Fail in the test, not the app: HyperOS blocked the test app from opening NetMerge. The test now opens it the way a person or adb does; rerun pending (free daily quota) |

## Emulators, on every change

`ci/emulator.sh` on API 34 and 35 with Wi-Fi and mobile data: both networks up, connections on each network, a merged
download, Wi-Fi dropped mid-download (fails on a stall of 5 s or more), mobile data on demand, daily limit, roaming (when
the emulator can fake it), apps left out, battery guard, the tile, ad blocking, the speed test, the release build (one
VPN service only), screenshots. The engine library's 16 KB page alignment is checked on every build.

## Bugs the tests found (all fixed)

| Bug | Found by | Fix |
| --- | --- | --- |
| After a restart with Always-on VPN the phone had no internet | Phone test | A leftover test VPN service was removed from the release |
| Pause for the battery left a VPN that carried nothing | Emulator | The engine closes the VPN fully when it stops |
| Very slow mobile data: websites did not open while merging | The owner, on the phone | Slow-to-answer and slow-but-busy networks get a trickle; DNS goes to the best network |
| First fix too eager: Wi-Fi cut when mobile answered quicker | Phone test | Judged after 3 answers, 4x and 0.3 s slower; never the proven fastest network |
| Mobile data coming back on demand got almost no connections | Emulator | Failed connections no longer count as slow answers |
| The tile could stop merging but not start it | Phone test | Falls back to the app's screen |
| False speed spike during uploads | The owner, on the phone | Upload buffering limited; speed over real elapsed time |
| Speed test: mobile data not woken in time; mobile data used over-counted | Phone test | Waits up to 30 s more; counts only mobile bytes |
| Home said to turn on mobile data that was only asleep | Phone test | Reads the phone's mobile data setting |

## Not tested yet

- Roaming abroad on a real phone (the emulator cannot fake it).
- Battery cost on demand against always on (needs about 80 minutes unplugged).
- Xiaomi 14 rerun in Test Lab (pending quota).
- Phones from people other than the owner (the closed test before Google Play).
