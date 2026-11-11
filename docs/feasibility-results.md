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

## 4. Still open

- A measured battery drain with the cable unplugged (30-60 minutes, merging on vs off).
- Keeping mobile data up when nothing is downloading: its cost while idle.
- Samsung's background limits over hours; behaviour when Wi-Fi or mobile drops in the middle of downloads.
- Processor use: the user-space stack is the main cost; to be tuned before release.
