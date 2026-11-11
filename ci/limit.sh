#!/bin/sh
# Daily limit for mobile data used by merging: once reached, merging lets mobile data go and uses Wi-Fi only.
P=dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
adb shell run-as dev.netmerge.android rm -f files/events.log
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true --ez ondemand false --ei limitmb 50
for i in $(seq 1 60); do ev | grep -q started && break; sleep 1; done
ev | grep -q started || { echo "merging did not start within 60 s"; ev | grep -v -e stats -e networks | tail -8 | cut -c1-200; adb logcat -d -s NetMergeSpike | grep -v "networks now" | tail -15; adb logcat -d -s NetMergeSpike | tail -10; exit 1; }
# the limit is about merging, so it needs Wi-Fi in the merge
for i in $(seq 1 30); do ev | grep -E "started|networks" | tail -1 | grep -q '"wifi"' && break; sleep 2; done
sleep 3
ev | grep -E "started|networks" | tail -1 | grep -q '"wifi"' || { echo "Wi-Fi is not in the merge; networks now:"; adb shell dumpsys connectivity | grep "ni{" | cut -c1-160; ev | grep -v stats | tail -5; exit 1; }
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 15 -e size 4000000000 $P | grep -E "download=|FAILURES"
sleep 3
ev | grep -v stats | cut -c1-160
ev | grep -q "mobile released: daily limit reached" || { echo "the daily limit did not stop mobile data"; exit 1; }
# and Wi-Fi alone still works
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#pagesOpen $P | grep -q "^OK (" || { echo "pages failed after the limit"; exit 1; }
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true --ei limitmb 0; sleep 5
echo "limit: OK"
