# UI Preview — Compose Multiplatform / Kotlin/Wasm

Это **первый изолированный прототип** браузерного предпросмотра Android UI. Он написан на Compose (не HTML-перерисовка), запускается как `wasmJs` и пока использует **тестовые данные**.

## Быстрый запуск

Из корня репозитория:

### Windows PowerShell

```powershell
.\gradlew.bat :ui-preview:wasmJsBrowserDevelopmentRun
```

### Linux / macOS

```sh
./gradlew :ui-preview:wasmJsBrowserDevelopmentRun
```

Откройте URL из вывода Gradle (обычно `http://localhost:8080`). При правках Kotlin браузерный сборщик пересоберёт предпросмотр; перезагрузите вкладку.

### Статическая сборка для публикации

```powershell
.\gradlew.bat :ui-preview:wasmJsBrowserDistribution
```

Каталог: `ui-preview/build/dist/wasmJs/productionExecutable/`.

## Уже есть

- Первый экран «Информация о товаре» с цветами светлой/тёмной темы приложения.
- Карточка штрихкодов с добавлением, изменением и удалением.
- Реальные Compose Material3 bottom sheets, текстовое поле с кнопкой камеры.
- Проверка контрольной цифры EAN-8 / UPC-A / EAN-13.
- Управление шириной предпросмотра и переключением темы.
- Демонстрационные товары и цены; изменение кодов только в памяти страницы.

## Отличия от настоящего Android-приложения

- Пока это **не исходные Android-компоненты из `app/`**, а пилотная реализация на Compose Multiplatform в `commonMain`. Подключение одних и тех же компонентов одновременно к Android и Wasm — следующий этап, требующий выделения UI из Android-зависимых `ViewModel`, `Room`, `LocalContext`, ресурсов и камеры.
- Кнопка камеры в браузере **подставляет тестовый код**, не использует физическую камеру и ZXing.
- Сохранение в Room, реальная синхронизация, настоящие покупки и сеть не подключены.
- Этот модуль не меняет конфигурацию `:app` и не нужен для APK-сборки.

## Технические сведения

- Kotlin: версия из `gradle/libs.versions.toml`, сейчас 2.4.21.
- Compose Multiplatform: 1.12.2.
- Используется Kotlin/Wasm с поддержкой WasmGC; необходим современный браузер.
- Код предпросмотра: `ui-preview/src/commonMain/kotlin/com/mok/buylist/preview/PreviewApplication.kt`.
- Точка входа Wasm: `ui-preview/src/wasmJsMain/kotlin/com/mok/buylist/preview/Main.kt`.

## Перед интеграцией

- [ ] Запустить `:ui-preview:wasmJsBrowserDevelopmentRun` и убедиться, что dev-server доступен.
- [ ] Проверить `:ui-preview:wasmJsBrowserDistribution`.
- [ ] Прогнать `:app:testDebugUnitTest :app:assembleRelease` и убедиться, что Android-приложение не изменилось.
- [ ] Постепенно выносить в общий код только чистые Compose-компоненты; Android-специфичные сервисы передавать через callbacks/адаптеры.
