# Чек-лист релиза 1.2.3

- [x] Добавлены подразделы лидеров продаж: топ продаж, популярные новинки, топ со скидками.
- [x] Новинки берутся из Steam featured category для региона RU.
- [x] Статус Steam RU и цена проверяются через appdetails для RU; прежняя логика неизвестного статуса сохранена.
- [x] Скидочная подборка фильтрует загруженный топ по подтверждённому discount_percent Steam RU.
- [x] Добавлены unit-тесты парсинга новинок и фильтра скидок.
- [x] Добавлены UI assertions для трёх подразделов.
- [x] Обновлены versionName/versionCode и release metadata.
- [ ] GitHub Actions: unit, Debug APK, instrumentation APK, GGSEL live smoke, UI на API 26 и 29.
- [ ] APK опубликован в GitHub Release v1.2.3 после успешного CI.
