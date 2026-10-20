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
  grep -E 'OK \(1 test\)' "device-results/$case_name.txt"
  if grep -E 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "device-results/$case_name.txt"; then return 1; fi
}
run_test seedUpgradeData
adb install -r "$current_apk"
run_test verifyUpgradeData
run_test launchAndNavigateFiltersSettingsAndMonitoring
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorRegressionTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/monitor.txt
grep -E 'OK \(1 test\)' device-results/monitor.txt
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorRoomMigrationTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/room-migration.txt
grep -E 'OK \(1 test\)' device-results/room-migration.txt
adb shell dumpsys package ru.igrolov.app > device-results/package.txt
adb shell am instrument -w -r -e class ru.igrolov.app.StellarisUiRegressionTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/stellaris-ui.txt
grep -E 'OK \(1 test\)' device-results/stellaris-ui.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/stellaris-result.png device-results/stellaris-result.png
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.ManualSearchPreferencesTest#saveFiltersFromScreen' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/save-filters.txt
grep -E 'OK \(1 test\)' device-results/save-filters.txt
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.ManualSearchPreferencesTest#verifyFiltersAfterRestart' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/restart-filters.txt
grep -E 'OK \(1 test\)' device-results/restart-filters.txt
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorUiRegressionTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/monitor-ui.txt
grep -E 'OK \(1 test\)' device-results/monitor-ui.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/monitor-history.png device-results/monitor-history.png
adb pull /sdcard/Android/data/ru.igrolov.app/files/monitor-selection.png device-results/monitor-selection.png
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.TrackedTransferUiRegressionTest#addFromSearchRefreshPriceAndKeepItOnNetworkFailure' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/tracked-transfer.txt
grep -E 'OK \(1 test\)' device-results/tracked-transfer.txt
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.TrackedTransferUiRegressionTest#restartKeepsContextAndRepairsLegacyTrackedItemWithoutReadding' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/tracked-restart.txt
grep -E 'OK \(1 test\)' device-results/tracked-restart.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/tracked-transfer.png device-results/tracked-transfer.png
adb pull /sdcard/Android/data/ru.igrolov.app/files/tracked-restart.png device-results/tracked-restart.png
