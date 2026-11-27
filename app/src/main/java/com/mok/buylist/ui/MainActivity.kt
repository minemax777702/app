package com.mok.buylist.ui

import android.content.Intent
import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.Log
import com.mok.buylist.update.AppRelease
import com.mok.buylist.update.AppUpdater
import com.mok.buylist.update.UpdateDiscovery
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.toArgb
import com.mok.buylist.ui.theme.DarkPalette
import com.mok.buylist.ui.theme.LightPalette
import com.mok.buylist.ui.theme.ThemeMode
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mok.buylist.update.UpdateNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.themeSettings
import com.mok.buylist.ui.theme.ShoppingTheme
import com.mok.buylist.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    internal var noticePresentation by mutableStateOf(NoticePresentation())
        private set
    internal val notice: NoticePayload? get() = noticePresentation.current
    private var noticeId = 0L
    private var showUpdates by mutableStateOf(false)
    private var updateCheck: Job? = null
    private var shownUpdateCode: Long? = null

    internal fun acceptUpdateDiscovery(discovery: UpdateDiscovery?) {
        val result = discovery ?: return
        val release = result.release ?: return
        if (release.version.channel != AppUpdater.selectedChannel(this) ||
            showUpdates || !UpdateNotifications.enabled(this) ||
            shownUpdateCode == release.version.code) return
        // A system notification may be blocked or silent. Show a compact actionable in-app notice as fallback.
        if (!result.systemPosted) {
            shownUpdateCode = release.version.code
            noticeOwner = noticeSheets.lastOrNull()
            noticePresentation = noticePresentation.enqueue(NoticePayload(
                id = ++noticeId, title = "Доступно обновление",
                message = "Версия ${release.version.name} готова к загрузке.",
                style = NoticeStyle.INFO, opensUpdates = true,
            ))
        }
    }

    internal fun activateNotice(id: Long) {
        if (notice?.id == id && notice?.opensUpdates == true) {
            dismissNotice(id)
            openUpdates()
        }
    }

    internal fun openUpdates() {
        showUpdates = true
        UpdateNotifications.clearNotification(this)
    }

    private fun handleUpdateIntent(intent: Intent) {
        if (intent.action == UpdateNotifications.OPEN_UPDATES) {
            openUpdates()
            // Consume the deep link so rotation cannot reopen a dismissed sheet.
            intent.action = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    internal fun checkUpdatesOnEntry(force: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (updateCheck?.isActive == true) {
            if (!force) return
            updateCheck?.cancel()
        }
        updateCheck = lifecycleScope.launch {
            try {
                val discovery = UpdateNotifications.check(applicationContext, allowRepeat = !showUpdates)
                acceptUpdateDiscovery(UpdateNotifications.confirmPosted(applicationContext, discovery))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BuyListUpdates", "Foreground update check failed", e)
            }
        }
    }
    private val noticeSheets = mutableStateListOf<Any>()
    internal var noticeOwner by mutableStateOf<Any?>(null)
        private set
    internal fun notifyUser(message: String) {
        ActionJournal.get(applicationContext).notice(message)
        noticeOwner = noticeSheets.lastOrNull()
        noticePresentation = noticePresentation.enqueue(noticePayload(++noticeId, message))
    }

    internal fun toggleNotice(id: Long) {
        noticePresentation = noticePresentation.toggle(id)
    }

    internal fun dismissNotice(id: Long) {
        noticePresentation = noticePresentation.dismiss(id)
    }

    internal fun registerNoticeSheet(owner: Any) {
        noticeSheets.add(owner)
        if (notice != null) noticeOwner = owner
    }
    internal fun unregisterNoticeSheet(owner: Any) {
        noticeSheets.remove(owner)
        if (noticeOwner === owner) noticeOwner = noticeSheets.lastOrNull()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("show_updates", showUpdates)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        updateCheck?.cancel()
        updateCheck = null
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showUpdates = savedInstanceState?.getBoolean("show_updates") == true
        handleUpdateIntent(intent)
        val initialMode = themeSettings.app.value
        val initialDark = when (initialMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.AUTO -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        window.setBackgroundDrawable(ColorDrawable((if (initialDark) DarkPalette else LightPalette).background.toArgb()))
        val animateLaunch = savedInstanceState == null && !showUpdates && ValueAnimator.areAnimatorsEnabled()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                checkUpdatesOnEntry()
                kotlinx.coroutines.awaitCancellation()
            }
        }
        setContent {
            var showLaunch by androidx.compose.runtime.remember { mutableStateOf(animateLaunch) }
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) checkUpdatesOnEntry(force = true)
            }
            LaunchedEffect(showLaunch) {
                if (!showLaunch && Build.VERSION.SDK_INT >= 33 && UpdateNotifications.shouldRequestPermission(this@MainActivity)) {
                    UpdateNotifications.markPermissionRequested(this@MainActivity)
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val mode by themeSettings.app.collectAsStateWithLifecycle()
            val dark = isDarkTheme(mode)
            // Тему приложения выбирает пользователь, а не система, поэтому цвет значков в системных
            // панелях задаём сами. Статус-бар соответствует светлой или тёмной теме FRESH CUT.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
                onDispose {}
            }
            ShoppingTheme(mode) {
                Box(Modifier.fillMaxSize()) {
                    CloudShoppingRoot()
                    if (showUpdates && !showLaunch) AppUpdateSheet(onClose = { showUpdates = false })
                    val presentation = noticePresentation
                    val current = presentation.current
                    val accessibility = LocalAccessibilityManager.current
                    if (current != null && !showLaunch) {
                        if (noticeOwner == null) NoticeOverlay(presentation, ::toggleNotice, ::dismissNotice, ::activateNotice)
                        LaunchedEffect(current.id, presentation.expanded) {
                            if (!presentation.expanded) {
                                val duration = if (current.opensUpdates) 8000L else current.style.durationMillis
                                delay(accessibility?.calculateRecommendedTimeoutMillis(
                                    originalTimeoutMillis = duration,
                                    containsIcons = true,
                                    containsText = true,
                                    containsControls = true,
                                ) ?: duration)
                                // A click can pin the notice just as the timer expires.
                                if (!noticePresentation.expanded) dismissNotice(current.id)
                            }
                        }
                    }
                    if (showLaunch) LaunchAnimation(onFinished = { showLaunch = false })
                }
            }
        }
    }
}
