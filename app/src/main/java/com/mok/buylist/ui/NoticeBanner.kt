package com.mok.buylist.ui

import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette

internal enum class NoticeStyle(val durationMillis: Long) {
    SUCCESS(2200L), INFO(2600L), WARNING(4200L),
}

/** Сводки с частичным результатом не должны выглядеть как безусловный успех. */
internal fun noticeStyle(message: String): NoticeStyle {
    val text = message.lowercase().replace('ё', 'е')
    return when {
        text.startsWith("обновление остановлено") -> NoticeStyle.INFO
        listOf("не удалось", "ошиб", "некоррект", "недоступ", "не обновлено",
            "без штрихкода:", "без новой цены:", "штрихкод отсутствует",
            "поврежден", "не найден", "не найдена", "истек", "не хватает",
            "время вышло", "укажите адрес", "http ", "уже изменено")
            .any { it in text } -> NoticeStyle.WARNING
        listOf("добавлен", "сохранен", "обновлен", "отмечен", "завершен",
            "восстановлен", "удален", "отменен", "сброшен", "выполнен")
            .any { it in text } -> NoticeStyle.SUCCESS
        else -> NoticeStyle.INFO
    }
}

/** Регистрируется единожды в общей оболочке панели, включая вложенные панели. */
@Composable
internal fun NoticeInSheet() {
    val activity = LocalActivity.current as? MainActivity ?: return
    val owner = remember { Any() }
    DisposableEffect(activity, owner) {
        activity.registerNoticeSheet(owner)
        onDispose { activity.unregisterNoticeSheet(owner) }
    }
    val notice = activity.notice
    if (notice != null && activity.noticeOwner === owner) NoticeOverlay(notice.second)
}

/** Один экранный якорь для Activity и любых нижних панелей. Popup не занимает место в layout. */
@Composable
internal fun NoticeOverlay(message: String) {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density) + with(density) { 8.dp.roundToPx() }
    val position = remember(top) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset((windowSize.width - popupContentSize.width) / 2, top)
        }
    }
    Popup(
        popupPositionProvider = position,
        properties = PopupProperties(
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            excludeFromSystemGesture = false,
        ),
    ) {
        NoticeBanner(message)
    }
}

/** Только визуальное сообщение: не меняет размеры экрана и не принимает ввод. */
@Composable
private fun NoticeBanner(message: String) {
    val p = LocalPalette.current
    val style = noticeStyle(message)
    val tint = when (style) {
        NoticeStyle.SUCCESS -> p.accent
        NoticeStyle.INFO -> p.textSecondary
        NoticeStyle.WARNING -> p.error
    }
    val icon = when (style) {
        NoticeStyle.SUCCESS -> R.drawable.ic_check
        NoticeStyle.INFO -> R.drawable.ic_info
        NoticeStyle.WARNING -> R.drawable.ic_info
    }
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(shape)
            .background(p.card).border(1.dp, p.line, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(17.dp), tint = tint)
            }
            Text(message, Modifier.weight(1f), color = p.text, fontSize = 13.sp,
                lineHeight = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
