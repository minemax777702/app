package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mok.buylist.data.Profile
import com.mok.buylist.data.LentaProduct
import com.mok.buylist.ui.theme.LocalPalette

@Composable
fun SettingsScreen(
    tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit,
    profile: Profile?, onRename: () -> Unit, addressRequest: Int,
    onAdd: (LentaProduct) -> Unit,
    viewModel: ShoppingViewModel,
    archive: List<com.mok.buylist.data.ArchivedList>,
    bought: List<com.mok.buylist.data.ShoppingItem>,
    users: List<com.mok.buylist.data.AppUser>,
    onOpenTheme: () -> Unit = {},
    navigation: @Composable () -> Unit = {},
    listPicker: @Composable () -> Unit = {},
) {
    val p = LocalPalette.current
    val activeTab = tab.coerceIn(0, 1)
    var syncActive by remember { mutableStateOf(false) }
    var storeDetail by remember { mutableStateOf(false) }
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var selectedProduct by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    if (selectedProduct != null) {
        ProductDetailsScreen(selectedProduct!!, remember(archive, bought) { expensePurchases(archive, bought) },
            users, viewModel, onBack = { selectedProduct = null }, navigation = navigation)
        return
    }
    Column(Modifier.fillMaxSize().background(p.background)) {
        if (activeTab == 0) {
            if (!storeDetail || section == 0) {
                FreshCutRootHeader("ВАША БАЗА", if (section == 0) "Товары" else "Магазины",
                    if (section == 0) "Все товары и магазины" else "Цены и покупки по магазинам")
                listPicker()
                FreshCutSegments(listOf("Товары", "Магазины"), section, { section = it; storeDetail = false },
                    Modifier.padding(horizontal = 20.dp))
            }
            Box(Modifier.weight(1f)) {
                if (section == 0) stateHolder.SaveableStateProvider("products") {
                    ProductsScreen(onBack, onAdd, onAddressSettings = { onTab(1) }, viewModel = viewModel,
                        onProductClick = { selectedProduct = it })
                } else stateHolder.SaveableStateProvider("stores") {
                    StoresScreen(viewModel, archive, bought, onProductClick = { selectedProduct = it }, onDetailChange = { storeDetail = it })
                }
            }
            navigation()
        } else {
            Box(Modifier.weight(1f)) {
                SyncScreen(profile, onRename, addressRequest, onActiveChange = { syncActive = it },
                    onOpenTheme = onOpenTheme)
            }
            navigation()
        }
    }
}
