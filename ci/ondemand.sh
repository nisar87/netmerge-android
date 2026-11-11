#!/bin/sh
# Mobile data only when it helps: after a quiet minute merging lets mobile data go; a big download brings it back
# and both networks carry it.
P=dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
# stop and start again the way a user does (also checks that a start right after a stop works)
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
adb shell run-as dev.netmerge.android rm -f files/events.log
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true --ez ondemand true
for i in $(seq 1 30); do ev | grep -q started && break; sleep 1; done
echo "merging: $(ev | grep started | cut -c1-120)"
ev | grep -q started || { echo "merging did not start again"; adb shell dumpsys activity services dev.netmerge.android | head -20; adb logcat -d -s NetMergeSpike | tail -10; adb shell dumpsys activity activities | grep -E "topResumed|ResumedActivity" | head -5; exit 1; }
for i in $(seq 1 50); do ev | grep -q "mobile released" && break; sleep 3; done
ev | grep -q "mobile released" || { echo "mobile data was not let go after a quiet minute"; ev | grep -v stats | tail -5; exit 1; }
echo "quiet: $(ev | grep 'mobile released' | tail -1)"
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 20 -e size 4000000000 $P | grep -E "download=|FAILURES"
ev | grep -q "mobile held: busy" || { echo "mobile data did not come back for a big download"; exit 1; }
sleep 3
# connections the first download opened stay on Wi-Fi (they are reused); a new download opens new ones
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 15 -e size 4000000000 $P | grep -E "download=|FAILURES"
ev | grep -v stats | tail -5 | cut -c1-200
last=$(adb shell run-as dev.netmerge.android cat files/engine.json)
echo "engine: $last"
echo "$last" | jq -e '[.nets[] | select(.name == "mobile" and .rx > 100000)] | length == 1' > /dev/null || { echo "mobile data carried nothing"; exit 1; }
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 3
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true --ez ondemand false
echo "on demand: OK"
