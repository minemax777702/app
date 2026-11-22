# Карта проекта «Список покупок»

## Назначение

Android-приложение для ведения списка покупок: подсказки товаров, отметка покупок, архив поездок и статистика, профиль, обмен данными между телефонами через QR, резервное копирование в файл и домашний виджет. Учёт цен удалён.

## Стек и сборка

- Kotlin, Jetpack Compose + Material 3
- Room 2.8.5 (БД `shopping.db`, schema v9), Coroutines/Flow
- CameraX + ZXing — QR-синхронизация
- Glance — AppWidget
- Gradle Kotlin DSL, один модуль `:app`; minSdk 29, target/compileSdk 37
- Версии: `gradle/libs.versions.toml`; конфигурация: `app/build.gradle.kts`
- По правилу проекта после изменений проверяют релиз: `.\gradlew.bat :app:assembleRelease`; для артефактов также `:app:bundleRelease`. Инструкции по JDK и проверке релиза на эмуляторе — в `CLAUDE.md`.

## Точки входа

- `ShoppingApp.kt` — контейнер зависимостей: БД, репозиторий, профиль, тема, каталог товаров.
- `ui/MainActivity.kt` — запуск Compose и темы.
- `ui/ShoppingScreen.kt` — главный экран и переходы к товарам, архиву, статистике и синхронизации.
- `widget/ShoppingWidgetReceiver.kt` → `ShoppingWidget.kt` — домашний виджет и покупка из него.
- `AndroidManifest.xml` — Activity, widget receiver, разрешения камеры и микрофона.

## Архитектура и поток данных

```text
Compose screens
      ↕ StateFlow / события
ShoppingViewModel ── SyncController
      ↕
ShoppingRepository
      ↕
ShoppingDao → Room / shopping.db

Дополнительно: ProductCatalog ← raw/products.txt
               QR UI ↔ sync/* ↔ Repository
               Repository → обновление Glance widget
```

## Пакеты

| Путь | Ответственность |
|---|---|
| `data/Entities.kt` | Room-сущности: текущие товары, история, архивы, скрытые товары, tombstones, пользователи |
| `data/ShoppingDao.kt` | SQL/Flow-доступ ко всем данным |
| `data/ShoppingRepository.kt` | бизнес-операции, транзакции, архивирование, backup/sync, обновление виджета |
| `data/AppDatabase.kt` | БД и миграции 1→9; экспорт схем в `app/schemas/`. Миграция 8→9 сохраняет архив, удаляет `purchases`, `product_prices`, поле `pricesAt` и tombstones цен |
| `data/UserProfile.kt`, `TripTime.kt` | локальный профиль и расчёт времени походов |
| `ui/ShoppingViewModel.kt` | состояние главного UI, подсказки, undo, профиль и архив |
| `ui/*Screen.kt` | экраны списка, товаров, архива, статистики и QR-синхронизации |
| `ui/ProductHistoryScreen.kt` | история купленных товаров: поиск, дата последней покупки и подробности по датам; общий архив и покупки из текущего списка |
| `ui/AddItemSheet.kt`, `ProfileDialog.kt`, `ThemeSheet.kt` | диалоги и нижние панели |
| `ui/VoiceInput.kt`, `QrScanner.kt` | голосовой ввод и сканирование камеры |
| `ui/Stats.kt`, `UserStats.kt`, `ArchiveFormat.kt` | чистые расчёты/преобразования для UI |
| `ui/theme/` | тема, палитра, типографика, анимации и настройки |
| `catalog/` | нормализация русского текста и поиск по каталогу |
| `sync/SyncModel.kt`, `SyncMerge.kt` | модель снимка и правила объединения данных |
| `sync/SyncCodec.kt`, `SyncFrames.kt`, `SyncTransfer.kt` | кодирование и многочастный протокол передачи |
| `sync/QrEncoder.kt`, `QrDecoder.kt`, `Base45.kt` | QR/Base45 транспорт |
| `sync/Backup.kt`, `ui/SyncController.kt`, `ui/SyncScreen.kt` | формат резервной копии, сохранение и восстановление через системный выбор файлов; контроллер также управляет QR-обменом и его отменой |
| `widget/` | отображение списка и действия AppWidget |

Учёт цен удалён из приложения и синхронизации. Формат версии 1 сохраняет пустые служебные поля; при чтении старых копий и сообщений цены пропускаются. Исторические миграции Room сохраняются для обновления старых баз.

## Ресурсы и данные

- `res/raw/products.txt` — встроенный каталог товаров.
- `res/values/` — строки, тема, plural-формы; интерфейс ориентирован на русский язык.
- `res/drawable/`, `res/font/manrope.ttf` — иконки и шрифт.
- `extension/` — справочные изображения и PDF, в сборку приложения не входят.

## Тесты

- `app/src/test/` — unit-тесты протокола/merge синхронизации, статистики, архива, каталога и времени поездки.
- `app/src/androidTest/` — базовый instrumented-тест и интеграционный тест репозитория.
- Запуск: `./gradlew test` (Windows: `.\gradlew.bat test`), instrumented: `.\gradlew.bat connectedAndroidTest`.

## Где менять типовые функции

- Поведение списка/покупки/архива: `ShoppingViewModel.kt` + `ShoppingRepository.kt`.
- Ручное завершение списка переносит купленное в архив, а непокупленные товары оставляет в списке для следующего похода.
- Схему данных: `Entities.kt`, `ShoppingDao.kt`, новая миграция в `AppDatabase.kt` и schema JSON.
- Экран или навигацию: соответствующий `ui/*Screen.kt`, корень — `ShoppingScreen.kt`.
- QR-совместимость: прежде всего `sync/SyncModel.kt`, `SyncCodec.kt`, `SyncMerge.kt`; обязательно обновить sync-тесты.
- Каталог: `res/raw/products.txt`, поиск — `catalog/ProductCatalog.kt`.
- Виджет: `widget/ShoppingWidget.kt`; после изменений данных учитывать его обновление из репозитория.

## Локальные снимки

`data/LocalBackups.kt` хранит текущий снимок и снимки перед завершением списка; лимит задаётся на экране синхронизации. `Snapshot.selectSection` ограничивает передаваемые данные и удаления выбранным разделом. Копии на устройстве не заменяют экспорт в файл.
