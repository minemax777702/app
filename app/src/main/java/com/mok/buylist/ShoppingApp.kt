package com.mok.buylist

import android.app.Application
import android.content.Context
import com.mok.buylist.cloud.SupabaseCloud
import com.mok.buylist.data.AppDatabase
import com.mok.buylist.data.ShoppingRepository
import com.mok.buylist.data.UserProfile
import com.mok.buylist.data.LentaCatalog
import com.mok.buylist.ui.theme.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ShoppingApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val cloud: SupabaseCloud by lazy { SupabaseCloud(this, appScope) }
    private val localProfile by lazy { UserProfile(this) }
    private val database by lazy { AppDatabase.create(this) }
    internal val localRepository by lazy { ShoppingRepository(this, database, localProfile) }
    private val localCatalog by lazy { LentaCatalog(this, database, appScope) }
    val userProfile: UserProfile get() = cloud.workspace.value?.profile ?: localProfile
    val repository: ShoppingRepository get() = cloud.workspace.value?.repository ?: localRepository
    val lentaCatalog: LentaCatalog get() = cloud.workspace.value?.catalog ?: localCatalog
    internal val workspaceNamespace: String get() = cloud.workspace.value?.namespace.orEmpty()
    val themeSettings: ThemeSettings by lazy { ThemeSettings(this) }
}
val Context.repository: ShoppingRepository get() = (applicationContext as ShoppingApp).repository
val Context.userProfile: UserProfile get() = (applicationContext as ShoppingApp).userProfile
val Context.themeSettings: ThemeSettings get() = (applicationContext as ShoppingApp).themeSettings
val Context.lentaCatalog: LentaCatalog get() = (applicationContext as ShoppingApp).lentaCatalog
