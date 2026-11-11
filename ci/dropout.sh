#!/bin/sh
# Wi-Fi drops in the middle of a merged download on the emulator: reports the download (errors, longest stall) and
# whether Wi-Fi joins the merge again. Not a pass/fail check yet (the emulator's Wi-Fi is slow to come back).
P=dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner
adb shell "rm -f /data/local/tmp/dl.txt; setsid nohup sh -c 'am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 30 -e size 2000000000 $P > /data/local/tmp/dl.txt' > /dev/null 2>&1 < /dev/null &"
sleep 8
adb shell svc wifi disable
echo "Wi-Fi off"
sleep 10
adb shell svc wifi enable
echo "Wi-Fi on"
sleep 20
echo "download during the dropout:"
adb shell cat /data/local/tmp/dl.txt | grep -E "download=|FAILURES|stack="
for i in $(seq 1 30); do
  adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null | tail -1 | grep -q '"wifi"' && break
  sleep 2
done
echo "events:"
adb shell run-as dev.netmerge.android cat files/events.log | cut -c1-200
echo "engine:"
adb shell run-as dev.netmerge.android cat files/engine.json; echo
