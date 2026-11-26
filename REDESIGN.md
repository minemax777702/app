# FRESH CUT — покрытие редизайна

Основа: feat/multi-catalog-stores-prices. Ветка: feat/figma-redesign.
Источник: Список покупок — Android UI · Redesign.fig, экспорт 26.11.2026, 37 состояний × Light/Dark (74 фрейма).
Все перечисленные ниже состояния связаны с существующим Kotlin UI, общими компонентами или выделенными маршрутами. Это карта переноса, а не утверждение об идентичности пикселей или пройденных тестах.

| Макет | Реализация |
| --- | --- |
| list | ShoppingScreen, FreshCutComponents |
| empty | ShoppingScreen: EmptyState, empty progress |
| purchased | ShoppingScreen: allPurchased |
| catalog | SettingsScreen, ProductsScreen |
| stores | SettingsScreen, StoresScreen |
| store-detail | StoresScreen: openedStore |
| product | ProductHistoryScreen: ProductDetailsScreen |
| archive | ArchiveScreen |
| archive-expanded | ArchiveCard |
| statistics | StatsScreen, ExpenseStatsPanel |
| history | ProductHistoryScreen, ProductDetailsScreen: история цен/покупок |
| profile | SyncScreen: profile |
| sync | SyncScreen: nearby, NearbyPanel |
| add | AddItemSheet |
| results | AddItemSheet, LentaUi |
| voice | AddItemSheet: ListeningIndicator |
| custom | CustomProductCreationSheet |
| scanner | BarcodeScannerSheet |
| purchase | ShoppingScreen: pendingPurchase/scannedCustom |
| weight | ShoppingScreen: weightItem |
| select-store | StoreSelectField, AppDropdownMenu |
| create-store | StoreSelectField: adding |
| edit-product | ProductHistoryScreen: editingOwnDetails |
| barcode | ProductHistoryScreen: barcodeEditorOpen |
| price | ProductHistoryScreen: editingPurchase/editingStoreLink |
| remove | ProductHistoryScreen: deleteOwnPending; подтверждения AppSheet |
| new-store | StoresScreen: creating; StoreSelectField |
| theme | ThemeSheet |
| backup | SyncScreen: showBackup |
| profile-edit | ProfileDialog |
| sync-pair | NearbyPanel: pairingExpanded/showingQr/scanning |
| sources | SyncScreen: sources; BarcodeSourceSettingsSection |
| sources-disabled | BarcodeSourceSettingsSection: enabled.isEmpty() |
| sources-multiple | BarcodeSourceSettingsSection: несколько включённых |
| archive-empty | ArchiveScreen: EmptyArchive |
| archive-purchase-detail | ArchiveProductDetails |
| archive-repeat-selection | ArchiveRepeatScreen |

## Дизайн-система
- Палитра из исходных токенов Figma. Общие отступы 20 dp, поля/primary 54 dp, secondary 52 dp, основные скругления 16/20/24/28 dp.
- Hugeicons Stroke Rounded, монохромные контурные ресурсы. Без стороннего runtime SDK.
- Общие заголовки, навигация, сегменты, dropdown, modal sheet, поля и кнопки.
- Room, синхронизация, цены и ViewModel не переписаны. Текущая подпись сохранена.
- Не использовать тестовые данные из макетов в реальных экранах.

## Проверка перед релизом
- [ ] GitHub Actions: компиляция, подписанный APK и JVM-тесты последнего коммита.
- [ ] Ручная проверка всех 37 состояний в обеих темах на устройстве/эмуляторе, включая пустые, загрузку, ошибки, отказ в разрешении камеры/микрофона, выключенные источники, длинные названия, клавиатуру и системные отступы.
- [ ] Сравнение скриншотов с 74 фреймами Figma при 390 × 844 и других ширинах.
- [ ] Проверить обновление ранее установленного APK с тем же сертификатом и сохранение БД.
- [ ] Inter: в макете Inter; в проекте пока используется Manrope. Для точного совпадения добавить лицензированный ресурс Inter и OFL после получения бинарного файла официального релиза.
- [ ] Остаточные нестандартные состояния, которых нет в Figma (например системные диалоги разрешений, уведомления, виджет, QR-передача), проверить дополнительно. Системные диалоги не изменять.
