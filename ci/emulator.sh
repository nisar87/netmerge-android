#!/bin/sh
# Everything that runs on the emulator (called by CI). The emulator's virtual Wi-Fi or mobile data sometimes never
# comes up (seen mostly on API 35); that is a problem of the emulator, not of the app, so the run then stops with a
# clear message instead of failing the app's tests.
set -e
adb shell svc wifi enable
adb shell svc data enable
for i in $(seq 1 60); do d=$(adb shell dumpsys connectivity); w=$(echo "$d" | grep -c "ni{WIFI CONNECTED.*IS_VALIDATED"); m=$(echo "$d" | grep -c -E "ni\{MOBILE[^ ]* CONNECTED.*IS_VALIDATED"); [ "$w" -ge 1 ] && [ "$m" -ge 1 ] && break; [ $((i % 15)) -eq 0 ] && [ "$w" -lt 1 ] && adb shell svc wifi disable && sleep 2 && adb shell svc wifi enable; [ $((i % 10)) -eq 0 ] && [ "$m" -lt 1 ] && adb shell svc data disable && sleep 2 && adb shell svc data enable; sleep 3; done; echo "validated: wifi=$w mobile=$m after $i tries"
if [ "$w" -lt 1 ] || [ "$m" -lt 1 ]; then echo "::warning::EMULATOR NETWORKS DID NOT COME UP (wifi=$w mobile=$m): app tests skipped on this emulator"; exit 0; fi
adb shell dumpsys connectivity | grep -E "NetworkAgentInfo|Transports" | head -20 || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell appops set dev.netmerge.android ACTIVATE_VPN allow
adb logcat -c
adb shell am instrument -w -r dev.netmerge.android.test/androidx.test.runner.AndroidJUnitRunner > instrument.txt; cat instrument.txt
grep -q "^OK (" instrument.txt || { echo "first try failed; networks now:"; adb shell dumpsys connectivity | grep "ni{" | cut -c1-200; sleep 20; adb shell am instrument -w -r dev.netmerge.android.test/androidx.test.runner.AndroidJUnitRunner > instrument.txt; cat instrument.txt; }
adb logcat -d -s NetMergeSpike > logcat.txt || true
grep -q "^OK (" instrument.txt
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
adb install -r probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true
sleep 20
adb shell dumpsys connectivity | grep -c "VPN CONNECTED" || true
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#pagesOpen dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner > probe1.txt; cat probe1.txt
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 15 -e size 40000000 dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner > probe2.txt; cat probe2.txt
sleep 3
adb logcat -d -s NetMergeSpike NetMergeProbe GoLog > logcat.txt || true
grep ENGINE logcat.txt | tail -5
grep -q "^OK (" probe1.txt
grep -q "^OK (" probe2.txt
grep ENGINE logcat.txt | grep '"nets"' | tail -1 | sed 's/.*ENGINE //' | jq -e '[.nets[] | select(.rx > 100000)] | length >= 2'
sh ci/dropout.sh
adb shell am start -n dev.netmerge.android/.MainActivity; sleep 4; adb shell screencap -p /sdcard/screen-merging.png; adb pull /sdcard/screen-merging.png . || true
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 4; adb shell screencap -p /sdcard/screen-off.png; adb pull /sdcard/screen-off.png . || true
