#!/bin/sh
# Ad blocking: with the block lists loaded, ad sites get no address through the VPN and ordinary sites do; with
# blocking off, ad sites resolve again.
P=dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
restart() {
  adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
  adb shell run-as dev.netmerge.android rm -f files/events.log
  adb logcat -c
  adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true "$@"
  for i in $(seq 1 60); do ev | grep -q started && break; sleep 1; done
}
restart --ez blocking true
# the lists are downloaded the first time (in the background)
for i in $(seq 1 60); do adb logcat -d -s NetMergeBlock | grep -qE "blocking on: [0-9]{4,} names" && break; sleep 2; done
adb logcat -d -s NetMergeBlock | tail -5
adb logcat -d -s NetMergeBlock | grep -qE "blocking on: [0-9]{4,} names" || { echo "the block lists were not loaded"; exit 1; }
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#dnsBlocking -e blocked googleads.g.doubleclick.net,pagead2.googlesyndication.com -e open www.google.com,www.wikipedia.org $P > dns1.txt
grep "dns=" dns1.txt; grep -q "^OK (" dns1.txt || { echo "blocking did not work"; exit 1; }
restart --ez blocking false
sleep 3
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#dnsBlocking -e blocked x -e open googleads.g.doubleclick.net,www.google.com $P > dns2.txt
grep "dns=" dns2.txt
# "x" does not exist, so it counts as blocked; the ad site must resolve again
grep -q "^OK (" dns2.txt || { echo "with blocking off an ad site still did not resolve"; exit 1; }
restart --ez blocking true
echo "blocking: OK"
