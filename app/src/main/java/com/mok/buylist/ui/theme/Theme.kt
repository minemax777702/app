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

/** Цвета дизайна: фиксированная зелёная палитра, одна на приложение и виджет. */
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
)

val LightPalette = ShoppingPalette(
    isDark = false,
    header = Color(0xFF0C7F50),
    onHeader = Color(0xFFFFFFFF),
    headerTrack = Color(0x47FFFFFF),
    headerFill = Color(0xFFFFFFFF),
    background = Color(0xFFF1F8F3),
    card = Color(0xFFFFFFFF),
    text = Color(0xFF14231B),
    textSecondary = Color(0xFF4F6057),
    ring = Color(0xFF3E9A70),
    boughtBg = Color(0xFFDDF1E6),
    accent = Color(0xFF0C7F50),
    onAccent = Color(0xFFFFFFFF),
    chipBg = Color(0xFFE3F4EA),
    chipBorder = Color(0xFFB7DCC8),
    chipText = Color(0xFF0A5C3B),
    field = Color(0xFFEEF6F1),
    sheet = Color(0xFFFFFFFF),
    iconMuted = Color(0xFF3E6B55),
    widgetBg = Color(0xFFF7FBF8),
    error = Color(0xFFB3261E),
    danger = Color(0xFFC62828),
    onDanger = Color(0xFFFFFFFF),
)

val DarkPalette = ShoppingPalette(
    isDark = true,
    header = Color(0xFF0F4A33),
    onHeader = Color(0xFFFFFFFF),
    headerTrack = Color(0x2EFFFFFF),
    headerFill = Color(0xFF5FE0A0),
    background = Color(0xFF0E1512),
    card = Color(0xFF17211C),
    text = Color(0xFFE4F0E9),
    textSecondary = Color(0xFF9DB0A5),
    ring = Color(0xFF5FB88A),
    boughtBg = Color(0xFF1F3A2D),
    accent = Color(0xFF5FE0A0),
    onAccent = Color(0xFF08261A),
    chipBg = Color(0xFF1F3A2D),
    chipBorder = Color(0xFF2F5A44),
    chipText = Color(0xFF9BEBC3),
    field = Color(0xFF1A261F),
    sheet = Color(0xFF17211C),
    iconMuted = Color(0xFF9DB0A5),
    widgetBg = Color(0xFF17211C),
    error = Color(0xFFFFB4AB),
    danger = Color(0xFF8E2A2A),
    onDanger = Color(0xFFFFFFFF),
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
