# Игролов 1.1.6 — исправление GGSEL 404

Статус: разработка.

- [x] Подтверждено, что прежний клиент добавлял несуществующий сегмент `/v2` и отправлял запросы обоих типов на один host.
- [x] Поиск категорий переведён на `api.ggsel.com/elastic/goods/query-categories`.
- [x] Каталожный список предложений переведён на `api4.ggsel.com/elastic/goods/categories`.
- [x] Общий клиент используется как ручным поиском, так и фоновым мониторингом.
- [x] Добавлена регрессионная проверка точных адресов, чтобы исключить возврат `/v2`.
- [x] Добавлен живой smoke test обоих read-only маршрутов в релизный GitHub Actions gate.
- [x] Версия повышена до 1.1.6 / versionCode 123.
- [ ] Unit-тесты, APK и instrumentation APK прошли GitHub Actions.
- [ ] UI-сценарии прошли на эмуляторах Android API 26 и 29.
- [ ] Живой smoke test GGSEL прошёл в GitHub Actions.
- [ ] GitHub Release `v1.1.6` опубликован с `Igrolov-1.1.6.apk`.
