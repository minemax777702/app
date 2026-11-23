package com.mok.buylist.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Device-local switches. Disabled providers must not make network calls. */
internal enum class BarcodeSource(val title: String, val description: String) {
    OPEN_FOOD_FACTS("Open Food Facts", "Название и фото продуктов"),
    UPCITEMDB("UPCitemdb", "Международная база, ограничения бесплатного API"),
    OLEGON("Olegon", "Российские товары; доступность сервиса не гарантируется"),
    DISAI("DiSAI", "Названия из карточек DiSAI; доступность сайта не гарантируется"),
}

internal class BarcodeSourceSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "barcode_lookup_sources", Context.MODE_PRIVATE
    )
    private val _enabled = MutableStateFlow(read())
    val enabled: StateFlow<Set<BarcodeSource>> = _enabled

    fun isEnabled(source: BarcodeSource): Boolean = source in _enabled.value

    fun setEnabled(source: BarcodeSource, value: Boolean) {
        preferences.edit().putBoolean(source.name, value).apply()
        _enabled.value = read()
    }

    private fun read(): Set<BarcodeSource> =
        BarcodeSource.entries.filter { preferences.getBoolean(it.name, true) }.toSet()
}
