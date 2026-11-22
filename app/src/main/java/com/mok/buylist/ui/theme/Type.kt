package com.mok.buylist.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.mok.buylist.R

/**
 * Manrope из макетов. Один переменный файл (`res/font/manrope.ttf`, лицензия OFL), насыщенность задаётся осью wght.
 * Нужны только те начертания, что используются в приложении.
 */
val Manrope = FontFamily(
    listOf(
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
        FontWeight.Bold to 700,
        FontWeight.ExtraBold to 800,
    ).map { (weight, axis) ->
        Font(R.font.manrope, weight, variationSettings = FontVariation.Settings(FontVariation.weight(axis)))
    },
)

/** Стандартная типографика Material 3, но шрифтом Manrope: диалоги и шторки берут стили оттуда. */
val ShoppingTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Manrope),
        displayMedium = base.displayMedium.copy(fontFamily = Manrope),
        displaySmall = base.displaySmall.copy(fontFamily = Manrope),
        headlineLarge = base.headlineLarge.copy(fontFamily = Manrope),
        headlineMedium = base.headlineMedium.copy(fontFamily = Manrope),
        headlineSmall = base.headlineSmall.copy(fontFamily = Manrope),
        titleLarge = base.titleLarge.copy(fontFamily = Manrope),
        titleMedium = base.titleMedium.copy(fontFamily = Manrope),
        titleSmall = base.titleSmall.copy(fontFamily = Manrope),
        bodyLarge = base.bodyLarge.copy(fontFamily = Manrope),
        bodyMedium = base.bodyMedium.copy(fontFamily = Manrope),
        bodySmall = base.bodySmall.copy(fontFamily = Manrope),
        labelLarge = base.labelLarge.copy(fontFamily = Manrope),
        labelMedium = base.labelMedium.copy(fontFamily = Manrope),
        labelSmall = base.labelSmall.copy(fontFamily = Manrope),
    )
}
