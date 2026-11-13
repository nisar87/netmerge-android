#!/bin/sh
# Roaming: unless the setting allows it, merging lets mobile data go while it roams (abroad it can cost a lot), and
# takes it back once home. The emulator's modem is put on roaming with "adb emu gsm data roaming".
P=dev.netmerge.probe.test/androidx.test.runner.AndroidJUnitRunner
ev() { adb shell run-as dev.netmerge.android cat files/events.log 2>/dev/null; }
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
adb shell run-as dev.netmerge.android rm -f files/events.log
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true --ez ondemand false --ez roaming false
for i in $(seq 1 60); do ev | grep -q started && break; sleep 1; done
ev | grep -q started || { echo "merging did not start"; exit 1; }
sleep 5
adb emu gsm data roaming
sleep 5
if ! adb shell dumpsys telephony.registry | grep -i -q -E "roaming(type)?=(true|[1-9])|isRoaming=true|mDataRoaming=true"; then
  echo "warning: the emulator's modem did not report roaming; roaming not tested here"
  adb emu gsm data home
  echo "roaming: OK (skipped)"; exit 0
fi
for i in $(seq 1 20); do ev | grep -q "mobile released: roaming" && break; sleep 1; done
ev | grep -q "mobile released: roaming" || { echo "merging kept mobile data while roaming"; ev | grep -v stats | tail -6 | cut -c1-160; adb emu gsm data home; exit 1; }
adb shell am instrument -w -r -e class dev.netmerge.probe.ProbeTest#pagesOpen $P | grep -q "^OK (" || { echo "pages failed while roaming"; adb emu gsm data home; exit 1; }
adb emu gsm data home
for i in $(seq 1 30); do ev | sed -n '/mobile released: roaming/,$p' | grep -q "mobile held" && break; sleep 1; done
ev | sed -n '/mobile released: roaming/,$p' | grep -q "mobile held" || { echo "mobile data not taken back after roaming"; exit 1; }
adb shell am start -n dev.netmerge.android/.MainActivity --ez merge false; sleep 2
echo "roaming: OK"
