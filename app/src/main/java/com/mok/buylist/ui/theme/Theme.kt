package com.mok.buylist.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

enum class ThemeMode { AUTO, LIGHT, DARK }

/** FRESH CUT: семантическая палитра из экспортированного макета Figma. */
@Immutable
class ShoppingPalette(
    val isDark: Boolean,
    val header: Color,
    val onHeader: Color,
    val headerTrack: Color,
    val headerFill: Color,
    val background: Color,
    val card: Color,
    val text: Color,
    val textSecondary: Color,
    val ring: Color,
    val boughtBg: Color,
    val accent: Color,
    val onAccent: Color,
    val chipBg: Color,
    val chipBorder: Color,
    val chipText: Color,
    val field: Color,
    val sheet: Color,
    val iconMuted: Color,
    val widgetBg: Color,
    val error: Color,
    val danger: Color,
    val onDanger: Color,
) {
    val actionFill: Color get() = Color(0xFFD6F36D)
    val actionInk: Color get() = Color(0xFF18230C)
    val line: Color get() = chipBorder
    /** Strong outline for controls whose boundary communicates interactivity. */
    val controlOutline: Color get() = if (isDark) Color(0xFF758174) else Color(0xFF80877C)
    val disabledInk: Color get() = textSecondary
    /** Data marks must remain visible on both light and dark cards. */
    val chartFill: Color get() = accent
}

val LightPalette = ShoppingPalette(
    isDark = false,
    header = Color(0xFFF6F5F0),
    onHeader = Color(0xFF171C19),
    headerTrack = Color(0xFFEFEEE8),
    headerFill = Color(0xFF35491C),
    background = Color(0xFFF6F5F0),
    card = Color(0xFFFFFFFF),
    text = Color(0xFF171C19),
    textSecondary = Color(0xFF646B63),
    ring = Color(0xFF646B63),
    boughtBg = Color(0xFFEFEEE8),
    accent = Color(0xFF35491C),
    onAccent = Color(0xFFFFFFFF),
    chipBg = Color(0xFFEFEEE8),
    chipBorder = Color(0xFFE1E2D9),
    chipText = Color(0xFF35491C),
    field = Color(0xFFEFEEE8),
    sheet = Color(0xFFF6F5F0),
    iconMuted = Color(0xFF646B63),
    widgetBg = Color(0xFFF6F5F0),
    error = Color(0xFFB84038),
    danger = Color(0xFFB84038),
    onDanger = Color(0xFFFFFFFF),
)

val DarkPalette = ShoppingPalette(
    isDark = true,
    header = Color(0xFF171B18),
    onHeader = Color(0xFFF4F6EE),
    headerTrack = Color(0xFF2B332C),
    headerFill = Color(0xFFD6F36D),
    background = Color(0xFF171B18),
    card = Color(0xFF232924),
    text = Color(0xFFF4F6EE),
    textSecondary = Color(0xFFA3AEA0),
    ring = Color(0xFFA3AEA0),
    boughtBg = Color(0xFF2B332C),
    accent = Color(0xFFD6F36D),
    onAccent = Color(0xFF18230C),
    chipBg = Color(0xFF2B332C),
    chipBorder = Color(0xFF39433A),
    chipText = Color(0xFFD6F36D),
    field = Color(0xFF2B332C),
    sheet = Color(0xFF171B18),
    iconMuted = Color(0xFFA3AEA0),
    widgetBg = Color(0xFF171B18),
    error = Color(0xFFFF9B92),
    danger = Color(0xFFFF9B92),
    onDanger = Color(0xFF171B18),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.AUTO -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Смена темы без покадровой перекраски. Раньше все 22 цвета анимировались по отдельности, и на каждом кадре
 * перекомпоновывался весь экран, из-за чего переключение подтормаживало. Теперь новая палитра применяется один раз,
 * а поверх неё лежит заливка цветом прежнего фона, которая растворяется: экран проявляется сквозь неё. Это только
 * изменение прозрачности слоя — без перекомпоновки.
 */
@Composable
fun ShoppingTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    val p = if (dark) DarkPalette else LightPalette
    val colors = remember(dark) {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = p.accent,
            onPrimary = p.onAccent,
            primaryContainer = p.chipBg,
            onPrimaryContainer = p.chipText,
            background = p.background,
            onBackground = p.text,
            surface = p.card,
            onSurface = p.text,
            surfaceVariant = p.field,
            onSurfaceVariant = p.textSecondary,
            outline = p.ring,
            error = p.error,
            onError = p.onDanger,
            outlineVariant = p.line,
        )
    }

    // Тема сменилась: запоминаем фон прежней и показываем заливку им. Заливка создаётся в тот же кадр, что и новая
    // палитра (сразу с полной непрозрачностью), поэтому новая тема не «мигает» на один кадр.
    var shownDark by remember { mutableStateOf(dark) }
    var fadeFrom by remember { mutableStateOf<Color?>(null) }
    var fadeKey by remember { mutableIntStateOf(0) }
    if (shownDark != dark) {
        fadeFrom = (if (shownDark) DarkPalette else LightPalette).background
        fadeKey++
        shownDark = dark
    }

    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = colors, typography = ShoppingTypography) {
            Box {
                content()
                fadeFrom?.let { from ->
                    key(fadeKey) { ThemeFade(from, onDone = { fadeFrom = null }) }
                }
            }
        }
    }
}

@Composable
private fun ThemeFade(color: Color, onDone: () -> Unit) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(0f, Motion.smooth(Motion.Medium))
        onDone()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(color))
}
