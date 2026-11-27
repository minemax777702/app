package com.mok.buylist.ui

import android.content.Intent
import android.os.Build
import android.Manifest
import androidx.activity.compose.LocalActivity
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.R
import androidx.compose.ui.Alignment
import com.mok.buylist.update.AppRelease
import com.mok.buylist.update.AppUpdater
import com.mok.buylist.update.UpdateChannel
import com.mok.buylist.update.UpdateNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun AppUpdateSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val activity = LocalActivity.current as? MainActivity
    val scope = rememberCoroutineScope()
    val installed = remember { AppUpdater.installedVersion(context) }
    var channel by remember { mutableStateOf(AppUpdater.selectedChannel(context)) }
    var attempt by remember { mutableIntStateOf(0) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var changelogOpen by remember(channel) { mutableStateOf(false) }
    var historyPage by remember(channel) { mutableIntStateOf(1) }
    var history by remember(channel) { mutableStateOf(emptyList<com.mok.buylist.update.ReleaseNotes>()) }
    var historyMore by remember(channel) { mutableStateOf(true) }
    var historyLoading by remember(channel) { mutableStateOf(false) }
    var historyError by remember(channel) { mutableStateOf<String?>(null) }
    var expandedHistory by remember(channel) { mutableStateOf<Long?>(null) }
    fun loadHistory() {
        if (historyLoading || !historyMore) return
        historyLoading = true
        historyError = null
        val target = channel
        val page = historyPage
        scope.launch {
            try {
                val result = AppUpdater.releaseNotes(target, page)
                if (channel == target) {
                    history = (history + result.entries).distinctBy { it.version.code }.sortedByDescending { it.version.code }
                    historyMore = result.hasMore
                    historyPage = page + 1
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (channel == target) historyError = "Не удалось загрузить историю версий."
            } finally {
                if (channel == target) historyLoading = false
            }
        }
    }
    var notificationsEnabled by remember { mutableStateOf(UpdateNotifications.enabled(context)) }
    var notificationsAllowed by remember { mutableStateOf(UpdateNotifications.canPost(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = UpdateNotifications.canPost(context)
        if (granted) activity?.checkUpdatesOnEntry(force = true)
    }
    val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationsAllowed = UpdateNotifications.canPost(context)
        if (notificationsAllowed) activity?.checkUpdatesOnEntry(force = true)
    }

    fun changeNotifications(enabled: Boolean) {
        notificationsEnabled = enabled
        UpdateNotifications.setEnabled(context, enabled)
        if (enabled && !UpdateNotifications.canPost(context)) {
            if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                UpdateNotifications.markPermissionRequested(context)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        } else if (enabled) {
            activity?.checkUpdatesOnEntry(force = true)
        }
    }

    fun install(file: File) {
        val target = release ?: return
        if (installing) return
        installing = true
        scope.launch {
            try {
                AppUpdater.install(context, file, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ready = null
                error = "Не удалось открыть установщик."
            } finally {
                installing = false
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = ready
        if (file != null && context.packageManager.canRequestPackageInstalls()) install(file)
        else error = "Разрешите установку из этого источника, чтобы продолжить."
    }

    fun requestInstall(file: File) {
        if (context.packageManager.canRequestPackageInstalls()) install(file)
        else {
            try {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")))
            } catch (e: Exception) {
                error = "Откройте системные настройки и разрешите установку из этого источника."
            }
        }
    }

    LaunchedEffect(channel, attempt) {
        checking = true
        release = null
        ready = null
        error = null
        try {
            val latest = AppUpdater.latest(channel)
            ready = AppUpdater.cachedUpdate(context, latest)
            release = latest
            UpdateNotifications.confirmPosted(context, UpdateNotifications.report(context, channel, latest))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Не удалось проверить обновления."
        } finally {
            checking = false
        }
    }
    val update = release?.takeIf { it.version.isNewerThan(installed) }
    val busy = checking || downloading || installing
    val notes = release?.takeIf { it.version.code >= installed.code }
    fun updateApp() {
        if (busy) return
        val file = ready
        if (file != null) {
            error = null
            requestInstall(file)
        } else if (update != null) {
            downloading = true
            error = null
            progress = 0
            scope.launch {
                try {
                    val apk = AppUpdater.download(context, update) { progress = it }
                    ready = apk
                    requestInstall(apk)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "Не удалось загрузить обновление."
                } finally {
                    downloading = false
                }
            }
        }
    }

    AppSheet(
        onDismissRequest = onClose, containerColor = p.sheet,
        title = { SheetHeader("Обновления") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Текущая версия", color = p.textSecondary, fontSize = 12.sp)
                        Text(installed.name, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    }
                    FreshCutToolbarAction(R.drawable.ic_bell,
                        if (notificationsEnabled && notificationsAllowed) "Отключить уведомления" else "Включить уведомления",
                        {
                            if (notificationsEnabled && !notificationsAllowed) {
                                notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            } else changeNotifications(!notificationsEnabled)
                        }, primary = notificationsEnabled && notificationsAllowed)
                    FreshCutToolbarAction(R.drawable.ic_history,
                        if (historyOpen) "Скрыть историю версий" else "История версий", {
                            historyOpen = !historyOpen
                            if (historyOpen) {
                                changelogOpen = false
                                if (history.isEmpty()) loadHistory()
                            }
                        }, primary = historyOpen)
                }
                FreshCutSegments(UpdateChannel.entries.map { it.label }, channel.ordinal, { index ->
                    if (!busy && channel.ordinal != index) {
                        historyOpen = false
                        channel = UpdateChannel.entries[index]
                        AppUpdater.selectChannel(context, channel)
                    }
                })
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val status = when {
                                downloading -> "Загрузка · $progress%"
                                installing -> "Подготовка установки…"
                                checking -> "Проверка…"
                                ready != null -> "Готово к установке"
                                update != null -> "Доступно обновление"
                                error != null -> "Проверка не удалась"
                                release == null -> "Нет обновлений в канале"
                                release!!.version.code < installed.code -> "В канале более старая версия"
                                else -> "Последняя версия установлена"
                            }
                            Text(status, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            if (update != null || ready != null) {
                                Text(release?.version?.name.orEmpty(), color = p.textSecondary, fontSize = 13.sp)
                            }
                        }
                        if (notes != null) FreshCutToolbarAction(R.drawable.ic_info,
                            if (changelogOpen) "Скрыть изменения" else "Что нового", {
                                changelogOpen = !changelogOpen
                                if (changelogOpen) historyOpen = false
                            }, primary = changelogOpen)
                        FreshCutToolbarAction(R.drawable.ic_sync, "Проверить обновления",
                            { checking = true; attempt++ }, enabled = !busy)
                        if (update != null || ready != null) {
                            FreshCutToolbarAction(
                                if (ready != null) R.drawable.ic_package else R.drawable.ic_download_update,
                                if (ready != null) "Установить обновление" else "Скачать и установить обновление",
                                ::updateApp, primary = true, enabled = !busy)
                        }
                    }
                    if (downloading) LinearProgressIndicator(progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(), color = p.accent)
                    else if (checking || installing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = p.accent)
                    error?.let { Text(it, color = p.error, fontSize = 13.sp) }
                }
                if (notificationsEnabled && !notificationsAllowed)
                    Text("Уведомления отключены в Android", color = p.textSecondary, fontSize = 12.sp)
                if (changelogOpen && notes != null) {
                    FreshCutSectionTitle("Что нового")
                    ReleaseChangelog(notes.changelog)
                }
                if (historyOpen) {
                    FreshCutSectionTitle("История версий")
                    history.forEach { entry ->
                        FreshCutListRow(R.drawable.ic_history, entry.version.name,
                            entry.publishedAt.take(10) + if (entry.version.code == installed.code) " · Установлена" else "",
                            showChevron = false,
                            trailing = {
                                androidx.compose.material3.Icon(
                                    androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_down),
                                    if (expandedHistory == entry.version.code) "Свернуть" else "Развернуть",
                                    Modifier.size(20.dp).then(if (expandedHistory == entry.version.code)
                                        Modifier.rotate(180f) else Modifier), tint = p.textSecondary)
                            },
                            onClick = { expandedHistory = if (expandedHistory == entry.version.code) null else entry.version.code })
                        if (expandedHistory == entry.version.code) ReleaseChangelog(entry.changelog)
                    }
                    historyError?.let { Text(it, color = p.error, fontSize = 13.sp) }
                    if (historyLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = p.accent)
                    else if (historyMore) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        FreshCutToolbarAction(if (historyError != null) R.drawable.ic_sync else R.drawable.ic_chevron_down,
                            if (historyError != null) "Повторить" else "Показать ещё", { loadHistory() })
                    }
                    else if (history.isEmpty()) Text("История пока пуста", color = p.textSecondary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ReleaseChangelog(raw: String) {
    val p = LocalPalette.current
    val lines = remember(raw) { com.mok.buylist.update.releaseChangelogLines(raw) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (lines.isEmpty()) Text("Описание изменений отсутствует.", color = p.textSecondary, fontSize = 13.sp)
        else lines.forEach { line ->
            Text(line, color = p.text, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
