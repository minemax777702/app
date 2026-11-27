package com.mok.buylist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.cloud.CloudIntegrationProbe
import com.mok.buylist.cloud.CloudTestResult
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
internal fun CloudIntegrationTestPanel() {
    val context = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<CloudTestResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FreshCutSectionTitle("Самопроверка облака")
        Text("Два тестовых пользователя и отдельные временные базы Room. Реальные товары, профиль и резервные копии не используются. Сетевой обмен проверяется отдельно через SSH.", color = p.textSecondary, fontSize = 13.sp)
        FreshCutSecondaryButton(if (busy) "Выполняется…" else "Запустить самопроверку", {
            results = emptyList()
            message = null
            busy = true
            scope.launch {
                try {
                    CloudIntegrationProbe.run(context) { result ->
                        withContext(Dispatchers.Main) { results = results + result }
                    }
                    message = "Локальная интеграционная проверка завершена"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "Проверка не пройдена. Подробности ниже." }
                finally { busy = false }
            }
        }, enabled = !busy)
        results.forEach { result ->
            FreshCutDetailLine(result.name, if (result.passed) "OK" else "Ошибка")
            if (result.detail.isNotEmpty()) Text(result.detail, color = p.error, fontSize = 12.sp)
        }
        message?.let { Text(it, color = p.text, fontSize = 14.sp) }
    }
}
