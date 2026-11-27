package com.mok.buylist.ui

import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.mok.buylist.update.UpdateNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
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
import com.mok.buylist.nearbySync
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
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) checkUpdatesOnEntry(force = true)
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

    override fun onResume() {
        super.onResume()
        checkUpdatesOnEntry()
    }

    internal fun checkUpdatesOnEntry(force: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (updateCheck?.isActive == true) {
            if (!force) return
            updateCheck?.cancel()
        }
        updateCheck = lifecycleScope.launch {
            try {
                UpdateNotifications.check(applicationContext, force)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed release check must never interrupt the shopping list.
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

    override fun onStop() {
        updateCheck?.cancel()
        updateCheck = null
        nearbySync.stop()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showUpdates = savedInstanceState?.getBoolean("show_updates") == true
        handleUpdateIntent(intent)
        setContent {
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 && UpdateNotifications.shouldRequestPermission(this@MainActivity)) {
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
                    ShoppingScreen()
                    if (showUpdates) AppUpdateSheet(onClose = { showUpdates = false })
                    val presentation = noticePresentation
                    val current = presentation.current
                    val accessibility = LocalAccessibilityManager.current
                    if (current != null) {
                        if (noticeOwner == null) NoticeOverlay(presentation, ::toggleNotice, ::dismissNotice)
                        LaunchedEffect(current.id, presentation.expanded) {
                            if (!presentation.expanded) {
                                val duration = current.style.durationMillis
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
                }
            }
        }
    }
}
