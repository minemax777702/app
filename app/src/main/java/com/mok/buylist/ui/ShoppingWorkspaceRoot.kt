package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mok.buylist.R
import com.mok.buylist.ShoppingApp
import com.mok.buylist.cloud.CloudSpaceSession
import com.mok.buylist.cloud.CloudSpaceKind
import com.mok.buylist.data.LentaCatalog
import com.mok.buylist.lentaCatalog
import com.mok.buylist.ui.theme.LocalPalette

internal val LocalShoppingWorkspace = staticCompositionLocalOf<CloudSpaceSession?> { null }
internal val LocalSelectShoppingWorkspace = staticCompositionLocalOf<(CloudSpaceSession?) -> Unit> { {} }

/** Explicit composition scope: never change application-global repository or widget data. */
@Composable
internal fun shoppingCatalog(): LentaCatalog =
    LocalShoppingWorkspace.current?.catalog ?: LocalContext.current.lentaCatalog

@Composable
internal fun ShoppingWorkspaceRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as ShoppingApp
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val p = LocalPalette.current
    val sessions by app.cloudSpaces.sessions.collectAsStateWithLifecycle()
    // Do not reopen a cloud space implicitly after process recreation.
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var selector by remember { mutableStateOf(false) }
    val requested = activity?.widgetWorkspaceRequest
    LaunchedEffect(requested, sessions) {
        if (requested == "local") { selectedKey = null; activity?.selectNoticeWorkspace(null); activity?.consumeWidgetWorkspaceRequest() }
        else if (requested != null && sessions.any { it.localKey == requested }) {
            selectedKey = requested; activity?.selectNoticeWorkspace(requested); activity?.consumeWidgetWorkspaceRequest()
        }
    }
    val selected = sessions.firstOrNull { it.localKey == selectedKey }
    val historical = if (selected != null) selected.historicalState.collectAsStateWithLifecycle().value else false
    val status = if (selected != null) selected.status.collectAsStateWithLifecycle().value else null
    val select: (CloudSpaceSession?) -> Unit = { session ->
        selectedKey = session?.localKey
        selector = false
        activity?.selectNoticeWorkspace(session?.localKey)
    }
    CompositionLocalProvider(LocalShoppingWorkspace provides selected, LocalSelectShoppingWorkspace provides select) {
        Column(Modifier.fillMaxSize().background(p.background)) {
            if (sessions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp)).background(p.card).clickable { selector = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(if (selected == null) R.drawable.ic_user_round else R.drawable.ic_cloud),
                        contentDescription = null, tint = p.text, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selected?.invitation?.label ?: "На телефоне", color = p.text, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (selected != null) Text(when {
                            historical -> "Прежняя версия"
                            !selected.grant.canWrite -> "Только просмотр"
                            status?.syncing == true -> "Синхронизация…"
                            status?.issue != null -> "Сохранено на телефоне"
                            (status?.lastSuccess ?: 0L) > 0 -> "Синхронизировано"
                            else -> "Ожидает синхронизации"
                        }, color = p.textSecondary, fontSize = 12.sp)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_down), "Выбрать список", tint = p.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().then(if (sessions.isNotEmpty()) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                key(selectedKey, historical) {
                    if (selected != null && (historical || !selected.grant.canWrite)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp)
                            .navigationBarsPadding()) {
                            CloudWorkspacePanel(selected)
                        }
                    } else {
                        // Separate lifetime: leaving a workspace cancels its UI jobs and sheets.
                        val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
                        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
                        val factory = remember(selected) {
                            object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                    (if (selected == null) ShoppingViewModel(app)
                                    else ShoppingViewModel(app, selected.repository, selected.catalog, selected.profile, selected.localKey)) as T
                            }
                        }
                        val model: ShoppingViewModel = viewModel(viewModelStoreOwner = owner, factory = factory)
                        ShoppingScreen(model)
                    }
                }
            }
        }
        if (selector) AppSheet(onDismissRequest = { selector = false }, containerColor = p.sheet,
            title = { SheetHeader("Списки покупок") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FreshCutListRow(R.drawable.ic_user_round, "На телефоне", "Локальные покупки", selected = selected == null,
                        onClick = { select(null) })
                    sessions.sortedWith(compareBy<CloudSpaceSession> { it.historical }.thenBy { it.invitation.label }).forEach { session ->
                        val old by session.historicalState.collectAsStateWithLifecycle()
                        FreshCutListRow(if (session.space.kind == CloudSpaceKind.PERSONAL) R.drawable.ic_user_round else R.drawable.ic_group,
                            session.invitation.label, when {
                                old -> "Прежняя версия"
                                !session.grant.canWrite -> "Только просмотр"
                                else -> "Облачный список"
                            }, selected = session.localKey == selectedKey, onClick = { select(session) })
                    }
                }
            }, confirmButton = {})
    }
}

@Composable
internal fun CloudWorkspaceProfile(session: CloudSpaceSession, addressRequest: Int, onOpenTheme: () -> Unit) {
    val p = LocalPalette.current
    val select = LocalSelectShoppingWorkspace.current
    val app = LocalContext.current.applicationContext as ShoppingApp
    val status by session.status.collectAsStateWithLifecycle()
    val address by session.catalog.address.collectAsStateWithLifecycle()
    val widgetKey by app.cloudSpaces.widgetKey.collectAsStateWithLifecycle()
    var showAddress by remember { mutableStateOf(false) }
    LaunchedEffect(addressRequest) { if (addressRequest > 0) showAddress = true }
    Column(Modifier.fillMaxSize().background(p.background)) {
        FreshCutRootHeader("НАСТРОЙКИ", "Профиль", session.invitation.label)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FreshCutListRow(R.drawable.ic_user_round, session.invitation.userName, "Участник списка", showChevron = false)
            FreshCutListRow(R.drawable.ic_cloud, "Синхронизация", when {
                status.syncing -> "Обновляем покупки…"
                status.issue != null -> "Сохранено на телефоне"
                status.lastSuccess > 0 -> "Синхронизировано"
                else -> "Ожидает синхронизации"
            }, onClick = { app.cloudSpaces.requestSync(session) })
            status.issue?.let { Text(it, color = p.textSecondary, fontSize = 13.sp) }
            FreshCutListRow(R.drawable.ic_store, "Магазин Ленты", address.ifBlank { "Адрес не выбран" }, onClick = { showAddress = true })
            FreshCutListRow(R.drawable.ic_list_check, "Список в виджете", if (widgetKey == session.localKey) "Этот список" else "Выбрать этот список",
                onClick = { app.cloudSpaces.selectWidget(if (widgetKey == session.localKey) null else session) })
            FreshCutListRow(R.drawable.ic_sun, "Оформление", onClick = onOpenTheme)
            FreshCutListRow(R.drawable.ic_archive, "На телефоне", "Локальный список и управление облаком", onClick = { select(null) })
        }
    }
    if (showAddress) LentaSettings { showAddress = false }
}
