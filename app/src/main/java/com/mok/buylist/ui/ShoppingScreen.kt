package com.mok.buylist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mok.buylist.R
import com.mok.buylist.lentaCatalog
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.data.PartialAmount
import java.text.NumberFormat
import java.util.Locale
import com.mok.buylist.data.nameKeyOf
import com.mok.buylist.themeSettings
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.Motion
import com.mok.buylist.ui.theme.ThemeMode
import com.mok.buylist.widget.ShoppingWidget
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(viewModel: ShoppingViewModel = viewModel()) {
    val context = LocalContext.current
    val themeSettings = context.themeSettings
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()

    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val customProducts by viewModel.customProducts.collectAsStateWithLifecycle()
    val customBarcodes by viewModel.customBarcodes.collectAsStateWithLifecycle()
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val plannedStores by viewModel.plannedStores.collectAsStateWithLifecycle()
    val customStoreRequest by viewModel.customStoreRequest.collectAsStateWithLifecycle()
    val storeLinks by viewModel.storeProducts.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val refresh by viewModel.priceRefresh.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    LaunchedEffect(refresh) {
        val result = refresh?.second
        if (result != null && result != "Загрузка") {
            activity?.notifyUser(result)
            viewModel.priceRefresh.value = null
        }
    }
    val archive by viewModel.archive.collectAsStateWithLifecycle()
    val bought by viewModel.bought.collectAsStateWithLifecycle()
    val canFinish by viewModel.canFinish.collectAsStateWithLifecycle()
    val finishEnabled by viewModel.finishEnabled.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val appMode by themeSettings.app.collectAsStateWithLifecycle()
    val widgetMode by themeSettings.widget.collectAsStateWithLifecycle()
    val lentaProducts by context.lentaCatalog.products.collectAsStateWithLifecycle()
    val lentaAddress by context.lentaCatalog.address.collectAsStateWithLifecycle()
    val lentaById = remember(lentaProducts) { lentaProducts.associateBy { it.id } }
    val lentaBarcodes by context.lentaCatalog.barcodeRows.collectAsStateWithLifecycle()
    val sharedIdentities = remember(lentaProducts, customProducts, lentaBarcodes, customBarcodes) {
        com.mok.buylist.data.sharedCatalogIdentities(
            lentaProducts, customProducts, lentaBarcodes, customBarcodes)
    }
    val recordedPurchases = remember(archive, bought) { expensePurchases(archive, bought) }
    val shopPricesByKey = remember(rows, stores, storeLinks, recordedPurchases, sharedIdentities, lentaProducts, lentaAddress) {
        rows.orEmpty().mapNotNull { row ->
            val key = when {
                row.item.customProductId.isNotBlank() -> "custom:${row.item.customProductId}"
                row.item.productId > 0 -> "lenta:${row.item.productId}"
                else -> return@mapNotNull null
            }
            row.item.id to productShopPrices(
                key, stores, storeLinks, recordedPurchases, sharedIdentities, lentaProducts, lentaAddress)
        }.toMap()
    }

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var scanPurchase by remember { mutableStateOf(false) }
    var scanLookup by remember { mutableStateOf<String?>(null) }
    var pendingWeight by remember { mutableStateOf<Pair<String, LentaProduct>?>(null) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showArchive by rememberSaveable { mutableStateOf(false) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsTab by rememberSaveable { mutableIntStateOf(0) }
    var addressRequest by rememberSaveable { mutableIntStateOf(0) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var detailsRow by remember { mutableStateOf<ListRow?>(null) }
    var weightItem by remember { mutableStateOf<com.mok.buylist.data.ShoppingItem?>(null) }
    var pendingPurchase by remember { mutableStateOf<com.mok.buylist.data.ShoppingItem?>(null) }
    var scannedCustom by remember { mutableStateOf<com.mok.buylist.data.CustomProduct?>(null) }

    var showNotifications by rememberSaveable { mutableStateOf(false) }
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    val visibleRows = remember(rows) { rows?.filter { it.state == RowState.ACTIVE } }
    val allPurchased = progress.total > 0 && progress.bought == progress.total

    fun navigate(destination: FreshCutDestination) {
        showNotifications = false
        showArchive = destination == FreshCutDestination.ARCHIVE
        showStats = destination == FreshCutDestination.STATS
        showSettings = destination == FreshCutDestination.CATALOG || destination == FreshCutDestination.PROFILE
        if (destination == FreshCutDestination.CATALOG) settingsTab = 0
        if (destination == FreshCutDestination.PROFILE) settingsTab = 1
    }

    val destination = when {
        showSettings -> if (settingsTab == 0) FreshCutDestination.CATALOG else FreshCutDestination.PROFILE
        showArchive -> FreshCutDestination.ARCHIVE
        showStats -> FreshCutDestination.STATS
        else -> FreshCutDestination.LIST
    }
    val screenStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().background(p.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (destination) {
                FreshCutDestination.LIST -> screenStates.SaveableStateProvider("list") {
        if (showNotifications) {
            BackHandler { showNotifications = false }
            val availableUndoIds by viewModel.availableUndoIds.collectAsStateWithLifecycle()
            NotificationsScreen(actions, availableUndoIds, onBack = { showNotifications = false },
                onUndo = viewModel::undoAction, onClear = viewModel::clearNotifications)
        } else Column(Modifier.fillMaxSize()) {
            val shoppingListState = rememberLazyListState()
            Header(
                progress = progress,
                totals = totals,
                themeEnabled = !showSettings && !showArchive && !showStats && profile != null && !renaming,
                // Быстрое переключение меняет и приложение, и виджет; раздельный выбор — в настройках темы.
                onToggleTheme = {
                    val next = if (p.isDark) ThemeMode.LIGHT else ThemeMode.DARK
                    themeSettings.setApp(next)
                    themeSettings.setWidget(next)
                    scope.launch { ShoppingWidget().updateAll(context) }
                },
                onNotifications = { showNotifications = true },
                canFinish = canFinish && finishEnabled,
                onFinish = viewModel::finishTrip,

            )
            // Пустое состояние и список сменяют друг друга плавно; пока список грузится, null — ничего не рисуем.
            Crossfade(
                targetState = visibleRows?.isEmpty(),
                modifier = Modifier.weight(1f),
                animationSpec = Motion.smooth(),
                label = "list-or-empty",
            ) { empty ->
                val list = visibleRows
                when {
                    empty == null || list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = p.textSecondary) }
                    empty && allPurchased -> Column(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    ) {
                        Box(Modifier.size(64.dp).background(p.field, RoundedCornerShape(22.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                                tint = p.text, modifier = Modifier.size(24.dp))
                        }
                        Text("Все товары куплены", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text("В активном списке ничего не осталось.", color = p.textSecondary, fontSize = 14.sp)
                    }
                    empty -> EmptyState(Modifier.fillMaxSize())
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        state = shoppingListState,
                        overscrollEffect = null,
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 8.dp,
                            bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(list, key = { it.item.id }, contentType = { "row" }) { row ->
                            ItemRow(
                                row = row,
                                product = lentaById[row.item.productId],
                                shopPrices = shopPricesByKey[row.item.id].orEmpty(),
                                plannedStoreId = plannedStores[row.item.id],
                                ownEstimate = customProducts.firstOrNull { it.id == row.item.customProductId }?.estimatedPriceKopecks,
                                address = lentaAddress,
                                onQuantity = { viewModel.setQuantity(row.item, it) },
                                onWeight = { weightItem = row.item },
                                onBuy = {
                                    if (row.item.productId > 0) viewModel.buy(row.item)
                                    else pendingPurchase = row.item
                                },
                                onUndo = { viewModel.restore(row.item) },
                                onRemove = { viewModel.remove(row.item) },
                                onDetails = { detailsRow = row },
                                // Animate visibility, not placement: keep the scroll anchor stable.
                                modifier = Modifier.animateItem(
                                    fadeInSpec = Motion.smooth(),
                                    placementSpec = null,
                                    fadeOutSpec = Motion.smooth(Motion.Quick),
                                ),
                            )
                        }
                    }
                }
            }

            ShoppingQuickActions(
                allPurchased = allPurchased,
                finishEnabled = finishEnabled,
                onFinish = viewModel::finishTrip,
                onAdd = { showAdd = true },
                onScan = { scanPurchase = true },
            )
        }
                }
                FreshCutDestination.ARCHIVE -> screenStates.SaveableStateProvider("archive") {
            BackHandler { showArchive = false }
            ArchiveScreen(
                archive = archive,
                onBack = { showArchive = false },
                onOpenStats = { navigate(FreshCutDestination.STATS) },
                onRepeat = { names, quantities, events -> viewModel.repeatArchived(names, quantities, events) { showArchive = false } },
                onDelete = viewModel::deleteArchived,
                users = users,
                meId = profile?.id.orEmpty(),
                viewModel = viewModel,
            )
        }

                FreshCutDestination.CATALOG, FreshCutDestination.PROFILE -> screenStates.SaveableStateProvider(destination.name) {
            BackHandler { showSettings = false }
            SettingsScreen(
                tab = settingsTab,
                onTab = { settingsTab = it },
                onBack = { showSettings = false },
                profile = profile,
                onRename = { renaming = true },
                onOpenTheme = { showTheme = true },
                addressRequest = addressRequest,
                onAdd = { product -> viewModel.addProduct(product) { added -> activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке") } },
                viewModel = viewModel,
                archive = archive,
                bought = bought,
                users = users,
            )
        }

                FreshCutDestination.STATS -> screenStates.SaveableStateProvider("stats") {
            BackHandler { showStats = false }
            StatsScreen(
                archive = archive,
                bought = bought,
                users = users,
                meId = profile?.id.orEmpty(),
                onBack = { showStats = false },
                viewModel = viewModel,
            )
        }
            }
        }
        FreshCutNavigation(destination, ::navigate,
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp))
    }

    // Первый запуск: без имени дальше не пускаем — нужен идентификатор, которым подписываются все действия.
    if (profile == null) {
        ProfileDialog(initialName = "", required = true, onSave = viewModel::saveProfile, onDismiss = {})
    } else if (renaming) {
        ProfileDialog(
            initialName = profile?.name.orEmpty(),
            required = false,
            onSave = { name ->
                viewModel.saveProfile(name)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }

    if (showAdd) {
        AddItemSheet(
            onQueryChange = {},
            onAdd = viewModel::addProduct,
            customProducts = customProducts,
            customBarcodes = customBarcodes,
            onAddCustom = viewModel::addCustomProduct,
            onCreateCustom = viewModel::addManualProduct,
            onDismiss = { showAdd = false },
            onAddressSettings = { showAdd = false; navigate(FreshCutDestination.PROFILE); addressRequest++ },
        )
    }
    customStoreRequest?.let { request ->
        AppSheet(onDismissRequest = viewModel::cancelCustomStore, containerColor = p.sheet,
            title = { SheetHeader("Выбрать магазин") }, text = {
                Text(request.product.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    request.prices.forEach { option ->
                        FreshCutListRow(R.drawable.ic_store, option.storeName,
                            value = option.priceKopecks?.let { java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() + " ₽" } ?: "—",
                            showChevron = false, onClick = { viewModel.confirmCustomStore(option.storeId) })
                    }
                }
            }, confirmButton = {
                SheetActionButton(onClick = { viewModel.confirmCustomStore(null) }) { Text("Без выбора магазина") }
            })
    }
    pendingPurchase?.let { item ->
        val ownProduct = customProducts.firstOrNull { it.id == item.customProductId }
        val preferred = displayShopPrice(
            if (item.productId > 0) "lenta:${item.productId}" else "custom:${item.customProductId}",
            shopPricesByKey[item.id].orEmpty(), plannedStores[item.id])
        val suggestedPrice = preferred?.priceKopecks ?: ownProduct?.estimatedPriceKopecks
        val isLenta = item.productId > 0
        val isWeighted = ownProduct?.isWeighted == true || item.weightGrams != null
        var price by remember(item.id) {
            mutableStateOf(suggestedPrice?.let {
                java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString()
            }.orEmpty())
        }
        var selectedStore by remember(item.id) { mutableStateOf(stores.firstOrNull { it.id == preferred?.storeId }) }
        var priceRevision by remember(item.id) { mutableIntStateOf(0) }
        var weightText by remember(item.id) {
            mutableStateOf(java.math.BigDecimal.valueOf(
                (item.weightGrams ?: 1000).toLong(), 3).stripTrailingZeros().toPlainString())
        }
        val weightGrams = if (isWeighted)
            com.mok.buylist.data.parseWeightGrams(weightText) else null
        val priceValue = com.mok.buylist.data.parsePriceKopecks(price)
        AppSheet(
            onDismissRequest = { pendingPurchase = null },
            containerColor = p.sheet,
            title = { SheetHeader("Отметить покупку") },
            minBodyHeight = 430.dp,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.name, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (isWeighted) SheetTextField(
                        weightText, { weightText = it }, "Вес, кг",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    SheetTextField(price, { price = it; priceRevision++ },
                        if (isWeighted) "Цена за кг, ₽" else "Цена за единицу, ₽",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    if (isLenta) {
                        SheetFixedValue("Магазин", "Лента")
                    } else StoreSelectField(stores, selectedStore, onSelected = { store ->
                        selectedStore = store
                        price = suggestedPrice?.let {
                            java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString()
                        }.orEmpty()
                        val revision = ++priceRevision
                        if (store != null) {
                            val key = when {
                                item.customProductId.isNotBlank() -> "custom:${item.customProductId}"
                                item.productId > 0 -> "lenta:${item.productId}"
                                else -> ""
                            }
                            if (key.isNotBlank()) viewModel.latestStorePrice(key, store.id) { known ->
                                if (selectedStore?.id == store.id && priceRevision == revision && known != null)
                                    price = java.math.BigDecimal.valueOf(known, 2)
                                        .stripTrailingZeros().toPlainString()
                            }
                        }
                    }, onCreate = viewModel::createStore)
                }
            },
            confirmButton = {
                SheetActionButton(enabled = (price.isBlank() || priceValue != null) &&
                    (!isWeighted || weightGrams != null), onClick = {
                    viewModel.buy(item, priceValue,
                        if (isLenta) "Лента" else selectedStore?.name.orEmpty(), weightGrams)
                    pendingPurchase = null
                }) { Text("Куплено") }
            },
        )
    }
    weightItem?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.weightGrams?.let {
            java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()
        }.orEmpty()) }
        var byWeight by remember(item.id) { mutableStateOf(true) }
        val grams = com.mok.buylist.data.parseWeightGrams(text)
        AppSheet(onDismissRequest = { weightItem = null }, containerColor = p.sheet,
            title = { SheetHeader("Количество") }, minBodyHeight = 320.dp,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.name, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    FreshCutSegments(listOf("Килограммы", "Штуки"), if (byWeight) 0 else 1, { byWeight = it == 0 })
                    if (byWeight) SheetTextField(text, { text = it }, "Вес, кг",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    else Text("Количество можно изменить кнопками в списке.", color = p.textSecondary, fontSize = 14.sp)
                }
            },
            confirmButton = { SheetActionButton(enabled = !byWeight || grams != null, onClick = {
                viewModel.setWeight(item, if (byWeight) grams else null); weightItem = null
            }) { Text("Сохранить") } })
    }
    if (scanPurchase) BarcodeScannerSheet(onDismiss = { scanPurchase = false }, onCode = { code ->
        scanPurchase = false
        val matches = lentaProducts.filter { context.lentaCatalog.matchesBarcode(it, code) }
        val ownMatches = customProducts.filter { product ->
            !product.deleted && (
                customBarcodes.any { it.productId == product.id && !it.deleted &&
                    com.mok.buylist.sync.barcodeMatches(it.code, code) } ||
                (product.barcode == code && customBarcodes.none {
                    it.productId == product.id && it.code == code && it.deleted })
            )
        }
        val customAlreadyPlanned = ownMatches.singleOrNull()?.let { custom ->
            rows?.any { it.item.customProductId == custom.id && it.state == RowState.ACTIVE }
        } == true
        if (!com.mok.buylist.sync.isVariableMeasureBarcode(code) &&
            ownMatches.size == 1 && (matches.isEmpty() || customAlreadyPlanned || ownMatches.single().isWeighted)) {
            scannedCustom = ownMatches.single()
        } else if (!com.mok.buylist.sync.isVariableMeasureBarcode(code) && matches.size == 1) {
            viewModel.buyScanned(matches.single(), null) { done ->
                activity?.notifyUser(if (done) "Покупка отмечена" else "Не удалось отметить покупку")
            }
        } else scanLookup = code
    })
    scannedCustom?.let { product ->
        var price by remember(product.id) {
            mutableStateOf(product.estimatedPriceKopecks?.let {
                java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString()
            }.orEmpty())
        }
        var selectedStore by remember(product.id) { mutableStateOf<com.mok.buylist.data.ShoppingStore?>(null) }
        var priceRevision by remember(product.id) { mutableIntStateOf(0) }
        var weightText by remember(product.id) { mutableStateOf("1") }
        val weightGrams = if (product.isWeighted)
            com.mok.buylist.data.parseWeightGrams(weightText) else null
        val priceValue = com.mok.buylist.data.parsePriceKopecks(price)
        AppSheet(onDismissRequest = { scannedCustom = null }, containerColor = p.sheet,
            title = { SheetHeader("Отметить покупку") }, minBodyHeight = 430.dp,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(product.name, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (product.isWeighted) SheetTextField(
                        weightText, { weightText = it }, "Вес, кг",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    SheetTextField(price, { price = it; priceRevision++ },
                        if (product.isWeighted) "Цена за кг, ₽" else "Цена за единицу, ₽",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    StoreSelectField(stores, selectedStore, onSelected = { store ->
                        selectedStore = store
                        price = product.estimatedPriceKopecks?.let {
                            java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString()
                        }.orEmpty()
                        val revision = ++priceRevision
                        if (store != null) viewModel.latestStorePrice("custom:${product.id}", store.id) { known ->
                            if (selectedStore?.id == store.id && priceRevision == revision && known != null)
                                price = java.math.BigDecimal.valueOf(known, 2)
                                    .stripTrailingZeros().toPlainString()
                        }
                    }, onCreate = viewModel::createStore)
                }
            },
            confirmButton = {
                SheetActionButton(enabled = (price.isBlank() || priceValue != null) &&
                    (!product.isWeighted || weightGrams != null), onClick = {
                    viewModel.buyScannedCustom(product, priceValue, selectedStore?.name.orEmpty(), weightGrams) { done ->
                        activity?.notifyUser(if (done) "Покупка отмечена" else "Не удалось отметить покупку")
                    }
                    scannedCustom = null
                }) { Text("Куплено") }
            },
        )
    }
    scanLookup?.let { code ->
        var lookupQuery by remember(code) { mutableStateOf(code) }
        AppModalSheet(onDismissRequest = { scanLookup = null }, containerColor = p.sheet,
            dragHandle = { SheetDragHandle { scanLookup = null } }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
                NoticeInSheet()
                SheetHeader("Выберите товар")
                ProductSearch(lookupQuery, { lookupQuery = it })
                LentaSuggestions(lookupQuery, Modifier.weight(1f), onSettings = {
                    scanLookup = null; navigate(FreshCutDestination.PROFILE); addressRequest++
                }, onAdd = { product ->
                    scanLookup = null
                    val chosen = if (product.ean.isBlank() || product.ean == "null") product.copy(ean = code) else product
                    scope.launch {
                        context.lentaCatalog.save(chosen, code)
                        if (com.mok.buylist.sync.isVariableMeasureBarcode(code)) pendingWeight = code to chosen
                        else viewModel.buyScanned(chosen, null) { done ->
                            activity?.notifyUser(if (done) "Покупка отмечена" else "Не удалось отметить покупку")
                        }
                    }
                })
            }
        }
    }
    pendingWeight?.let { (code, product) ->
        var price by remember(code, product.id) { mutableStateOf("") }
        val amount = com.mok.buylist.data.parsePriceKopecks(price)
        AppSheet(onDismissRequest = { pendingWeight = null }, containerColor = p.sheet,
            title = { Text(product.name, color = p.text, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.mok.buylist.sync.lentaWeightGrams(code)?.let { grams ->
                        Text("${java.math.BigDecimal.valueOf(grams.toLong(), 3).toPlainString()} кг", color = p.text)
                    }
                    SheetTextField(price, { price = it }, "Цена одной упаковки",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                }
            },
            confirmButton = {
                SheetActionButton(onClick = {
                    viewModel.buyScanned(product, amount) { done ->
                        activity?.notifyUser(if (done) "Покупка отмечена" else "Не удалось отметить покупку")
                    }
                    pendingWeight = null
                }, enabled = amount != null) { Text("Куплено") }
            })
    }
    detailsRow?.let { selected ->
        ItemDetailsSheet(selected.item, lentaById[selected.item.productId], lentaAddress,
            refresh?.takeIf { it.first == selected.item.productId }?.second,
            onRefresh = { viewModel.refreshPrice(selected.item.productId) },
            customProducts = customProducts,
            customBarcodes = customBarcodes,
            storeLinks = storeLinks,
            stores = stores,
            archive = archive,
            bought = bought,
            prices = shopPricesByKey[selected.item.id].orEmpty(),
            lentaCodes = lentaBarcodes.filter { it.productId == selected.item.productId && !it.deleted }.map { it.code },
            onSaveManualPrice = { amount, done ->
                val key = if (selected.item.productId > 0) "lenta:${selected.item.productId}" else "custom:${selected.item.customProductId}"
                viewModel.saveStoreProduct(com.mok.buylist.data.lentaStoreId(), key, amount, done)
            },
        ) { detailsRow = null }
    }
    if (showTheme) {
        ThemeSheet(
            appMode = appMode,
            widgetMode = widgetMode,
            onAppMode = themeSettings::setApp,
            onWidgetMode = { mode ->
                themeSettings.setWidget(mode)
                scope.launch { ShoppingWidget().updateAll(context) }
            },
            onDismiss = { showTheme = false },
        )
    }
}

/** Основные действия в нижней правой зоне: доступны большим пальцем и не перекрывают список. */
@Composable
private fun ShoppingQuickActions(
    allPurchased: Boolean,
    finishEnabled: Boolean,
    onFinish: () -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (allPurchased) {
            Row(
                Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (finishEnabled) p.card else p.field)
                    .border(1.dp, p.line, RoundedCornerShape(16.dp))
                    .clickable(enabled = finishEnabled, role = Role.Button, onClick = onFinish)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp),
                    tint = if (finishEnabled) p.text else p.textSecondary)
                Text("Завершить покупки", color = if (finishEnabled) p.text else p.textSecondary,
                    fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        } else Spacer(Modifier.weight(1f))

        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(16.dp))
                .background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClickLabel = "Отметить покупку по штрихкоду", onClick = onScan),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_scan_barcode), "Сканер штрихкодов",
                Modifier.size(26.dp), tint = p.text)
        }
        Row(
            Modifier.height(54.dp).then(if (allPurchased) Modifier.width(54.dp) else Modifier)
                .clip(RoundedCornerShape(16.dp)).background(p.actionFill)
                .clickable(role = Role.Button, onClickLabel = "Добавить товар", onClick = onAdd)
                .padding(horizontal = if (allPurchased) 0.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (allPurchased) Arrangement.Center else Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_plus), if (allPurchased) "Добавить товар" else null,
                Modifier.size(26.dp), tint = p.actionInk)
            if (!allPurchased) Text("Добавить", color = p.actionInk,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Header(
    progress: Progress,
    totals: com.mok.buylist.data.ShoppingTotals,
    themeEnabled: Boolean,
    onToggleTheme: () -> Unit,
    onNotifications: () -> Unit,
    canFinish: Boolean,
    onFinish: () -> Unit,
) {
    val p = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    val fraction by animateFloatAsState(
        if (progress.total > 0) (progress.bought.toFloat() / progress.total).coerceIn(0f, 1f) else 0f,
        animationSpec = Motion.smooth(Motion.Slow), label = "fresh-cut-progress",
    )
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Покупки", Modifier.weight(1f), color = p.text, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
            FreshCutToolbarAction(R.drawable.ic_bell, "Уведомления", onNotifications)
            Box {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(16.dp)).background(p.field)
                    .clickable(enabled = themeEnabled, role = Role.Button) { expanded = true }
                    .semantics { contentDescription = "Действия со списком" },
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = null, tint = p.text, modifier = Modifier.size(24.dp))
                }
                AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppDropdownMenuItem(text = { Text("Переключить тему") },
                        onClick = { expanded = false; onToggleTheme() })
                    AppDropdownMenuItem(text = { Text("Завершить список") },
                        enabled = canFinish, onClick = { expanded = false; onFinish() })
                }
            }
        }
        if (progress.total > 0) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Куплено ${progress.bought} из ${progress.total}", Modifier.weight(1f),
                        color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${(fraction * 100).roundToInt()}%", color = p.textSecondary, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.field)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(2.dp)).background(p.headerFill))
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SummaryAmount(R.drawable.ic_list_check, "Всего", totals.total, p.text)
                    SummaryAmount(R.drawable.ic_tag, "Осталось", totals.remaining, p.text)
                }
            }
        }
    }
}

@Composable
internal fun SummaryAmount(icon: Int, label: String, amount: PartialAmount?, color: Color) {
    if (amount == null || amount.known == 0) return
    val value = formatAmount(amount)
    val detail = when {
        amount.unknown > 0 -> "$value, позиций без цены: ${amount.unknown}"
        else -> value
    }
    Row(Modifier.semantics { contentDescription = "$label, $detail" }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Text("$label · $value", Modifier.clearAndSetSemantics {}, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun formatAmount(amount: PartialAmount): String {
    val money = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU"))
        .format(java.math.BigDecimal.valueOf(amount.kopecks, 2))
    return if (amount.unknown > 0) "≥ $money" else money
}

@Composable
private fun ProgressRow(progress: Progress, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    // Единственная «длинная» анимация: полоса заметно, но спокойно подтягивается к новому значению.
    val fraction by animateFloatAsState(
        if (progress.total > 0) progress.bought.toFloat() / progress.total else 0f,
        animationSpec = Motion.smooth(Motion.Slow),
        label = "progress",
    )
    val description = stringResource(R.string.progress_description, progress.bought, progress.total)
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(
                progress.bought.toFloat(),
                0f..progress.total.coerceAtLeast(1).toFloat(),
            )
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(p.headerTrack),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(5.dp))
                    .background(p.headerFill),
            )
        }
        // Число «перелистывается»: при росте уезжает вверх, при убывании вниз.
        AnimatedContent(
            targetState = progress,
            transitionSpec = {
                val up = targetState.bought >= initialState.bought
                val sign = if (up) 1 else -1
                (slideInVertically(Motion.smooth()) { sign * it / 2 } + fadeIn(Motion.smooth()))
                    .togetherWith(slideOutVertically(Motion.smooth()) { -sign * it / 2 } + fadeOut(Motion.smooth(Motion.Quick)))
                    .using(SizeTransform(clip = false))
            },
            label = "progress-count",
        ) { shown ->
            Text(
                stringResource(R.string.progress_format, shown.bought, shown.total),
                color = p.onHeader,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

/** Переключатель «солнце — луна»: короткое нажатие меняет тему, долгое открывает настройки темы. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeToggle(dark: Boolean, enabled: Boolean, onToggle: () -> Unit, onOpenSettings: () -> Unit) {
    val p = LocalPalette.current
    val label = stringResource(R.string.theme_toggle)
    val thumbOffset by animateDpAsState(
        if (dark) 44.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "thumb",
    )
    Box(
        Modifier
            .size(width = 88.dp, height = 44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (dark) 0.14f else 0.2f))
            .combinedClickable(
                enabled = enabled,
                role = Role.Switch,
                onClickLabel = label,
                onLongClickLabel = stringResource(R.string.theme_open_settings),
                onLongClick = onOpenSettings,
                onClick = onToggle,
            )
            .semantics { stateDescription = label; contentDescription = label },
    ) {
        Box(
            Modifier
                .offset { IntOffset((4.dp + thumbOffset).roundToPx(), 4.dp.roundToPx()) }
                .size(36.dp)
                .clip(CircleShape)
                .background(if (dark) p.accent else Color.White),
        )
        Row(Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ToggleIcon(R.drawable.ic_sun, tint = if (dark) p.onHeader else p.accent)
            ToggleIcon(R.drawable.ic_moon, tint = if (dark) p.onAccent else p.onHeader)
        }
    }
}

@Composable
private fun ToggleIcon(drawable: Int, tint: Color) {
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Icon(painterResource(drawable), contentDescription = null, Modifier.size(20.dp), tint = tint)
    }
}

/** Какую долю ширины строки нужно протянуть влево, чтобы удаление сработало. Быстрого «щелчка» недостаточно. */
private const val SWIPE_REMOVE_FRACTION = 0.55f

@Composable
private fun ItemRow(
    row: ListRow,
    product: LentaProduct?,
    shopPrices: List<ProductShopPrice>,
    plannedStoreId: String?,
    ownEstimate: Long?,
    address: String,
    onQuantity: (Int) -> Unit,
    onWeight: () -> Unit,
    onBuy: () -> Unit,
    onUndo: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Обёртка свайпа есть всегда (просто выключается), чтобы строка не пересоздавалась и её цвет и значок
    // плавно перетекали из «в списке» в «куплено».
    SwipeToRemove(enabled = row.state == RowState.ACTIVE, onRemove = onRemove, onBuy = onBuy, modifier = modifier) {
        ItemRowContent(row, onBuy, onUndo, product, shopPrices, plannedStoreId, ownEstimate,
            address, onQuantity, onWeight, onDetails, onRemove)
    }
}

/**
 * Удаление свайпом влево. Чтобы не было случайных срабатываний:
 * - строка идёт за пальцем только влево и только при явном горизонтальном движении;
 * - удаляется, только если строку протянули дальше [SWIPE_REMOVE_FRACTION] ширины и отпустили;
 *   скорость жеста не учитывается, поэтому лёгкий «флик» ничего не удаляет;
 * - красный фон с надписью растёт по мере движения, а на пороге надпись меняется на «Отпустите»
 *   и телефон коротко вибрирует.
 *
 * Строк в списке много, и они создаются прямо во время прокрутки, поэтому в покое обёртка почти ничего не стоит:
 * смещение читается только в слоях измерения и рисования (без перекомпоновки), а красный фон существует,
 * лишь пока строку тянут.
 */
@Composable
private fun SwipeToRemove(
    enabled: Boolean,
    onRemove: () -> Unit,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val appear = remember { Animatable(1f) }
    // Строку удалили свайпом: она уехала за край, а на её месте проявляется «Удалено».
    LaunchedEffect(enabled) {
        if (!enabled && offset.value != 0f) {
            appear.snapTo(0f)
            offset.snapTo(0f)
            appear.animateTo(1f, Motion.smooth())
        }
    }
    val swiping by remember { derivedStateOf { offset.value != 0f } }
    val buyAction = androidx.compose.runtime.rememberUpdatedState(onBuy)
    val removeAction = androidx.compose.runtime.rememberUpdatedState(onRemove)
    val removeLabel = stringResource(R.string.swipe_delete)

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (enabled) {
                    Modifier.semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(); true }, CustomAccessibilityAction("Отметить покупку") { onBuy(); true }) }
                } else {
                    Modifier
                },
            ),
    ) {
        if (enabled && swiping) SwipeBackground(offset, Modifier.matchParentSize())
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                // Слой с прозрачностью нужен, только пока строка проявляется после удаления.
                .then(
                    if (appear.value < 1f) Modifier.graphicsLayer { alpha = appear.value } else Modifier,
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            scope.launch { offset.snapTo((offset.value + delta).coerceIn(-size.width.toFloat(), size.width.toFloat())) }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (offset.value <= -size.width * SWIPE_REMOVE_FRACTION) {
                                    // Строка уезжает за край и остаётся там, пока на её месте не появится «Удалено».
                                    offset.animateTo(-size.width.toFloat(), tween(150))
                                    removeAction.value()
                                } else if (offset.value >= size.width * SWIPE_REMOVE_FRACTION) {
                                    offset.snapTo(0f)
                                    buyAction.value()
                                } else {
                                    offset.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                    )
                },
        ) {
            content()
        }
    }
}

/** Красная подложка под сдвигаемой строкой: чем дальше протянули, тем она плотнее; на пороге — «Отпустите». */
@Composable
private fun SwipeBackground(offset: Animatable<Float, AnimationVector1D>, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var width by remember { mutableIntStateOf(0) }
    val threshold = width * SWIPE_REMOVE_FRACTION
    val progress = remember(threshold) {
        derivedStateOf { if (threshold > 0f) (kotlin.math.abs(offset.value) / threshold).coerceIn(0f, 1f) else 0f }
    }
    val buying by remember { derivedStateOf { offset.value > 0f } }
    val armed by remember(threshold) { derivedStateOf { progress.value >= 1f } }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(armed) {
        if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Box(
        modifier
            .onSizeChanged { width = it.width }
            .drawBehind {
                drawRoundRect(
                    color = (if (buying) p.actionFill else p.danger).copy(alpha = 0.3f + 0.7f * progress.value),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            },
    ) {
        Row(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = progress.value }
                .padding(horizontal = 22.dp),
            horizontalArrangement = if (buying) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (buying) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(24.dp), tint = p.actionInk)
                Spacer(Modifier.width(10.dp))
                Text(if (armed) "Отпустите" else "Куплено", color = p.actionInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text(stringResource(if (armed) R.string.swipe_release else R.string.swipe_delete),
                    color = p.onDanger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Icon(painterResource(R.drawable.ic_trash), null, Modifier.size(24.dp), tint = p.onDanger)
            }
        }
    }
}

private val RowShape = RoundedCornerShape(16.dp)

/**
 * Строка товара. Все её переходы («в списке» → «куплено»/«удалено») ведёт одно число [done] от 0 до 1:
 * фон и цвет текста. Раньше на каждую строку приходилось по шесть отдельных
 * анимаций и два контейнера переходов — они создавались при прокрутке и заметно её тормозили. Теперь строка в покое
 * состоит из обычных элементов, а то, что нужно только во время перехода, не создаётся вовсе.
 */
@Composable
private fun ItemRowContent(
    row: ListRow,
    onBuy: () -> Unit,
    onUndo: () -> Unit,
    product: LentaProduct?,
    shopPrices: List<ProductShopPrice>,
    plannedStoreId: String?,
    ownEstimate: Long?,
    address: String,
    onQuantity: (Int) -> Unit,
    onWeight: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val state = row.state
    val finished = state != RowState.ACTIVE
    val done by animateFloatAsState(if (finished) 1f else 0f, Motion.smooth(), label = "row-done")
    var actionsExpanded by remember(row.item.id) { mutableStateOf(false) }
    val weight = row.item.weightGrams
    val buyLabel = stringResource(R.string.buy_action)
    val shown = product?.takeIf {
        address.isNotBlank() && it.address == address && it.channel == "locc" &&
            it.priceType == "card" && com.mok.buylist.data.parsePriceKopecks(it.price) != null
    }
    val lowest = displayShopPrice(if (row.item.productId > 0) "lenta:${row.item.productId}" else "custom:${row.item.customProductId}", shopPrices, plannedStoreId)
    val ownPrice = lowest?.priceKopecks ?: ownEstimate
    val subtitle = when {
        ownPrice != null -> {
            val price = java.math.BigDecimal.valueOf(ownPrice, 2).stripTrailingZeros().toPlainString() + " ₽"
            if (lowest != null) "${lowest.storeName} · $price" else price
        }
        row.item.customProductId.isNotBlank() && shopPrices.isNotEmpty() ->
            shopPrices.take(2).joinToString(" · ") { it.storeName }
        else -> null
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RowShape)
            .background(lerp(p.card, p.boughtBg, done))
            .semantics {
                stateDescription = when (state) {
                    RowState.ACTIVE -> "Не куплено"
                    RowState.BOUGHT -> "Куплено"
                    RowState.REMOVED -> "Удалено"
                }
                customActions = if (finished) listOf(
                    CustomAccessibilityAction("Информация о товаре") { onDetails(); true },
                    CustomAccessibilityAction("Вернуть товар") { onUndo(); true },
                ) else listOf(
                    CustomAccessibilityAction("Информация о товаре") { onDetails(); true },
                    CustomAccessibilityAction("Изменить вес") { onWeight(); true },
                    CustomAccessibilityAction("Удалить товар") { onRemove(); true },
                )
            }
            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp, 48.dp)
                .clickable(enabled = !finished, role = Role.Button, onClickLabel = buyLabel, onClick = onBuy),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (finished) p.actionFill else p.card)
                    .border(1.dp, if (finished) p.actionFill else p.ring, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (state == RowState.BOUGHT) Icon(painterResource(R.drawable.ic_check),
                    contentDescription = null, tint = p.actionInk, modifier = Modifier.size(16.dp))
                if (state == RowState.REMOVED) Text("−", color = p.actionInk, fontSize = 16.sp)
            }
        }
        Column(
            Modifier.weight(1f).padding(start = 4.dp, end = 6.dp)
                .pointerInput(row.item) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val held = withTimeoutOrNull(3001L) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        if (!change.pressed || change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop)
                            return@withTimeoutOrNull false
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                } == null
                if (held) {
                    onDetails()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
                .clickable(role = Role.Button, onClickLabel = "Информация о товаре", onClick = onDetails),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(row.item.name, color = if (finished) p.textSecondary else p.text,
                fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                textDecoration = if (finished) TextDecoration.LineThrough else null,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, color = p.textSecondary, fontSize = 12.sp, lineHeight = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (finished) {
            androidx.compose.material3.TextButton(onClick = onUndo) {
                Text(stringResource(R.string.undo), color = p.accent, fontSize = 13.sp)
            }
        } else {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(p.field),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (weight == null) {
                    FreshCutQuantityButton("−", "Уменьшить количество", row.item.quantity > 1) {
                        onQuantity(row.item.quantity - 1)
                    }
                }
                Box {
                    val amount = weight?.let {
                        "${java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()} кг"
                    } ?: row.item.quantity.toString()
                    Box(
                        Modifier.height(48.dp).clickable(role = Role.Button) { actionsExpanded = true }
                            .semantics { contentDescription = "Количество: $amount. Действия с товаром" }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(amount, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    AppDropdownMenu(
                        expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false },
                    ) {
                        AppDropdownMenuItem(text = { Text("Изменить вес") },
                            onClick = { actionsExpanded = false; onWeight() })
                        AppDropdownMenuItem(text = { Text("Информация о товаре") },
                            onClick = { actionsExpanded = false; onDetails() })
                        AppDropdownMenuItem(text = { Text("Удалить") },
                            onClick = { actionsExpanded = false; onRemove() })
                    }
                }
                if (weight == null) {
                    FreshCutQuantityButton("+", "Увеличить количество", row.item.quantity < 999) {
                        onQuantity(row.item.quantity + 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun FreshCutQuantityButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(40.dp, 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = if (enabled) p.text else p.textSecondary.copy(alpha = 0.55f),
            fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.padding(bottom = 12.dp).size(96.dp).clip(RoundedCornerShape(32.dp)).background(p.field),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_list_check), contentDescription = null, Modifier.size(24.dp), tint = p.text)
        }
        Text(
            stringResource(R.string.empty_title),
            color = p.text,
            fontSize = 23.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text("Все покупки будут здесь.", color = p.textSecondary, fontSize = 14.sp)
    }
}

/** Кнопка внутри общей панели: отдельная область нажатия и подпись для TalkBack. */
@Composable
private fun FreshCutTab(
    icon: Int,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val color = if (selected) p.text else p.textSecondary
    Column(
        modifier.height(56.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(width = 24.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
            .background(if (selected) p.text else Color.Transparent))
        Spacer(Modifier.height(4.dp))
        Icon(painterResource(icon), contentDescription = null, Modifier.size(24.dp), tint = color)
        Text(label, color = color, fontSize = 10.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val label = stringResource(R.string.add_item_description)
    // При нажатии кнопка чуть «продавливается» — единственная реакция, лишнего не добавляем.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "fab-press",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(68.dp)
            .shadow(10.dp, CircleShape, ambientColor = p.accent, spotColor = p.accent)
            .clip(CircleShape)
            .background(p.accent)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = label, Modifier.size(32.dp), tint = p.onAccent)
    }
}
