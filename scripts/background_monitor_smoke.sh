#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
apk=$(find current -type f -name '*.apk' -print -quit)
tests=$(find tests -type f -name '*.apk' -print -quit)
adb install -r "$apk"
adb install -r "$tests"
adb shell pm grant ru.igrolov.app android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -r -e class ru.igrolov.app.BackgroundMonitorTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/background-monitor-api34.txt
grep -F 'OK (3 tests)' device-results/background-monitor-api34.txt
