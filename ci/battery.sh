#!/bin/sh
# Battery guard: merging pauses when the battery falls to the stop level and starts again by itself once it has
# charged back to the resume level. The emulator's battery level is set with "dumpsys battery".
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
waitfor() { for i in $(seq 1 ${2:-30}); do ev | grep -q "$1" && return 0; sleep 1; done; return 1; }
fail() { echo "$1"; ev | grep -v -e stats -e networks | tail -12 | cut -c1-160; adb shell dumpsys battery reset; exit 1; }
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
adb shell run-as dev.netmerge.android rm -f files/events.log
adb shell dumpsys battery unplug
adb shell dumpsys battery set level 60
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true --ei batterystop 25 --ei batteryresume 50
waitfor started 60 || fail "merging did not start within 60 s"
tun() { adb shell ip addr 2>/dev/null | grep -q "10.111.0.2"; }
tun && seetun=1 || { seetun=0; echo "warning: the VPN's address is not visible to adb; not checking that it closes"; }
adb shell dumpsys battery set level 20
waitfor "paused: battery 20%" 15 || fail "merging did not pause at 20%"
sleep 3
[ $seetun = 1 ] && tun && fail "the VPN is still up while paused"
# apps keep working on the normal network
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#pagesOpen dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner | grep -q "^OK (" || fail "pages failed while paused"
adb shell dumpsys battery set ac 1
adb shell dumpsys battery set level 40
sleep 5
ev | grep -q resumed && fail "merging came back at 40%, below the resume level"
adb shell dumpsys battery set level 55
waitfor "resumed: battery 55%" 15 || fail "merging did not come back at 55%"
for i in $(seq 1 60); do ev | sed -n '/resumed/,$p' | grep -q started && break; sleep 1; done
ev | sed -n '/resumed/,$p' | grep -q started || fail "merging did not start again after the pause"
# Stop while paused ends the pause for good
adb shell dumpsys battery set ac 0
adb shell dumpsys battery set level 20
waitfor "paused: battery 20%.*" 15
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
adb shell dumpsys battery set level 70; sleep 5
ev | tail -3 | grep -q stopped || fail "stop while paused did not stop"
ev | grep -v -e stats -e networks | cut -c1-120
adb shell dumpsys battery reset
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true --ei batterystop 0; sleep 5
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
echo "battery: OK"
