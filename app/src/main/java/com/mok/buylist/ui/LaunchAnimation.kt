package com.mok.buylist.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.Motion

private const val LaunchDuration = 1500

/** One finite timeline, rendered in the draw phase. No bitmaps, blur filters or looping animation. */
@Composable
internal fun LaunchAnimation(onFinished: () -> Unit) {
    val palette = LocalPalette.current
    val timeline = remember { Animatable(0f) }
    val finish = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        timeline.animateTo(LaunchDuration.toFloat(), tween(LaunchDuration, easing = LinearEasing))
        finish.value()
    }

    Canvas(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - launchPhase(timeline.value, 1230, 1500) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        val time = timeline.value
        drawRect(palette.background)
        val drift = launchPhase(time, 0, LaunchDuration)
        val width = size.width
        val height = size.height
        val radius = maxOf(width, height) * 0.48f
        val green = if (palette.isDark) Color(0xFF2C925C) else Color(0xFF9FE5B4)
        val olive = if (palette.isDark) Color(0xFF56743D) else Color(0xFFA8D4A0)
        val lime = if (palette.isDark) Color(0xFF4C6939) else Color(0xFFE5E7B7)
        fun glow(color: Color, opacity: Float, center: Offset, extent: Float) {
            drawRect(Brush.radialGradient(
                colors = listOf(color.copy(alpha = opacity), color.copy(alpha = 0f)),
                center = center, radius = extent,
            ))
        }
        glow(green, if (palette.isDark) 0.24f else 0.47f,
            Offset(width * (0.9f - 0.13f * drift), height * (0.10f + 0.10f * drift)), radius)
        glow(olive, if (palette.isDark) 0.20f else 0.40f,
            Offset(width * (0.04f + 0.16f * drift), height * (0.94f - 0.12f * drift)), radius * 1.1f)
        glow(lime, if (palette.isDark) 0.12f else 0.27f,
            Offset(width * (-0.10f + 0.06f * drift), height * 0.40f), radius * 0.68f)

        val side = minOf(132.dp.toPx(), width * 0.42f, height * 0.42f)
        val entry = launchPhase(time, 0, 330)
        val logoScale = side / 108f * (0.82f + 0.18f * entry)
        val center = this.center
        translate(center.x - 54f * logoScale, center.y - 54f * logoScale) {
            scale(logoScale, logoScale, pivot = Offset.Zero) {
                drawLaunchLogo(time, entry)
            }
        }
    }
}

private fun launchPhase(time: Float, start: Int, end: Int): Float =
    Motion.Smooth.transform(((time - start) / (end - start)).coerceIn(0f, 1f))

// Geometry and colors match ic_launcher_foreground.xml and ic_launcher_background.xml.
private val launcherClip = Path().apply {
    addRoundRect(RoundRect(Rect(0f, 0f, 108f, 108f), CornerRadius(26f)))
}
private val launcherClamp = PathParser().parsePathString(
    "M47,26.5h14a3,3 0 0 1 3,3v3.5h-20v-3.5a3,3 0 0 1 3,-3z",
).toPath()

private fun DrawScope.drawLaunchLogo(time: Float, entry: Float) {
    val paper = launchPhase(time, 100, 480)
    val clamp = launchPhase(time, 350, 620)
    clipPath(launcherClip) {
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(Color(0xFF4FDB8F), Color(0xFF0E8A5A)), Offset.Zero, Offset(108f, 108f),
            ),
            topLeft = Offset.Zero, size = Size(108f, 108f), cornerRadius = CornerRadius(26f),
            alpha = entry,
        )
        drawCircle(Color.White.copy(alpha = 0.12f * entry), radius = 38f, center = Offset(92f, -6f))
        translate(0f, 8f * (1f - paper)) {
            drawRoundRect(Color(0xFF063D26).copy(alpha = 0.2f * paper),
                topLeft = Offset(35f, 32.5f), size = Size(38f, 48f), cornerRadius = CornerRadius(6f))
            drawRoundRect(Color.White.copy(alpha = paper),
                topLeft = Offset(35f, 30f), size = Size(38f, 48f), cornerRadius = CornerRadius(6f))
        }
        translate(0f, -6f * (1f - clamp)) {
            drawPath(launcherClamp, Color(0xFF146B47).copy(alpha = clamp))
        }

        fun row(y: Float, length: Float, start: Int, circle: Boolean) {
            val progress = launchPhase(time, start, start + 260)
            if (progress <= 0f) return
            if (circle) drawCircle(
                Color(0xFF8FD3AE).copy(alpha = progress),
                radius = 3.2f, center = Offset(43f, y), style = Stroke(1.7f),
            )
            drawLine(
                Color(if (circle) 0xFF9ED9B8 else 0xFFCFE9DB).copy(alpha = progress),
                start = Offset(53f, y), end = Offset(53f + length * progress, y),
                strokeWidth = 3f, cap = StrokeCap.Round,
            )
        }
        row(44.5f, 14f, 490, false)
        row(56.5f, 14f, 610, true)
        row(68.5f, 9f, 730, true)

        val tick = launchPhase(time, 860, 1160)
        if (tick > 0f) {
            val a = Offset(39.5f, 45f)
            val b = Offset(42.1f, 47.6f)
            val c = Offset(47.1f, 42f)
            val first = (tick / 0.33f).coerceIn(0f, 1f)
            val second = ((tick - 0.33f) / 0.67f).coerceIn(0f, 1f)
            val check = Color(0xFF1FA65A)
            drawLine(check, a, a + (b - a) * first, 2.4f, cap = StrokeCap.Round)
            if (second > 0f) drawLine(check, b, b + (c - b) * second, 2.4f, cap = StrokeCap.Round)
        }
    }
}
