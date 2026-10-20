#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
trap 'adb logcat -d > device-results/logcat.txt; adb shell screencap -p /sdcard/igrolov.png; adb pull /sdcard/igrolov.png device-results/screen.png || true' EXIT
baseline_apk=$(find baseline -type f -name '*.apk' -print -quit)
current_apk=$(find current -type f -name '*.apk' -print -quit)
test_apk=$(find tests -type f -name '*.apk' -print -quit)
adb install -r "$baseline_apk"
adb install -r "$test_apk"
run_test() {
  local case_name="$1"
  adb shell am instrument -w -r -e class "ru.igrolov.app.ReleaseSmokeTest#$case_name" ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee "device-results/$case_name.txt"
  rg 'OK \(1 test\)' "device-results/$case_name.txt"
  if rg 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "device-results/$case_name.txt"; then return 1; fi
}
run_test seedUpgradeData
adb install -r "$current_apk"
run_test verifyUpgradeData
run_test launchAndNavigateFiltersSettingsAndMonitoring
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorRegressionTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/monitor.txt
rg 'OK \(1 test\)' device-results/monitor.txt
adb shell dumpsys package ru.igrolov.app > device-results/package.txt
