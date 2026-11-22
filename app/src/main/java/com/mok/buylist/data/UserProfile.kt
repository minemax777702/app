package com.mok.buylist.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Кто пользуется этим телефоном. [id] создаётся один раз и привязывается ко всем действиям. */
data class Profile(val id: String, val name: String)

/** Профиль хранится в SharedPreferences. `null` — приложение запущено впервые и имя ещё не спросили. */
class UserProfile(context: Context, prefsName: String = "profile") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<Profile?> = _profile

    /** Id пользователя; пустая строка до регистрации. */
    val id: String get() = _profile.value?.id.orEmpty()

    /** Сохраняет имя; идентификатор создаётся при первом сохранении и больше не меняется. */
    fun save(name: String): Profile {
        val id = _profile.value?.id ?: UUID.randomUUID().toString()
        val profile = Profile(id, name)
        prefs.edit().putString(KEY_ID, id).putString(KEY_NAME, name).apply()
        _profile.value = profile
        return profile
    }

    /** Восстановление копии на новом телефоне: принимаем прежний идентификатор владельца вместо созданного заново. */
    fun adopt(id: String, name: String): Profile {
        val profile = Profile(id, name)
        prefs.edit().putString(KEY_ID, id).putString(KEY_NAME, name).apply()
        _profile.value = profile
        return profile
    }

    private fun read(): Profile? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val name = prefs.getString(KEY_NAME, null) ?: return null
        return Profile(id, name)
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
    }
}
