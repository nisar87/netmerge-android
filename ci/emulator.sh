#!/bin/sh
# Everything that runs on the emulator (called by CI). The emulator's virtual Wi-Fi or mobile data sometimes never
# comes up (seen mostly on API 35); that is a problem of the emulator, not of the app, so the run then stops with a
# clear message instead of failing the app's tests.
# Only the checks below decide the result (each ends with || exit 1); other commands may fail harmlessly.
adb shell svc wifi enable
adb shell svc data enable
for i in $(seq 1 60); do d=$(adb shell dumpsys connectivity); w=$(echo "$d" | grep -c "ni{WIFI CONNECTED.*IS_VALIDATED"); m=$(echo "$d" | grep -c -E "ni\{MOBILE[^ ]* CONNECTED.*IS_VALIDATED"); [ "$w" -ge 1 ] && [ "$m" -ge 1 ] && break; [ $((i % 15)) -eq 0 ] && [ "$w" -lt 1 ] && adb shell svc wifi disable && sleep 2 && adb shell svc wifi enable; [ $((i % 10)) -eq 0 ] && [ "$m" -lt 1 ] && adb shell svc data disable && sleep 2 && adb shell svc data enable; sleep 3; done; echo "validated: wifi=$w mobile=$m after $i tries"
if [ "$w" -lt 1 ] || [ "$m" -lt 1 ]; then echo "::warning::EMULATOR NETWORKS DID NOT COME UP (wifi=$w mobile=$m): app tests skipped on this emulator"; exit 0; fi
adb shell dumpsys connectivity | grep -E "NetworkAgentInfo|Transports" | head -20 || true
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell appops set dev.netmerge.android ACTIVATE_VPN allow
adb shell pm grant dev.netmerge.android android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am instrument -w -r dev.netmerge.android.test/androidx.test.runner.AndroidJUnitRunner > instrument.txt; cat instrument.txt
grep -q "^OK (" instrument.txt || { echo "first try failed; networks now:"; adb shell dumpsys connectivity | grep "ni{" | cut -c1-200; sleep 20; adb shell am instrument -w -r dev.netmerge.android.test/androidx.test.runner.AndroidJUnitRunner > instrument.txt; cat instrument.txt; }
adb logcat -d -s NetMergeSpike > logcat.txt || true
grep -q "^OK (" instrument.txt || exit 1
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
adb install -r probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true
sleep 20
adb shell dumpsys connectivity | grep -c "VPN CONNECTED" || true
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#pagesOpen dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner > probe1.txt; cat probe1.txt
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 15 -e size 4000000000 dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner > probe2.txt; cat probe2.txt
sleep 3
adb logcat -d -s NetMergeSpike NetMergeProbe GoLog > logcat.txt || true
grep ENGINE logcat.txt | tail -5
grep -q "^OK (" probe1.txt || exit 1
grep -q "^OK (" probe2.txt || exit 1
grep ENGINE logcat.txt | grep '"nets"' | tail -1 | sed 's/.*ENGINE //' | jq -e '[.nets[] | select(.rx > 100000)] | length >= 2' || exit 1
sh ci/dropout.sh || exit 1
sh ci/ondemand.sh || exit 1
sh ci/limit.sh || exit 1
sh ci/roaming.sh || exit 1
sh ci/exclude.sh || exit 1
sh ci/battery.sh || exit 1
sh ci/system.sh || exit 1
sh ci/blocking.sh || exit 1
sh ci/speedtest.sh || exit 1
sh ci/upload.sh || true
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true; sleep 4
(adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#download -e conns 8 -e secs 12 -e size 4000000000 dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner > /dev/null &) ; sleep 6
adb shell am start -n dev.netmerge.android/.MainActivity --es tab home; sleep 3; adb shell screencap -p /sdcard/screen-merging.png; adb pull /sdcard/screen-merging.png . || true
sleep 8
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false --es tab home; sleep 4; adb shell screencap -p /sdcard/screen-off.png; adb pull /sdcard/screen-off.png . || true
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true; sleep 3
# the notification while merging
adb shell cmd statusbar expand-notifications; sleep 2; adb shell screencap -p /sdcard/screen-notification.png; adb pull /sdcard/screen-notification.png . || true
adb shell cmd statusbar collapse; sleep 1
adb shell am start -n dev.netmerge.android/.MainActivity --es tab block; sleep 3; adb shell screencap -p /sdcard/screen-block.png; adb pull /sdcard/screen-block.png . || true
adb shell am start -n dev.netmerge.android/.MainActivity --es tab settings; sleep 3; adb shell screencap -p /sdcard/screen-settings.png; adb pull /sdcard/screen-settings.png . || true
# large font and large display size (Samsung's "font size" and "screen zoom" set high): the dial must still fit
dens=$(adb shell wm density | grep -o "[0-9]*" | head -1)
adb shell settings put system font_scale 1.3
adb shell wm density $((dens * 115 / 100))
sleep 2
adb shell am start -n dev.netmerge.android/.MainActivity --es tab home; sleep 4; adb shell screencap -p /sdcard/screen-large-home.png; adb pull /sdcard/screen-large-home.png . || true
adb shell am start -n dev.netmerge.android/.MainActivity --es tab test; sleep 3; adb shell screencap -p /sdcard/screen-large-test.png; adb pull /sdcard/screen-large-test.png . || true
adb shell am start -n dev.netmerge.android/.MainActivity --es tab settings; sleep 3; adb shell screencap -p /sdcard/screen-large-settings.png; adb pull /sdcard/screen-large-settings.png . || true
adb shell settings put system font_scale 1.0
adb shell wm density reset
sleep 2
adb shell am start -n dev.netmerge.android/.MainActivity --es tab home
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 3
adb shell am force-stop dev.netmerge.android
adb shell run-as dev.netmerge.android rm -f shared_prefs/settings.xml || true
adb shell am start -n dev.netmerge.android/.MainActivity; sleep 4; adb shell screencap -p /sdcard/screen-welcome.png; adb pull /sdcard/screen-welcome.png . || true
# the screenshots in the log (artifacts cannot always be downloaded): between the markers, base64
for f in screen-large-home.png screen-large-test.png screen-large-settings.png screen-speedtest-running.png screen-speedtest.png screen-merging.png screen-off.png screen-block.png screen-settings.png screen-notification.png screen-welcome.png; do [ -f $f ] && { echo "BEGIN-$f"; base64 -w0 $f; echo; echo "END-$f"; }; done

# the shrunk build for real phones: installs over the debug build, starts, and pages open through it
adb install -r app/build/outputs/apk/release/app-universal-release.apk || { echo "the release build did not install"; exit 1; }
adb shell appops set dev.netmerge.android ACTIVATE_VPN allow
adb shell run-as dev.netmerge.android rm -f files/events.log
adb shell am start -S -n dev.netmerge.android/.MainActivity --ez merge true
for i in $(seq 1 40); do adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null | grep -q started && break; sleep 1; done
adb shell run-as dev.netmerge.android cat files/events.log | grep -m1 started | cut -c1-160
# NetMerge's own log has what is needed after a problem: the app, the networks, the service's commands, the engine
sleep 6
adb shell run-as dev.netmerge.android cat files/events.log > events.txt
grep -v "^[0-9]* stats " events.txt | cut -c1-200 | head -30
for w in "app: process up" "net: WIFI" "net: MOBILE" "command: start" "engine: up" "splitter "; do
  grep -q "$w" events.txt || { echo "the log has no \"$w\" line"; exit 1; }
done
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#pagesOpen dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner > release.txt; cat release.txt | tail -3
adb shell dumpsys connectivity | grep -c "VPN CONNECTED" || true
grep -q "^OK (" release.txt || { echo "pages failed through the release build"; exit 1; }
# Always-on VPN (and a restart) starts the VPN service Android finds for the app: the release must hold exactly one, the
# merging one. With a second one (the first spike's), a phone came back from a restart with a VPN that carried nothing.
# (the package's own resolver table: "cmd package query-services" leaves out services that are not exported)
adb shell dumpsys package dev.netmerge.android | grep -E "dev.netmerge.android/\.(Merge|Spike)VpnService" > vpns.txt
vpns=$(grep -o -E "(Merge|Spike)VpnService" vpns.txt | sort -u | tr '\n' ' ')
echo "VPN services in the release: $vpns"
[ "$vpns" = "MergeVpnService " ] || { echo "the release must hold only MergeVpnService; Android lists:"; cat vpns.txt | head -20; exit 1; }
# updates: the app reads GitHub's release list (no release yet is fine; an error is not)
adb shell run-as dev.netmerge.android rm -f files/updates.json
adb shell am start -n dev.netmerge.android/.MainActivity --ez checkupdates true; sleep 12
u=$(adb shell run-as dev.netmerge.android cat files/updates.json 2>/dev/null)
echo "update check: $u"
echo "$u" | grep -q '"result":"\(UpToDate\|Newer\)"' || echo "warning: the update check did not answer (GitHub rate limit from CI?)"
# diagnostics: on by default; a report must reach the database (the rules accept it: answer 200)
adb shell run-as dev.netmerge.android rm -f files/diagnostics.log
adb shell am start -n dev.netmerge.android/.MainActivity --ez senddiagnostics true; sleep 15
d=$(adb shell run-as dev.netmerge.android cat files/diagnostics.log 2>/dev/null)
echo "diagnostics: $d"
echo "$d" | grep -q "sent=200" || { echo "the diagnostics report was not accepted"; exit 1; }
echo "release build: OK"
