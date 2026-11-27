package com.mok.buylist.cloud

import android.content.Context
import androidx.room.Room
import com.mok.buylist.data.AppDatabase
import com.mok.buylist.data.ShoppingRepository
import com.mok.buylist.data.UserProfile
import java.util.UUID

/**
 * Synthetic integration-test workspace. Separate Room DB and profile for each peer.
 * It must not be substituted for the active ShoppingApp.repository yet.
 */
internal class CloudTestPeer(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val prefsName = "cloud-test-peer-" + UUID.randomUUID()
    private val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
    val profile = UserProfile(app, prefsName)
    val repository = ShoppingRepository(app, database, profile, isolatedWorkspace = true)

    override fun close() {
        database.close()
        app.deleteSharedPreferences(prefsName)
    }
}
