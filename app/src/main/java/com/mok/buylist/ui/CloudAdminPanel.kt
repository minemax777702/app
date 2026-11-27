package com.mok.buylist.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mok.buylist.cloud.CloudAccessPolicy
import com.mok.buylist.cloud.CloudAdminException
import com.mok.buylist.cloud.CloudStorage
import com.mok.buylist.cloud.GitHubCloudAdmin
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Admin setup only: no user data is uploaded and no credentials are persisted. */
@Composable
internal fun CloudAdminPanel() {
    val p = LocalPalette.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<GitHubCloudAdmin?>(null) }
    var storage by remember { mutableStateOf<CloudStorage?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }

    fun lock() {
        task?.cancel()
        task = null
        token = ""
        session = null
        storage = null
        message = null
        busy = false
    }

    DisposableEffect(activity, lifecycle) {
        val window = activity?.window
        val wasSecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) lock()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            lock()
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun execute(operation: suspend () -> CloudStorage, onSuccess: (CloudStorage) -> Unit) {
        if (busy) return
        busy = true
        message = null
        task = scope.launch {
            try {
                onSuccess(operation())
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudAdminException) {
                message = e.message
            } catch (_: Exception) {
                message = "Не удалось связаться с GitHub. Проверьте интернет и повторите."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FreshCutSectionTitle("Облако")
        Text("Личные профили, группы и синхронизация будут подключены после настройки приватного хранилища. Пока данные остаются только на устройстве.", color = p.textSecondary, fontSize = 14.sp)
        FreshCutDetailLine("Репозиторий", CloudAccessPolicy.REPOSITORY)
        FreshCutSectionTitle("Администрирование", Modifier.padding(top = 8.dp))
        val admin = session
        val current = storage
        if (admin == null || current == null) {
            Text("Вход доступен только владельцу GitHub. Используйте токен с необходимыми правами на репозиторий. Токен не сохраняется и очищается при выходе.", color = p.textSecondary, fontSize = 14.sp)
            SheetTextField(token, { token = it.filterNot(Char::isWhitespace).take(512) }, "Токен GitHub",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                placeholder = "Введите токен", readOnly = busy,
                visualTransformation = PasswordVisualTransformation())
            FreshCutSecondaryButton(if (busy) "Проверяем…" else "Войти", {
                val candidate = GitHubCloudAdmin(token.trim())
                token = ""
                execute(candidate::inspect) {
                    session = candidate
                    storage = it
                }
            }, enabled = token.isNotBlank() && !busy)
        } else {
            FreshCutDetailLine("Владелец", CloudAccessPolicy.OWNER)
            FreshCutDetailLine("Доступ", "Подтверждён")
            FreshCutDetailLine("Хранилище", if (current.exists) "Приватное" else "Не найдено")
            if (current.exists) {
                FreshCutDetailLine("Размер, КиБ", current.sizeKiB.toString())
                FreshCutDetailLine("GitHub Actions", when (current.actionsEnabled) {
                    true -> "Включены"
                    false -> "Отключены"
                    null -> "Не проверены"
                })
                if (current.actionsEnabled != false) {
                    Text("Перед подключением пользователей отключите Actions в репозитории данных.", color = p.textSecondary, fontSize = 14.sp)
                    FreshCutSecondaryButton("Отключить Actions", {
                        execute(admin::disableActions) { storage = it }
                    }, enabled = !busy)
                }
            } else {
                Text("Если репозиторий уже существует, проверьте доступ токена. Создаётся только приватный репозиторий, без отправки покупок.", color = p.textSecondary, fontSize = 14.sp)
                FreshCutSecondaryButton("Создать приватный репозиторий", {
                    execute(admin::createPrivateStorage) { storage = it }
                }, enabled = !busy)
            }
            FreshCutSecondaryButton(if (busy) "Проверяем…" else "Обновить статус", {
                execute(admin::inspect) { storage = it }
            }, enabled = !busy)
            FreshCutSecondaryButton("Выйти", { lock() }, enabled = !busy)
        }
        message?.let { Text(it, color = p.error, fontSize = 14.sp) }
    }
}
