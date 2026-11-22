package com.mok.buylist.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * Единый «характер» движения. Анимаций в приложении мало и они короткие: помогают понять, что произошло
 * (товар куплен, строка ушла, тема сменилась), но не привлекают внимания сами.
 */
object Motion {
    /** Мелкие отклики: появление и исчезновение подписей, кнопок. */
    const val Quick = 160

    /** Основные переходы: смена состояния строки, шторки, цвета темы. */
    const val Medium = 300

    /** Только для полосы прогресса: движение, которое хочется проследить глазами. */
    const val Slow = 500

    /** Быстро начинается и мягко останавливается. */
    val Smooth = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> smooth(durationMillis: Int = Medium) = tween<T>(durationMillis, easing = Smooth)

    /** Для сдвигов элементов списка: без отскока, но с живым «доводом». */
    fun settle() = spring<IntOffset>(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )
}
