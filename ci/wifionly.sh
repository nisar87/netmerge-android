#!/bin/sh
# Wi-Fi only (mobile data off, as on a phone without a SIM): merging starts within a few seconds on Wi-Fi alone,
# not after waiting the whole 15 s for mobile data; mobile data joins when it is back.
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 3
adb shell svc data disable; sleep 3
adb shell run-as dev.netmerge.android rm -f files/events.log
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true
for i in $(seq 1 30); do ev | grep -q started && break; sleep 1; done
found=$(ev | grep "networks found in" | tail -1)
echo "wifi only: $(echo "$found" | cut -c15-200)"
ms=$(echo "$found" | sed -n 's/.*networks found in \([0-9]*\) ms.*/\1/p')
adb shell svc data enable
[ -n "$ms" ] || { echo "merging did not start with mobile data off"; ev | grep -v stats | tail -8; exit 1; }
[ "$ms" -lt 8000 ] || { echo "merging waited $ms ms for mobile data that is off"; exit 1; }
# mobile data back: it joins the running merge
for i in $(seq 1 40); do ev | grep -q "engine: networks now .*mobile" && break; sleep 1; done
ev | grep -q "engine: networks now .*mobile" || { echo "mobile data did not join when it came back"; ev | grep -v stats | tail -8; exit 1; }
echo "wifi only: OK (started in $ms ms; mobile data joined later)"
