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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.ui.theme.LocalPalette
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
                error = e.message ?: "Не удалось открыть установщик."
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
            error = e.message ?: "Не удалось проверить обновления."
        } finally {
            checking = false
        }
    }
    val update = release?.takeIf { it.version.isNewerThan(installed) }
    AppSheet(
        onDismissRequest = onClose, containerColor = p.sheet,
        title = { SheetHeader("Обновления") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Установлена версия ${installed.name}", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Сборка ${installed.code}", color = p.textSecondary, fontSize = 13.sp)
                FreshCutSectionTitle("Канал обновлений")
                FreshCutSegments(UpdateChannel.entries.map { it.label }, channel.ordinal, { index ->
                    if (!downloading && !checking && !installing) {
                        historyOpen = false
                        channel = UpdateChannel.entries[index]
                        AppUpdater.selectChannel(context, channel)
                    }
                })
                Text(if (channel == UpdateChannel.BETA) "Новые функции и тестовые сборки."
                    else "Только стабильные релизы.", color = p.textSecondary, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Уведомления об обновлениях", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(if (!notificationsAllowed && notificationsEnabled) "Разрешите уведомления в Android."
                            else "Проверяем новые версии при входе.", color = p.textSecondary, fontSize = 12.sp)
                    }
                    Switch(checked = notificationsEnabled && notificationsAllowed,
                        onCheckedChange = ::changeNotifications,
                        colors = SwitchDefaults.colors(checkedTrackColor = p.actionFill, checkedThumbColor = p.actionInk))
                }
                if (notificationsEnabled && !notificationsAllowed) {
                    androidx.compose.material3.TextButton(onClick = {
                        notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("Настройки уведомлений Android", color = p.text) }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        downloading -> {
                            Text("Загрузка обновления", color = p.text, fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = p.accent)
                            Text("$progress%", color = p.textSecondary, fontSize = 13.sp)
                        }
                        checking -> {
                            Text("Проверяем GitHub…", color = p.text)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = p.accent)
                        }
                        installing -> {
                            Text("Проверяем APK…", color = p.text)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = p.accent)
                        }
                        ready != null -> {
                            Text("Обновление загружено", color = p.text, fontWeight = FontWeight.SemiBold)
                            Text(release?.version?.name.orEmpty(), color = p.textSecondary, fontSize = 13.sp)
                            Text("APK сохранён в кэше. Повторная загрузка не нужна.", color = p.textSecondary, fontSize = 13.sp)
                        }
                        update != null -> {
                            Text("Доступна версия ${update.version.name}", color = p.text, fontWeight = FontWeight.SemiBold)
                            Text("Сборка ${update.version.code} · ${(update.size + 1_048_575) / 1_048_576} МБ", color = p.textSecondary, fontSize = 13.sp)
                        }
                        error == null && release == null -> Text("В этом канале пока нет опубликованных сборок.", color = p.textSecondary)
                        error == null && release!!.version.code < installed.code -> Text("В этом канале более старая сборка. Понижение версии недоступно.", color = p.textSecondary)
                        error == null -> Text("Установлена актуальная версия.", color = p.text)
                    }
                    error?.let { Text(it, color = p.error, fontSize = 13.sp) }
                }
                release?.takeIf { it.version.code >= installed.code }?.let { current ->
                    FreshCutSectionTitle(if (current.version.code > installed.code) "Что нового" else "Изменения в этой версии")
                    ReleaseChangelog(current.changelog)
                }
                FreshCutSecondaryButton(if (historyOpen) "Скрыть историю версий" else "История версий", {
                    historyOpen = !historyOpen
                    if (historyOpen && history.isEmpty()) loadHistory()
                })
                if (historyOpen) {
                    history.forEach { entry ->
                        FreshCutListRow(com.mok.buylist.R.drawable.ic_history, entry.version.name,
                            entry.publishedAt.take(10) + if (entry.version.code == installed.code) " · Установлена" else "",
                            onClick = { expandedHistory = if (expandedHistory == entry.version.code) null else entry.version.code })
                        if (expandedHistory == entry.version.code) ReleaseChangelog(entry.changelog)
                    }
                    historyError?.let { Text(it, color = p.error, fontSize = 13.sp) }
                    if (historyLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = p.accent)
                    else if (historyMore) FreshCutSecondaryButton(if (historyError != null) "Повторить" else "Показать ещё", { loadHistory() })
                    else if (history.isEmpty()) Text("История пока пуста.", color = p.textSecondary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            SheetActionButton(enabled = !checking && !downloading && !installing, onClick = {
                val file = ready
                when {
                    file != null -> { error = null; requestInstall(file) }
                    update != null -> {
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
                            } catch (e: Exception) {
                                error = e.message ?: "Не удалось загрузить обновление."
                            } finally { downloading = false }
                        }
                    }
                    else -> { checking = true; attempt++ }
                }
            }) {
                Text(when {
                    downloading -> "Загрузка…"
                    installing -> "Проверка APK…"
                    checking -> "Проверка…"
                    ready != null -> "Установить"
                    update != null -> "Обновить"
                    else -> "Проверить обновления"
                }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        },
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
