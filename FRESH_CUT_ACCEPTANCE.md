# FRESH CUT — контроль переноса интерфейса

**Статус: перенос в работе, выпуск запрещён до приёмки.**

Источник: экспорт `Список покупок — Android UI · Redesign.fig`, Figma `rqliMoJ3SoiKFsa0yuLvt5`. В выгрузке 37 состояний в каждой из двух тем, всего 74 фрейма 390 × 844. Проверка исходных слоёв выполнена по декодированному FIG; живой доступ Figma ограничен квотой. Таблица ниже — перечень приёмки, а не утверждение о визуальном совпадении.

| Фрейм | Состояние | Реализация в ui/ | Light | Dark |
| --- | --- | --- | --- | --- |
| `list` | Главный список | ShoppingScreen.kt | ☐ | ☐ |
| `empty` | Пустой список | ShoppingScreen.kt | ☐ | ☐ |
| `purchased` | Все куплено | ShoppingScreen.kt | ☐ | ☐ |
| `catalog` | Каталог | ProductsScreen.kt | ☐ | ☐ |
| `stores` | Магазины | StoresScreen.kt | ☐ | ☐ |
| `store-detail` | Товары магазина | StoresScreen.kt | ☐ | ☐ |
| `product` | Карточка товара | ProductHistoryScreen.kt | ☐ | ☐ |
| `archive` | Архив | ArchiveScreen.kt | ☐ | ☐ |
| `archive-expanded` | Раскрытый архив | ArchiveScreen.kt | ☐ | ☐ |
| `statistics` | Статистика | StatsScreen.kt / ExpenseStatsPanel.kt | ☐ | ☐ |
| `history` | История товара | ProductHistoryScreen.kt | ☐ | ☐ |
| `profile` | Профиль | SettingsScreen.kt | ☐ | ☐ |
| `sync` | Синхронизация | SyncScreen.kt / NearbyPanel.kt | ☐ | ☐ |
| `add` | Добавление | AddItemSheet.kt | ☐ | ☐ |
| `results` | Поиск с результатами | AddItemSheet.kt / LentaUi.kt | ☐ | ☐ |
| `voice` | Голосовой ввод | AddItemSheet.kt | ☐ | ☐ |
| `custom` | Свой товар | CustomProductCreationSheet.kt | ☐ | ☐ |
| `scanner` | Сканер | BarcodeScannerSheet.kt | ☐ | ☐ |
| `purchase` | Отметить покупку | ShoppingScreen.kt | ☐ | ☐ |
| `weight` | Количество/вес | ShoppingScreen.kt | ☐ | ☐ |
| `select-store` | Выбрать магазин | StoreSelectField.kt | ☐ | ☐ |
| `create-store` | Создать магазин при выборе | StoreSelectField.kt | ☐ | ☐ |
| `edit-product` | Редактировать товар | ProductHistoryScreen.kt | ☐ | ☐ |
| `barcode` | Штрихкод | ProductHistoryScreen.kt | ☐ | ☐ |
| `price` | Цена и магазин | ProductHistoryScreen.kt | ☐ | ☐ |
| `remove` | Удаление | ProductHistoryScreen.kt / ProductsScreen.kt | ☐ | ☐ |
| `new-store` | Новый магазин | StoresScreen.kt | ☐ | ☐ |
| `theme` | Оформление | ThemeSheet.kt | ☐ | ☐ |
| `backup` | Резервная копия | SyncScreen.kt | ☐ | ☐ |
| `profile-edit` | Редактирование профиля | ProfileDialog.kt | ☐ | ☐ |
| `sync-pair` | Обмен QR-кодом | NearbyPanel.kt | ☐ | ☐ |
| `sources` | Источники поиска | BarcodeSourceSettingsSection.kt | ☐ | ☐ |
| `sources-disabled` | Источники отключены | BarcodeSourceSettingsSection.kt | ☐ | ☐ |
| `sources-multiple` | Несколько источников | BarcodeSourceSettingsSection.kt | ☐ | ☐ |
| `archive-empty` | Пустой архив | ArchiveScreen.kt | ☐ | ☐ |
| `archive-purchase-detail` | Позиция архива | ArchiveScreen.kt | ☐ | ☐ |
| `archive-repeat-selection` | Повторная покупка | ArchiveScreen.kt | ☐ | ☐ |

## Условия приёмки

- Снять скриншот каждого состояния на Android в обеих темах и сопоставить с исходным фреймом: геометрия, интервалы, типографика, цвета, границы, иконки, системные отступы.
- Проверить состояния на небольших экранах, при длинных названиях и с открытой клавиатурой. Не фиксировать высоту так, чтобы терялись действия или данные.
- Проверить реальные данные, пустые состояния, ошибки API, отсутствие разрешений камеры/микрофона, загрузку, пагинацию, редактирование и удаление, восстановление, синхронизацию.
- Проверить сохранность функций исходной ветки: несколько штрихкодов, отдельные цены магазинов, весовые товары, история, виджет, резервное копирование.
- В макете Inter; в проекте пока Manrope. Не считать типографику принятой, пока шрифт и метрики не сверены.
- Иконки Hugeicons Stroke Rounded, монохромные, — согласованная адаптация, не исходные глифы Figma.
- Debug-компиляция и тесты должны быть успешными. В текущем проходе не запускались.
- Отметить обе колонки только после фактической визуальной проверки. Автоматический release отключён, workflow оставлен только для ручного запуска. Не запускать до завершения всех проверок.

## Коммиты переноса

- `31ee86f` — отключение автосборки release.
- `94c2600` — панели, сканер, поиск, выбор магазина, источники.
- `2d2ca39` — каталог, история, навигация, карточки.
- `f1dd473` — основной список, архив, статистика, QR, темы.
- `11159b0` — формы редактирования, цена, вес, штрихкод и общая оболочка.

