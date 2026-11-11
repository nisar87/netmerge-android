#!/bin/sh
# Mobile data only when it helps: after a quiet minute merging lets mobile data go; a big download brings it back
# and both networks carry it.
P=dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
for i in $(seq 1 50); do ev | grep -q "mobile released" && break; sleep 3; done
ev | grep -q "mobile released" || { echo "mobile data was not let go after a quiet minute"; ev | grep -v stats | tail -5; exit 1; }
echo "quiet: $(ev | grep 'mobile released' | tail -1)"
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 20 -e size 4000000000 $P | grep -E "download=|FAILURES"
ev | grep -v stats | tail -4 | cut -c1-200
ev | grep -q "mobile held: busy" || { echo "mobile data did not come back for a big download"; exit 1; }
last=$(adb shell run-as dev.netmerge.android cat files/engine.json)
echo "engine: $last"
echo "$last" | jq -e '[.nets[] | select(.name == "mobile" and .rx > 100000)] | length == 1' > /dev/null || { echo "mobile data carried nothing"; exit 1; }
echo "on demand: OK"
