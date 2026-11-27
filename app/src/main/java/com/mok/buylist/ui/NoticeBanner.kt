package com.mok.buylist.ui

import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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

internal data class NoticePayload(
    val id: Long,
    val title: String,
    val message: String,
    val style: NoticeStyle,
    val opensUpdates: Boolean = false,
)

/** Разделяем текст только для баннера: журнал получает исходное сообщение. */
internal fun noticePayload(id: Long, raw: String): NoticePayload {
    val style = noticeStyle(raw)
    val normalized = raw.lowercase().replace('ё', 'е')
    val (title, message) = when {
        normalized.startsWith("данные товара обновлены") -> "Товар обновлён" to
            raw.substringAfter(" · ", "Информация из каталога актуализирована.")
        normalized.startsWith("данные каталога обновлены:") -> "Каталог обновлён" to
            ("Обновлено " + raw.substringAfter(": ").replaceFirstChar { it.lowercase() })
        normalized.startsWith("обновление остановлено:") -> "Обновление остановлено" to
            if (normalized.contains("адрес магазина изменился")) raw.substringAfter(": ").replaceFirstChar { it.uppercase() }
            else "Обновлено " + raw.substringAfter(": ")
        normalized == "добавлено в список" -> "Товар добавлен" to "Товар появился в списке покупок."
        normalized == "уже в списке" -> "Товар уже в списке" to "Повторное добавление не требуется."
        normalized == "покупка отмечена" -> "Товар куплен" to "Покупка сохранена."
        normalized == "товар сохранен" -> "Товар сохранён" to "Товар добавлен в базу продуктов."
        normalized.startsWith("список завершен") -> "Покупки завершены" to raw
        else -> when (style) {
            NoticeStyle.SUCCESS -> "Готово"
            NoticeStyle.INFO -> "Информация"
            NoticeStyle.WARNING -> "Внимание"
        } to raw
    }
    return NoticePayload(id, title, formatNoticeMessage(message), style)
}

/** Дополнительные сведения читаются как отдельные строки, а не цепочка статусов. */
internal fun formatNoticeMessage(message: String): String =
    message.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { line ->
        val normalized = line.lowercase().replace('ё', 'е')
        when {
            normalized == "штрихкод отсутствует" -> "Штрихкод не найден."
            normalized == "ручная цена сохранена" -> "Ваша цена не изменена."
            normalized == "цена недоступна" -> "Новая цена недоступна."
            normalized.startsWith("без штрихкода:") -> "Без штрихкода: " + line.substringAfter(':').trim()
            normalized.startsWith("без новой цены:") -> "Без новой цены: " + line.substringAfter(':').trim()
            normalized.startsWith("ошибок:") -> "Ошибок: " + line.substringAfter(':').trim()
            else -> line.replaceFirstChar { it.uppercase() }
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
    val presentation = activity.noticePresentation
    if (presentation.current != null && activity.noticeOwner === owner) {
        NoticeOverlay(presentation, activity::toggleNotice, activity::dismissNotice, activity::activateNotice)
    }
}

/** Один экранный якорь для Activity и любых нижних панелей. Popup не занимает место в layout. */
@Composable
internal fun NoticeOverlay(
    presentation: NoticePresentation,
    onToggle: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    onAction: (Long) -> Unit,
) {
    val notice = presentation.current ?: return
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density) + with(density) { 12.dp.roundToPx() }
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            excludeFromSystemGesture = false,
        ),
    ) {
        androidx.compose.runtime.key(notice.id) {
            NoticeBanner(notice, presentation.expanded, presentation.pending.size,
                onToggle = { onToggle(notice.id) }, onDismiss = { onDismiss(notice.id) }, onAction = { onAction(notice.id) })
        }
    }
}

/** Нажатие закрепляет/раскрывает карточку. За её пределами работает обычный интерфейс. */
@Composable
private fun NoticeBanner(
    notice: NoticePayload,
    expanded: Boolean,
    pendingCount: Int,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
) {
    val p = LocalPalette.current
    val style = notice.style
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
    val shape = RoundedCornerShape(20.dp)
    val configuration = LocalConfiguration.current
    // Do not create a full-width touch target: only the visible card receives input.
    val cardWidth = (configuration.screenWidthDp.dp - 40.dp).coerceIn(1.dp, 560.dp)
    val bodyMaxHeight = (configuration.screenHeightDp.dp * 0.42f).coerceIn(80.dp, 360.dp)
    Box(Modifier.width(cardWidth + 8.dp).padding(4.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().shadow(4.dp, shape, clip = false).clip(shape)
            .background(p.card).border(1.dp, p.line, shape)
            .clickable(role = Role.Button,
                onClickLabel = if (expanded) "Свернуть уведомление" else "Раскрыть и задержать уведомление",
                onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(notice.title, color = p.text, fontSize = 14.sp,
                    lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (expanded) {
                    Text(notice.message, modifier = Modifier.heightIn(max = bodyMaxHeight)
                        .verticalScroll(rememberScrollState()),
                        color = p.textSecondary, fontSize = 12.sp,
                        lineHeight = 17.sp, fontWeight = FontWeight.Normal)
                    Text("Нажмите, чтобы свернуть", color = p.textSecondary, fontSize = 11.sp)
                } else {
                    Text(notice.message, color = p.textSecondary, fontSize = 12.sp,
                        lineHeight = 17.sp, fontWeight = FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (pendingCount > 0) {
                    Text("В очереди: $pendingCount", color = p.textSecondary, fontSize = 11.sp)
                }
            }
            if (notice.opensUpdates) {
                androidx.compose.material3.TextButton(onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("Обновить", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (expanded) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Закрыть уведомление",
                        modifier = Modifier.size(18.dp), tint = p.textSecondary)
                }
            } else {
                Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null,
                    modifier = Modifier.padding(top = 8.dp).size(18.dp), tint = p.textSecondary)
            }
        }
    }
}
