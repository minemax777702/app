package com.mok.buylist.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Выбранная тема приложения и виджета — для каждого своя. */
class ThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)

    private val _app = MutableStateFlow(read(KEY_APP))
    val app: StateFlow<ThemeMode> = _app

    private val _widget = MutableStateFlow(read(KEY_WIDGET))
    val widget: StateFlow<ThemeMode> = _widget

    fun setApp(mode: ThemeMode) {
        prefs.edit().putString(KEY_APP, mode.name).apply()
        _app.value = mode
    }

    fun setWidget(mode: ThemeMode) {
        prefs.edit().putString(KEY_WIDGET, mode.name).apply()
        _widget.value = mode
    }

    private fun read(key: String): ThemeMode =
        prefs.getString(key, null)?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
            ?: ThemeMode.AUTO

    private companion object {
        const val KEY_APP = "app"
        const val KEY_WIDGET = "widget"
    }
}
