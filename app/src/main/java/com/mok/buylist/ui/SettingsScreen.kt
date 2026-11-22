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
    val activeTab = tab.coerceIn(0, 1)
    var syncActive by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(0) }
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var selectedProduct by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    if (selectedProduct != null) {
        ProductDetailsScreen(selectedProduct!!, remember(archive, bought) { expensePurchases(archive, bought) },
            users, viewModel, onBack = { selectedProduct = null })
        return
    }
    Column(Modifier.fillMaxSize().background(p.background)) {
        if (!syncActive) SubScreenHeader("Настройки", onBack)
        TabRow(selectedTabIndex = activeTab, containerColor = p.background, contentColor = p.accent) {
            listOf("Товары и магазины", "Настройки").forEachIndexed { index, title ->
                Tab(selected = activeTab == index, onClick = { onTab(index) }, enabled = !syncActive, text = { Text(title) })
            }
        }
        if (activeTab == 0) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                listOf("Товары", "Магазины").forEachIndexed { index, label ->
                    androidx.compose.material3.FilterChip(
                        selected = section == index,
                        onClick = { section = index },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (section == 0) stateHolder.SaveableStateProvider("products") {
                ProductsScreen(onBack, onAdd, onAddressSettings = { onTab(1) }, viewModel = viewModel,
                    onProductClick = { selectedProduct = it })
            } else stateHolder.SaveableStateProvider("stores") {
                StoresScreen(viewModel, archive, bought, onProductClick = { selectedProduct = it })
            }
        }
        else SyncScreen(profile, onRename, addressRequest, onActiveChange = { syncActive = it })
    }
}
