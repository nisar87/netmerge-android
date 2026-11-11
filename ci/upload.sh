#!/bin/sh
# An upload as a speed test does, not merged and merged: reports whether the first second shows a false peak.
P=dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 3
echo "not merged: $(adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#upload -e upload 1 -e conns 8 -e secs 12 $P | grep 'upload=' | sed 's/.*upload=//')"
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true; sleep 8
echo "merged:     $(adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#upload -e upload 1 -e conns 8 -e secs 12 $P | grep 'upload=' | sed 's/.*upload=//')"
