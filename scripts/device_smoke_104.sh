#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
trap 'adb logcat -d > device-results/logcat.txt; adb shell screencap -p /sdcard/igrolov.png; adb pull /sdcard/igrolov.png device-results/screen.png || true; adb pull /sdcard/Android/data/ru.igrolov.app/files/wishlist.png device-results/wishlist.png || true; adb pull /sdcard/Android/data/ru.igrolov.app/files/game-card-last.png device-results/game-card-last.png || true; adb pull /sdcard/Android/data/ru.igrolov.app/files/offer-cache-last.png device-results/offer-cache-last.png || true; adb pull /sdcard/Android/data/ru.igrolov.app/files/offer-cache-last.xml device-results/offer-cache-last.xml || true; adb pull /sdcard/Android/data/ru.igrolov.app/files/tracked-search-suggestions.png device-results/tracked-search-suggestions.png || true; adb pull /sdcard/Android/data/ru.igrolov.app/files/tracked-search-suggestions.xml device-results/tracked-search-suggestions.xml || true' EXIT
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

adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.OfferPriceCacheUiTest#multiSelectionIsRemovedAndCachedPricesRemainVisible' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/offer-cache-batch.txt
grep -E 'OK \(1 test\)' device-results/offer-cache-batch.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/offer-cache-no-multiselect.png device-results/offer-cache-no-multiselect.png
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.OfferPriceCacheUiTest#restartRestoresCachedPricesAndOriginalSuccessfulTime' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/offer-cache-restored.txt
grep -E 'OK \(1 test\)' device-results/offer-cache-restored.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/offer-cache-restored.png device-results/offer-cache-restored.png

adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.FullRedesignScreenshotsTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/redesign-screenshots.txt
grep -E 'OK \(1 test\)' device-results/redesign-screenshots.txt
for tab in search products monitoring wishlist leaders directory; do adb pull "/sdcard/Android/data/ru.igrolov.app/files/redesign-$tab.png" "device-results/redesign-$tab.png"; done
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.GameCardMonitoringTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/game-card-monitoring.txt
grep -E 'OK \(1 test\)' device-results/game-card-monitoring.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/leader-review-monitor.png device-results/leader-review-monitor.png
adb pull /sdcard/Android/data/ru.igrolov.app/files/wishlist-monitor.png device-results/wishlist-monitor.png
cp device-results/leader-review-monitor.png device-results/redesign-leaders.png
cp device-results/wishlist-monitor.png device-results/redesign-wishlist.png

adb shell am force-stop ru.igrolov.app
run_test launchAndNavigateFiltersSettingsAndMonitoring
run_test wishlistImportIsSeparateFromOfferSearch
run_test savedWishlistProfilesShowSteamPricesAndKeepSearchSeparate
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorRegressionTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/monitor.txt
grep -E 'OK \(3 tests\)' device-results/monitor.txt
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorRoomMigrationTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/room-migration.txt
grep -E 'OK \(1 test\)' device-results/room-migration.txt
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorRoomSchemaMigrationTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/room-schema-migration.txt
grep -E 'OK \(1 test\)' device-results/room-schema-migration.txt
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorRoomScaleTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/room-scale.txt
grep -E 'OK \(1 test\)' device-results/room-scale.txt
adb shell dumpsys package ru.igrolov.app > device-results/package.txt
adb shell am instrument -w -r -e class ru.igrolov.app.StellarisUiRegressionTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/stellaris-ui.txt
grep -E 'OK \(1 test\)' device-results/stellaris-ui.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/stellaris-result.png device-results/stellaris-result.png
cp device-results/stellaris-result.png device-results/redesign-search.png
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.ManualSearchPreferencesTest#saveFiltersFromScreen' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/save-filters.txt
grep -E 'OK \(1 test\)' device-results/save-filters.txt
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.ManualSearchPreferencesTest#verifyFiltersAfterRestart' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/restart-filters.txt
grep -E 'OK \(1 test\)' device-results/restart-filters.txt
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorUiRegressionTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/monitor-ui.txt
grep -E 'OK \(1 test\)' device-results/monitor-ui.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/monitor-compact.png device-results/monitor-compact.png
adb pull /sdcard/Android/data/ru.igrolov.app/files/monitor-history.png device-results/monitor-history.png
cp device-results/monitor-history.png device-results/redesign-monitoring.png
adb pull /sdcard/Android/data/ru.igrolov.app/files/monitor-selection.png device-results/monitor-selection.png
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.TrackedTransferUiRegressionTest#addFromSearchRefreshPriceAndKeepItOnNetworkFailure' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/tracked-transfer.txt
grep -E 'OK \(1 test\)' device-results/tracked-transfer.txt
adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class 'ru.igrolov.app.TrackedTransferUiRegressionTest#restartKeepsContextAndRepairsLegacyTrackedItemWithoutReadding' ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/tracked-restart.txt
grep -E 'OK \(1 test\)' device-results/tracked-restart.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/tracked-transfer.png device-results/tracked-transfer.png
cp device-results/tracked-transfer.png device-results/redesign-products.png
adb pull /sdcard/Android/data/ru.igrolov.app/files/tracked-restart.png device-results/tracked-restart.png


adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.UnifiedListsUiTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/unified-lists.txt
grep -E 'OK \(1 test\)' device-results/unified-lists.txt
adb pull /sdcard/Android/data/ru.igrolov.app/files/unified-lists.png device-results/unified-lists.png

adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.ConcurrentPriceSearchUiTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/concurrent-prices.txt
grep -E 'OK \(1 test\)' device-results/concurrent-prices.txt

adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.MonitorEditionUiTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/monitor-edition.txt
grep -F 'OK (1 test)' device-results/monitor-edition.txt


adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.CatalogFilterPreferencesTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/catalogue-filter-preferences.txt
grep -F 'OK (1 test)' device-results/catalogue-filter-preferences.txt


adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.EditionComparisonUiTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/edition-comparison.txt
grep -F 'OK (1 test)' device-results/edition-comparison.txt


adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.PartialPriceSearchUiTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/partial-prices.txt
grep -F 'OK (1 test)' device-results/partial-prices.txt


adb shell am force-stop ru.igrolov.app
adb shell am instrument -w -r -e class ru.igrolov.app.PriceBatchUiTest ru.igrolov.app.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/price-batch.txt
grep -F 'OK (1 test)' device-results/price-batch.txt

