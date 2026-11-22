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

    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(Modifier.fillMaxSize()) {
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
                onOpenThemeSettings = { showTheme = true },
            )
            // Пустое состояние и список сменяют друг друга плавно; пока список грузится, null — ничего не рисуем.
            Crossfade(
                targetState = rows?.isEmpty(),
                modifier = Modifier.weight(1f),
                animationSpec = Motion.smooth(),
                label = "list-or-empty",
            ) { empty ->
                val list = rows
                when {
                    empty == null || list == null -> Unit
                    empty -> EmptyState(Modifier.fillMaxSize())
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            // Место под кнопку «+», чтобы последний товар можно было прокрутить выше неё.
                            bottom = 108.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.item.id }, contentType = { "row" }) { row ->
                            ItemRow(
                                row = row,
                                product = lentaById[row.item.productId],
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
                                // Новые строки проявляются, ушедшие гаснут, остальные плавно сдвигаются на их место.
                                modifier = Modifier.animateItem(
                                    fadeInSpec = Motion.smooth(),
                                    placementSpec = Motion.settle(),
                                    fadeOutSpec = Motion.smooth(Motion.Quick),
                                ),
                            )
                        }
                    }
                }
            }
        }

        // Все действия находятся в одном ряду; между группой и добавлением остаётся свободное место.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.shadow(6.dp, CircleShape).clip(CircleShape).background(p.card),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomAction(R.drawable.ic_settings, "Настройки") { showSettings = true }
                BottomAction(R.drawable.ic_camera, "Отметить покупку по штрихкоду") { scanPurchase = true }
                AnimatedVisibility(
                    visible = archive.isNotEmpty(),
                    enter = fadeIn(Motion.smooth()) + scaleIn(Motion.smooth(), initialScale = 0.8f),
                    exit = fadeOut(Motion.smooth(Motion.Quick)) + scaleOut(Motion.smooth(Motion.Quick), targetScale = 0.8f),
                ) {
                    BottomAction(R.drawable.ic_history, stringResource(R.string.archive_open)) { showArchive = true }
                }
                AnimatedVisibility(
                    visible = canFinish,
                    enter = fadeIn(Motion.smooth()) + scaleIn(Motion.smooth(), initialScale = 0.8f),
                    exit = fadeOut(Motion.smooth(Motion.Quick)) + scaleOut(Motion.smooth(Motion.Quick), targetScale = 0.8f),
                ) {
                    val label = stringResource(R.string.finish_list)
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .clickable(enabled = finishEnabled, role = Role.Button, onClickLabel = label, onClick = viewModel::finishTrip),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_check), contentDescription = label,
                            modifier = Modifier.size(24.dp), tint = (if (p.isDark) p.error else p.danger).copy(alpha = if (finishEnabled) 1f else 0.35f),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            AddButton(onClick = { showAdd = true })
        }

        AnimatedVisibility(
            visible = showArchive,
            enter = slideInHorizontally(Motion.smooth()) { it / 6 } + fadeIn(Motion.smooth()),
            exit = slideOutHorizontally(Motion.smooth(Motion.Quick)) { it / 6 } + fadeOut(Motion.smooth(Motion.Quick)),
        ) {
            BackHandler { showArchive = false }
            ArchiveScreen(
                archive = archive,
                onBack = { showArchive = false },
                onOpenStats = { showStats = true },
                onRepeat = { names, quantities, events -> viewModel.repeatArchived(names, quantities, events) { showArchive = false } },
                onDelete = viewModel::deleteArchived,
                users = users,
                meId = profile?.id.orEmpty(),
                viewModel = viewModel,
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(Motion.smooth()) { it / 6 } + fadeIn(Motion.smooth()),
            exit = slideOutHorizontally(Motion.smooth(Motion.Quick)) { it / 6 } + fadeOut(Motion.smooth(Motion.Quick)),
        ) {
            BackHandler { showSettings = false }
            SettingsScreen(
                tab = settingsTab,
                onTab = { settingsTab = it },
                onBack = { showSettings = false },
                profile = profile,
                onRename = { renaming = true },
                addressRequest = addressRequest,
                onAdd = { product -> viewModel.addProduct(product) { added -> activity?.notifyUser(if (added) "Добавлено в список" else "Уже в списке") } },
                viewModel = viewModel,
                archive = archive,
                bought = bought,
                users = users,
            )
        }

        AnimatedVisibility(
            visible = showStats,
            enter = slideInHorizontally(Motion.smooth()) { it / 6 } + fadeIn(Motion.smooth()),
            exit = slideOutHorizontally(Motion.smooth(Motion.Quick)) { it / 6 } + fadeOut(Motion.smooth(Motion.Quick)),
        ) {
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
            onAddressSettings = { showAdd = false; settingsTab = 2; addressRequest++; showSettings = true },
        )
    }
    pendingPurchase?.let { item ->
        val ownProduct = customProducts.firstOrNull { it.id == item.customProductId }
        val suggestedPrice = ownProduct?.estimatedPriceKopecks
        val isWeighted = ownProduct?.isWeighted == true || item.weightGrams != null
        var price by remember(item.id) {
            mutableStateOf(suggestedPrice?.let {
                java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString()
            }.orEmpty())
        }
        var selectedStore by remember(item.id) { mutableStateOf<com.mok.buylist.data.ShoppingStore?>(null) }
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
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.name, color = p.text, fontWeight = FontWeight.SemiBold)
                    if (isWeighted) SheetTextField(
                        weightText, { weightText = it }, "Вес, кг",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    SheetTextField(price, { price = it; priceRevision++ },
                        if (isWeighted) "Цена за кг, ₽" else "Цена, ₽",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    StoreSelectField(stores, selectedStore, onSelected = { store ->
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
                    viewModel.buy(item, priceValue, selectedStore?.name.orEmpty(), weightGrams)
                    pendingPurchase = null
                }) { Text("Куплено") }
            },
        )
    }
    weightItem?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.weightGrams?.let {
            java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()
        }.orEmpty()) }
        val grams = com.mok.buylist.data.parseWeightGrams(text)
        AppSheet(onDismissRequest = { weightItem = null }, containerColor = p.sheet,
            title = { Text(item.name, color = p.text, fontWeight = FontWeight.Bold) },
            text = { SheetTextField(text, { text = it }, "Вес, кг", keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)) },
            confirmButton = { SheetActionButton(enabled = grams != null, onClick = { viewModel.setWeight(item, grams); weightItem = null }) { Text("Сохранить") } },
            showSecondaryAction = item.weightGrams != null,
            dismissButton = { SheetActionButton(onClick = { viewModel.setWeight(item, null); weightItem = null }) { Text("Штуки") } })
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
            title = { SheetHeader(product.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (product.isWeighted) SheetTextField(
                        weightText, { weightText = it }, "Вес, кг",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    SheetTextField(price, { price = it; priceRevision++ },
                        if (product.isWeighted) "Цена за кг, ₽" else "Цена, ₽",
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
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { scanLookup = null }, containerColor = p.sheet,
            dragHandle = { SheetDragHandle { scanLookup = null } }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
                NoticeInSheet()
                SheetHeader("Выберите товар")
                ProductSearch(lookupQuery, { lookupQuery = it })
                LentaSuggestions(lookupQuery, Modifier.weight(1f), onSettings = {
                    scanLookup = null; settingsTab = 2; addressRequest++; showSettings = true
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
            onRefresh = { viewModel.refreshPrice(selected.item.productId) }) { detailsRow = null }
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

@Composable
private fun Header(
    progress: Progress,
    totals: com.mok.buylist.data.ShoppingTotals,
    themeEnabled: Boolean,
    onToggleTheme: () -> Unit,
    onOpenThemeSettings: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(p.header)
            .drawBehind {
                // Мягкий блик в углу, как на иконке приложения.
                drawCircle(
                    color = Color.White.copy(alpha = if (p.isDark) 0.07f else 0.1f),
                    radius = 110.dp.toPx(),
                    center = Offset(size.width - 50.dp.toPx(), 20.dp.toPx()),
                )
            }
            .statusBarsPadding()
            .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                stringResource(R.string.app_name),
                Modifier.weight(1f).padding(top = 10.dp, end = 8.dp),
                color = p.onHeader,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
            )
            ThemeToggle(dark = p.isDark, enabled = themeEnabled, onToggle = onToggleTheme, onOpenSettings = onOpenThemeSettings)
        }
        // Полоса появляется, когда в списке что-то есть, и уезжает, когда он опустел. Пока она уходит, показываем
        // последние цифры, а не «0 из 0».
        run {
            Column {
                Spacer(Modifier.height(10.dp))
                ProgressRow(progress, Modifier.padding(end = 8.dp))
                Spacer(Modifier.height(8.dp))
                if (progress.total > 0) androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SummaryAmount(R.drawable.ic_list_check, "Всего", totals.total, p.onHeader)
                    SummaryAmount(R.drawable.ic_tag, "Осталось", totals.remaining, p.onHeader)
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
        Text(value, Modifier.clearAndSetSemantics {}, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
    SwipeToRemove(enabled = row.state == RowState.ACTIVE, onRemove = onRemove, modifier = modifier) {
        ItemRowContent(row, onBuy, onUndo, product, address, onQuantity, onWeight, onDetails, onRemove)
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
    val swiping by remember { derivedStateOf { offset.value < 0f } }
    val removeLabel = stringResource(R.string.swipe_delete)

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .then(
                if (enabled) {
                    Modifier.semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(); true }) }
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
                            scope.launch { offset.snapTo((offset.value + delta).coerceIn(-size.width.toFloat(), 0f)) }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (offset.value <= -size.width * SWIPE_REMOVE_FRACTION) {
                                    // Строка уезжает за край и остаётся там, пока на её месте не появится «Удалено».
                                    offset.animateTo(-size.width.toFloat(), tween(150))
                                    onRemove()
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
        derivedStateOf { if (threshold > 0f) (-offset.value / threshold).coerceIn(0f, 1f) else 0f }
    }
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
                    color = p.danger.copy(alpha = 0.3f + 0.7f * progress.value),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            },
    ) {
        Row(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = progress.value }
                .padding(end = 22.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (armed) R.string.swipe_release else R.string.swipe_delete),
                color = p.onDanger,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                painterResource(R.drawable.ic_trash),
                contentDescription = null,
                Modifier.size(if (armed) 28.dp else 24.dp),
                tint = p.onDanger,
            )
        }
    }
}

private val RowShape = RoundedCornerShape(20.dp)

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
    val done = animateFloatAsState(if (finished) 1f else 0f, Motion.smooth(), label = "row-done")
    // Пока строка возвращается в список, значок и цвет «куплено»/«удалено» ещё нужны.
    var lastFinished by remember { mutableStateOf(if (finished) state else RowState.BOUGHT) }
    if (finished) lastFinished = state
    val removed = lastFinished == RowState.REMOVED
    val doneColor = if (removed) p.field else p.boughtBg
    val buyLabel = stringResource(R.string.buy_action)
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(RowShape)
            .drawBehind { drawRect(lerp(p.card, doneColor, done.value)) }
            .then(
                if (finished) Modifier
                else Modifier.clickable(role = Role.Button, onClickLabel = buyLabel, onClick = onBuy),
            )
            .semantics { stateDescription = when (state) { RowState.ACTIVE -> "Не куплено"; RowState.BOUGHT -> "Куплено"; RowState.REMOVED -> "Удалено" } }
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().semantics {
            customActions = listOf(CustomAccessibilityAction("Информация о товаре") { onDetails(); true })
        }.pointerInput(row.item) {
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
        }, verticalAlignment = Alignment.CenterVertically) {
        LentaImage(product?.image.orEmpty(), Modifier.size(48.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
            row.item.name,
            Modifier.fillMaxWidth(),
            color = lerp(p.text, p.textSecondary, done.value),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textDecoration = if (finished) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val shown = product?.takeIf { address.isNotBlank() && it.address == address && it.channel == "locc" && it.priceType == "card" && com.mok.buylist.data.parsePriceKopecks(it.price) != null }
        if (shown != null) Text("${shown.price} ₽", color = p.textSecondary, fontSize = 12.sp)
        }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (!finished) {
            androidx.compose.material3.IconButton(onClick = onDetails, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = "Информация о товаре", tint = p.iconMuted)
            }
            androidx.compose.material3.IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_trash), contentDescription = "Удалить", tint = p.iconMuted)
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onWeight) {
                Text(row.item.weightGrams?.let { "${java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()} кг" } ?: "Вес", maxLines = 1)
            }
            if (row.item.weightGrams == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = "Уменьшить количество" }.clickable(enabled = row.item.quantity > 1, role = Role.Button) { onQuantity(row.item.quantity - 1) }, contentAlignment = Alignment.Center) {
                    Text("−", color = p.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text(row.item.quantity.toString(), Modifier.semantics { contentDescription = "Количество: ${row.item.quantity}" }, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = "Увеличить количество" }.clickable(enabled = row.item.quantity < 999, role = Role.Button) { onQuantity(row.item.quantity + 1) }, contentAlignment = Alignment.Center) {
                    Text("+", color = p.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            }
        }
        if (done.value > 0f) {
            Box(
                Modifier
                    .graphicsLayer {
                        val scale = 0.9f + 0.1f * done.value
                        scaleX = scale
                        scaleY = scale
                        alpha = done.value
                    }
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.card)
                    .clickable(enabled = finished, role = Role.Button, onClick = onUndo)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.undo),
                    color = p.chipText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        }
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
            Modifier.padding(bottom = 16.dp).size(132.dp).clip(CircleShape).background(p.boughtBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_empty_list), contentDescription = null, Modifier.size(76.dp), tint = p.accent)
        }
        Text(
            stringResource(R.string.empty_title),
            color = p.text,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** Кнопка внутри общей панели: отдельная область нажатия и подпись для TalkBack. */
@Composable
private fun BottomAction(icon: Int, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, Modifier.size(24.dp), tint = p.accent)
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
