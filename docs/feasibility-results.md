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

## 3. Next: the cost

Engine inside the app (all of the phone's traffic through the VPN and split over both networks): speed through the
engine against the app-level numbers above, battery drain over 30-60 minutes merged against not merged, heat.
