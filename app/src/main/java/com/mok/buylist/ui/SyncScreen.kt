package com.mok.buylist.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.mok.buylist.themeSettings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.R
import com.mok.buylist.lentaCatalog
import com.mok.buylist.data.Profile
import com.mok.buylist.repository
import com.mok.buylist.sync.QrMatrix
import com.mok.buylist.sync.SnapshotSection
import com.mok.buylist.data.LocalBackup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mok.buylist.ui.SyncController.Notice
import com.mok.buylist.ui.theme.LocalPalette
import java.time.LocalDate
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SyncScreen(profile: Profile?, onRename: () -> Unit, addressRequest: Int, onActiveChange: (Boolean) -> Unit, onOpenTheme: () -> Unit = {}) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as com.mok.buylist.ShoppingApp
    val workspace by app.cloud.workspace.collectAsStateWithLifecycle()
    val signedIn by app.cloud.signedIn.collectAsStateWithLifecycle()
    val directory by app.cloud.directory.collectAsStateWithLifecycle()
    val me = directory.me
    val isAdmin = signedIn && me?.admin == true && me.active
    val controller = remember { SyncController(app.localRepository, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? MainActivity
    var showAddress by rememberSaveable { mutableStateOf(false) }
    val lentaSelection by app.lentaAddressSettings.current.collectAsStateWithLifecycle()
    val address = lentaSelection.address
    val scrollState = rememberScrollState()
    var page by rememberSaveable { mutableStateOf("profile") }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    val installedVersion = remember { com.mok.buylist.update.AppUpdater.installedVersion(context) }
    val themeMode by context.themeSettings.app.collectAsStateWithLifecycle()
    LaunchedEffect(page, isAdmin) {
        if (page == "sources") page = "profile"
        if (page == "admin" && !isAdmin) page = "profile"
        onActiveChange(page != "profile"); scrollState.scrollTo(0)
    }
    BackHandler(enabled = page != "profile") { page = "profile" }
    LaunchedEffect(addressRequest) { if (addressRequest > 0) scrollState.scrollTo(0) }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        val message = when (notice) {
            Notice.BACKUP_SAVED -> R.string.notice_backup_saved
            Notice.BACKUP_FAILED -> R.string.notice_backup_failed
            Notice.RESTORED -> R.string.notice_restored
            Notice.RESTORE_FAILED -> R.string.notice_restore_failed
            Notice.UNDONE -> R.string.notice_undone
            Notice.UNDO_FAILED -> R.string.notice_undo_failed
            Notice.LOCAL_BACKUP_FAILED -> R.string.notice_local_backup_failed
        }
        activity?.notifyUser(context.getString(message))
        controller.dismissNotice()
    }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val bytes = controller.backupBytes()
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("no output stream")
                    stream.use { it.write(bytes) }
                }
            }.isSuccess
            controller.onBackupWritten(ok)
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            }.getOrNull()
            controller.onBackupRead(bytes)
        }
    }
    Column(Modifier.fillMaxSize().background(p.background)) {
        when (page) {
            "cloud" -> SubScreenHeader("Личный профиль", { page = "profile" })
            "admin" -> SubScreenHeader("Администрирование", { page = "profile" })
            else -> FreshCutRootHeader("НАСТРОЙКИ", "Профиль", "Ваши данные и предпочтения")
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                "cloud" -> SupabasePanel()
                "admin" -> if (isAdmin) SupabasePanel(adminOnly = true)
                else -> {
                    if (signedIn) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.card)
                        .clickable(role = Role.Button, onClick = { page = "cloud" }).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(p.field),
                            contentAlignment = Alignment.Center) {
                            Text((me?.name ?: profile?.name ?: "П").take(1).uppercase(), color = p.text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(me?.name ?: "Личный профиль", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (me != null) "Личный профиль" else "Аккаунт", color = p.textSecondary, fontSize = 13.sp)
                        }
                        Icon(painterResource(R.drawable.ic_chevron_down), "Аккаунт", Modifier.size(24.dp).rotate(-90f), tint = p.text)
                    }
                    if (!signedIn) ProfileMenuRow(R.drawable.ic_user_round, "Личный профиль", "Войти или зарегистрироваться", { page = "cloud" })
                    FreshCutSectionTitle("Приложение", Modifier.padding(top = 8.dp))
                    ProfileMenuRow(R.drawable.ic_sun, "Оформление", when (themeMode) {
                        com.mok.buylist.ui.theme.ThemeMode.AUTO -> "Системная тема"
                        com.mok.buylist.ui.theme.ThemeMode.LIGHT -> "Светлая тема"
                        com.mok.buylist.ui.theme.ThemeMode.DARK -> "Тёмная тема"
                    }, onOpenTheme)
                    ProfileMenuRow(R.drawable.ic_store, "Магазин Ленты",
                        if (address.isBlank()) "Адрес не выбран" else address, { showAddress = true })
                    ProfileMenuRow(R.drawable.ic_download_update, "Обновления приложения",
                        "Версия ${installedVersion.name}", { activity?.openUpdates() })
                    if (isAdmin || !signedIn) FreshCutSectionTitle("Данные", Modifier.padding(top = 8.dp))
                    if (isAdmin) ProfileMenuRow(R.drawable.ic_shield, "Администрирование", "", { page = "admin" })
                    if (!signedIn) ProfileMenuRow(R.drawable.ic_archive, "Резервная копия", "Сохранить или восстановить", { showBackup = true })
                }
            }
        }
    }
    if (showBackup) AppSheet(
        onDismissRequest = { showBackup = false }, containerColor = p.sheet,
        title = { SheetHeader("Резервная копия") }, minBodyHeight = 296.dp,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileMenuRow(R.drawable.ic_download_update, "Сохранить в файл", "Экспорт данных", {
                    showBackup = false
                    saveBackup.launch("spisok-pokupok-${LocalDate.now()}.buylist")
                })
                ProfileMenuRow(R.drawable.ic_upload_file, "Восстановить из файла", "Импорт данных", {
                    showBackup = false
                    openBackup.launch(arrayOf("*/*"))
                })
            }
        },
        confirmButton = {},
    )

    if (showAddress) LentaSettings { showAddress = false }
    state.pendingRestoreOwner?.let { owner ->
        AppSheet(
            onDismissRequest = controller::cancelRestore,
            containerColor = p.sheet,
            showSecondaryAction = true,
            title = { Text(stringResource(R.string.restore_owner_title, owner), color = p.text, fontWeight = FontWeight.ExtraBold) },
            text = { Text(stringResource(R.string.restore_owner_text), color = p.textSecondary) },
            confirmButton = {
                SheetActionButton(onClick = { controller.confirmRestore(asMe = true) }) {
                    Text(stringResource(R.string.restore_owner_me), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                SheetActionButton(onClick = { controller.confirmRestore(asMe = false) }) {
                    Text(stringResource(R.string.restore_owner_other), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
        )
    }
}

@Composable
private fun SyncAction(icon: Int, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = p.iconMuted)
        Text(title, Modifier.weight(1f), color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("›", color = p.textSecondary, fontSize = 24.sp, lineHeight = 24.sp)
    }
}


@Composable
private fun ProfileMenuRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    FreshCutListRow(icon, title, subtitle, onClick = onClick)
}
