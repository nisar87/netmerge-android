#!/bin/sh
# Android telling "low on memory" again and again while merging: written once per level change (or per 10 minutes),
# with how often it was told meanwhile and NetMerge's own memory. On a Galaxy A7 2018 it came about 20 times in 3
# minutes, burying the rest of the log.
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
fail() { echo "$1"; ev | grep -v -e stats -e networks | tail -12 | cut -c1-200; adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; exit 1; }
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true; sleep 2
for i in $(seq 1 30); do ev | grep -q started && break; sleep 1; done
ev | grep -q started || fail "merging did not start"
adb shell run-as dev.netmerge.android rm -f files/events.log
for l in RUNNING_LOW RUNNING_LOW RUNNING_LOW RUNNING_CRITICAL RUNNING_LOW RUNNING_CRITICAL; do
  adb shell am send-trim-memory dev.netmerge.android $l; sleep 1
done
sleep 2
n=$(ev | grep -c "memory: the phone is low on memory")
ev | grep "memory:" | cut -c1-200
[ "$n" -ge 1 ] || fail "low memory was not logged at all"
[ "$n" -le 2 ] || fail "low memory logged $n times for 6 warnings (want once per level change)"
ev | grep "memory:" | grep -q "NetMerge uses" || fail "the line does not say NetMerge's own memory"
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
echo "memory: OK"
