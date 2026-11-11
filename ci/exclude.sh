#!/bin/sh
# An app left out of merging uses the phone's normal network: its download does not go through the engine.
P=dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
rx() { adb shell run-as dev.netmerge.android cat files/engine.json | jq '[.nets[].rx] | add'; }
restart() {
  adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
  adb shell run-as dev.netmerge.android rm -f files/events.log
  adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true "$@"
  for i in $(seq 1 30); do ev | grep -q started && break; sleep 1; done; sleep 4
}
restart --es exclude dev.netmerge.probe
a=$(rx)
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 8 -e size 4000000000 $P | grep -E "download=|FAILURES"
sleep 3; b=$(rx)
echo "engine carried $((b - a)) bytes while the probe app was left out"
[ $((b - a)) -lt 5000000 ] || { echo "the left-out app still went through merging"; exit 1; }
restart --es include dev.netmerge.probe
a=$(rx)
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 8 -e size 4000000000 $P | grep -E "download=|FAILURES"
sleep 3; b=$(rx)
echo "engine carried $((b - a)) bytes with the probe app merged again"
[ $((b - a)) -gt 20000000 ] || { echo "the app did not go through merging again"; exit 1; }
echo "exclude: OK"
