package com.mok.buylist.ui

import android.graphics.Color
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.nearbySync
import com.mok.buylist.themeSettings
import com.mok.buylist.ui.theme.ShoppingTheme
import com.mok.buylist.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    internal var notice by mutableStateOf<Pair<Long, String>?>(null)
        private set
    private var noticeId = 0L
    private val noticeSheets = mutableStateListOf<Any>()
    internal var noticeOwner by mutableStateOf<Any?>(null)
        private set
    internal fun notifyUser(message: String) {
        noticeOwner = noticeSheets.lastOrNull()
        notice = ++noticeId to message
    }

    internal fun registerNoticeSheet(owner: Any) {
        noticeSheets.add(owner)
        if (notice != null) noticeOwner = owner
    }
    internal fun unregisterNoticeSheet(owner: Any) {
        noticeSheets.remove(owner)
        if (noticeOwner === owner) noticeOwner = noticeSheets.lastOrNull()
    }

    override fun onStop() {
        nearbySync.stop()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val mode by themeSettings.app.collectAsStateWithLifecycle()
            val dark = isDarkTheme(mode)
            // Тему приложения выбирает пользователь, а не система, поэтому цвет значков в системных
            // панелях задаём сами. Шапка всегда зелёная, значки в статус-баре всегда светлые.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
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
                    val current = notice
                    if (current != null) {
                        LaunchedEffect(current.first) {
                            delay(3000)
                            if (notice?.first == current.first) notice = null
                        }
                        if (noticeOwner == null) NoticeOverlay(current.second)
                    }
                }
            }
        }
    }
}

@Composable
internal fun NoticeInSheet() {
    val activity = LocalActivity.current as? MainActivity
    val owner = remember { Any() }
    DisposableEffect(activity, owner) {
        activity?.registerNoticeSheet(owner)
        onDispose { activity?.unregisterNoticeSheet(owner) }
    }
    val current = activity?.notice
    if (current != null && activity.noticeOwner === owner) {
        NoticeOverlay(current.second)
    }
}

@Composable
private fun NoticeOverlay(message: String) {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density) + with(density) { 12.dp.roundToPx() }
    val position = remember(top) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                popupContentSize: IntSize): IntOffset = IntOffset((windowSize.width - popupContentSize.width) / 2, top)
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        Snackbar(Modifier.widthIn(max = 560.dp).padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }) { Text(message) }
    }
}
