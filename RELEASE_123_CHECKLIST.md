# Чек-лист релиза 1.2.3

- [x] Добавлены подразделы лидеров продаж: топ продаж, популярные новинки, топ со скидками.
- [x] Новинки берутся из Steam featured category для региона RU.
- [x] Статус Steam RU и цена проверяются через appdetails для RU; прежняя логика неизвестного статуса сохранена.
- [x] Скидочная подборка фильтрует загруженный топ по подтверждённому discount_percent Steam RU.
- [x] Добавлены unit-тесты парсинга новинок и фильтра скидок.
- [x] Добавлены UI assertions для трёх подразделов.
- [x] Обновлены versionName/versionCode до 1.2.3 / 128.
- [x] GitHub Actions run [39729585160](https://github.com/Magral/Igrolov/actions/runs/39729585160): unit-тесты, Debug APK, instrumentation APK, GGSEL live smoke, UI на API 26 и 29 — успешно.
- [x] APK опубликован в [GitHub Release v1.2.3](https://github.com/Magral/Igrolov/releases/tag/v1.2.3).
- SHA-256 APK: `632a1c048de78f0dc3ebdf7100ec316353d5e8a4290d76e8e16579b5984f772f`.
- Release commit: `80d3bff56cf9c3b09477466690163ddc887c92ee`.
