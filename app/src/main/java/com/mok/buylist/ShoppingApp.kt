package com.mok.buylist

import android.app.Application
import android.content.Context
import com.mok.buylist.nearby.NearbySync
import com.mok.buylist.data.AppDatabase
import com.mok.buylist.data.ShoppingRepository
import com.mok.buylist.data.UserProfile
import com.mok.buylist.data.LentaCatalog
import com.mok.buylist.ui.theme.ThemeSettings
import com.mok.buylist.ui.BarcodeSourceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShoppingApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val nearbySync: NearbySync by lazy { NearbySync(this, repository) }
    val userProfile: UserProfile by lazy { UserProfile(this) }
    private val database: AppDatabase by lazy { AppDatabase.create(this) }
    val repository: ShoppingRepository by lazy {
        ShoppingRepository(this, database, userProfile)
    }
    val themeSettings: ThemeSettings by lazy { ThemeSettings(this) }
    val barcodeSourceSettings: BarcodeSourceSettings by lazy { BarcodeSourceSettings(this) }
    val lentaCatalog: LentaCatalog by lazy { LentaCatalog(this, database, appScope) }

}

val Context.repository: ShoppingRepository
    get() = (applicationContext as ShoppingApp).repository

val Context.userProfile: UserProfile
    get() = (applicationContext as ShoppingApp).userProfile

val Context.themeSettings: ThemeSettings
    get() = (applicationContext as ShoppingApp).themeSettings

val Context.lentaCatalog: LentaCatalog
    get() = (applicationContext as ShoppingApp).lentaCatalog

val Context.nearbySync: NearbySync
    get() = (applicationContext as ShoppingApp).nearbySync

val Context.barcodeSourceSettings: BarcodeSourceSettings
    get() = (applicationContext as ShoppingApp).barcodeSourceSettings
