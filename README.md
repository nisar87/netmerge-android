# NetMerge for Android (early test)

The idea: use Wi-Fi and mobile data at the same time on an Android phone, the way NetMerge merges a PC's connections.
New connections are spread over both, so downloads, app stores and browsing many sites get faster; one single
connection still uses one network.

**Status: feasibility test, not an app yet.** This repository currently answers two questions:

1. Do Wi-Fi and mobile data stay up together when an app asks for both?
2. Can a connection be sent out on each one separately, also while the app runs a VPN on the phone (the way it will
   catch every app's traffic)?

`DualNetworkTest` checks both on GitHub's Android emulator for every change (see `.github/workflows/ci.yml`), and the
same test runs on a real phone over USB.

## How it will be tested

1. The merging logic (in Go, shared ideas with NetMerge) is tested on a computer with simulated lines.
2. Every change runs on an Android emulator with Wi-Fi and mobile data.
3. A real phone over USB (adb) for what an emulator cannot show.
4. A self-test button in the app for daily-life checks (battery, the phone maker's background limits, real speeds).
