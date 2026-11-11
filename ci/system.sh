#!/bin/sh
# Started from outside the app: the quick settings tile must start and stop merging (on a phone it stopped but could
# not start). Always-on VPN is checked on the release build (emulator.sh): it must hold exactly one VPN service.
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
tun() { adb shell ip addr 2>/dev/null | grep -q "10.111.0.2"; }
pages() { adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#pagesOpen dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner | grep -q "^OK ("; }
fail() { echo "$1"; ev | grep -v -e stats -e networks | tail -8 | cut -c1-160; exit 1; }
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 3
# the tile (added for the test, removed after)
adb shell cmd statusbar add-tile dev.netmerge.android/.MergeTileService; sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell run-as dev.netmerge.android rm -f files/events.log
adb shell cmd statusbar click-tile dev.netmerge.android/.MergeTileService
for i in $(seq 1 30); do ev | grep -q started && break; sleep 1; done
ev | grep -q started || fail "the tile did not start merging"
sleep 3
adb shell cmd statusbar click-tile dev.netmerge.android/.MergeTileService; sleep 5
ev | tail -2 | grep -q stopped || fail "the tile did not stop merging"
adb shell cmd statusbar remove-tile dev.netmerge.android/.MergeTileService
adb shell cmd statusbar collapse
echo "system: OK"
