#!/bin/sh
# The built-in speed test: Wi-Fi alone, mobile alone, both together, download and upload; a screenshot while it runs
# and when it is done. Started with --ez speedtest true; the result is in files/speedtest.json.
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 3
adb shell run-as dev.netmerge.android rm -f files/speedtest.json
adb shell am start -n dev.netmerge.android/.MainActivity --ez speedtest true
sleep 20; adb shell screencap -p /sdcard/screen-speedtest-running.png; adb pull /sdcard/screen-speedtest-running.png . || true
for i in $(seq 1 120); do adb shell run-as dev.netmerge.android cat files/speedtest.json 2>/dev/null | grep -q '"done"' && break; sleep 1; done
r=$(adb shell run-as dev.netmerge.android cat files/speedtest.json 2>/dev/null)
echo "speed test: $r"
adb shell screencap -p /sdcard/screen-speedtest.png; adb pull /sdcard/screen-speedtest.png . || true
echo "$r" | grep -q '"done":true' || { echo "the speed test did not finish"; exit 1; }
echo "$r" | grep -q '"label":"Both together"' || { echo "no both-together result"; exit 1; }
echo "$r" | grep -q '"down":0,' && echo "warning: a network got no download (test server not reachable from the emulator?)"
echo "speedtest: OK"
