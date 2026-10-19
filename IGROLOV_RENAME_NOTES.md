# Игролов 1.0.1

Переименование приложения на основе Plati Steam Tracker 1.0.1.

- Название на устройстве: Игролов
- Android applicationId: `ru.igrolov.app`
- Kotlin namespace/package: `ru.igrolov.app`
- Gradle project name: `Igrolov`
- Версия: 1.0.1 (versionCode 103)
- Поиск и мониторинг Plati сохранены; GGSEL пока не интегрирован.

## Подпись
Откройте проект в Android Studio и выберите Build → Generate Signed Bundle / APK.
Создайте и храните постоянный JKS-ключ у себя. Пароли и JKS не включены в архив.

## Важно
Из-за изменения applicationId Android считает «Игролов» отдельным приложением.
Данные установленного Plati Steam Tracker автоматически не переносятся.
