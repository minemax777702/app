package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
) {
    val p = LocalPalette.current
    var syncActive by remember { mutableStateOf(false) }
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var selectedProduct by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    if (selectedProduct != null) {
        ProductDetailsScreen(selectedProduct!!, remember(archive, bought) { expensePurchases(archive, bought) },
            users, viewModel, onBack = { selectedProduct = null })
        return
    }
    Column(Modifier.fillMaxSize().background(p.background)) {
        if (!syncActive) SubScreenHeader("Настройки", onBack)
        TabRow(selectedTabIndex = tab, containerColor = p.background, contentColor = p.accent) {
            listOf("База продуктов", "Магазины", "Настройки").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { onTab(index) }, enabled = !syncActive, text = { Text(title) })
            }
        }
        if (tab == 0) stateHolder.SaveableStateProvider("products") {
            ProductsScreen(onBack, onAdd, onAddressSettings = { onTab(2) }, viewModel = viewModel,
                onProductClick = { selectedProduct = it })
        }
        else if (tab == 1) stateHolder.SaveableStateProvider("stores") {
            StoresScreen(viewModel, archive, bought, onProductClick = { selectedProduct = it })
        }
        else SyncScreen(profile, onRename, addressRequest, onActiveChange = { syncActive = it })
    }
}
