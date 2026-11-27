package com.mok.buylist.ui

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.update.AppRelease
import com.mok.buylist.update.AppUpdater
import com.mok.buylist.update.UpdateChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun AppUpdateSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { AppUpdater.installedVersion(context) }
    var channel by remember { mutableStateOf(AppUpdater.selectedChannel(context)) }
    var attempt by remember { mutableIntStateOf(0) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }

    fun install(file: File) {
        try {
            AppUpdater.install(context, file)
        } catch (e: Exception) {
            error = e.message ?: "Не удалось открыть установщик."
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
            release = AppUpdater.latest(channel)
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
                    if (!downloading && !checking) {
                        channel = UpdateChannel.entries[index]
                        AppUpdater.selectChannel(context, channel)
                    }
                })
                Text(if (channel == UpdateChannel.BETA) "Новые функции и тестовые сборки."
                    else "Только стабильные релизы.", color = p.textSecondary, fontSize = 13.sp)
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
                        ready != null -> Text("APK проверен и готов к установке.", color = p.text)
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
            }
        },
        confirmButton = {
            SheetActionButton(enabled = !checking && !downloading, onClick = {
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
                    checking -> "Проверка…"
                    ready != null -> "Установить"
                    update != null -> "Обновить"
                    else -> "Проверить обновления"
                }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
