package com.mok.buylist.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mok.buylist.R
import com.mok.buylist.ui.MainActivity
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal fun shouldNotifyUpdate(
    installed: Long, available: Long, lastNotified: Long,
    active: Boolean = false, allowRepeat: Boolean = false,
): Boolean = available > installed && !active && (allowRepeat || available > lastNotified)

internal data class UpdateDiscovery(val release: AppRelease?, val systemPosted: Boolean = false)

internal object UpdateNotifications {
    const val OPEN_UPDATES = "com.mok.buylist.OPEN_UPDATES"
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 731

    private fun prefs(context: Context) =
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("notify_enabled", true)

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(context: Context) {
        manager(context).createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Обновления приложения", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Новые версии из выбранного канала Beta или Stable" })
    }

    fun canPost(context: Context): Boolean {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager(context).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun shouldRequestPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && enabled(context) &&
            !prefs(context).getBoolean("permission_requested", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

    fun markPermissionRequested(context: Context) {
        prefs(context).edit().putBoolean("permission_requested", true).apply()
    }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("notify_enabled", value).apply()
        if (!value) clearNotification(context)
    }

    fun clearNotification(context: Context) {
        manager(context).cancel(NOTIFICATION_ID)
    }

    /** A foreground release lookup must not depend on notification permissions. */
    suspend fun check(context: Context, allowRepeat: Boolean): UpdateDiscovery? {
        AppUpdater.hasPendingCache(context)
        val channel = AppUpdater.selectedChannel(context)
        val release = AppUpdater.latest(channel)
        coroutineContext.ensureActive()
        if (channel != AppUpdater.selectedChannel(context)) return null
        AppUpdater.cachedUpdate(context, release)
        coroutineContext.ensureActive()
        return report(context, channel, release, allowRepeat)
    }

    /** Used for manual and automatic checks. No notification is marked as sent before posting. */
    @SuppressLint("MissingPermission")
    fun report(context: Context, channel: UpdateChannel, release: AppRelease?, allowRepeat: Boolean = false): UpdateDiscovery? {
        if (channel != AppUpdater.selectedChannel(context)) return null
        val installed = AppUpdater.installedVersion(context)
        val available = release?.takeIf { it.version.isNewerThan(installed) }
        if (available == null) {
            clearNotification(context)
            return UpdateDiscovery(null)
        }
        if (!enabled(context) || !canPost(context)) return UpdateDiscovery(available)
        val state = prefs(context)
        val noticeKey = "posted_v2_${channel.key}"
        if (!shouldNotifyUpdate(installed.code, available.version.code, state.getLong(noticeKey, 0L),
                active = isActive(context, available), allowRepeat = allowRepeat)) {
            return UpdateDiscovery(available, systemPosted = true)
        }
        return try {
            val pending = PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).setAction(OPEN_UPDATES)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val body = "Доступна версия ${available.version.name}. Нажмите, чтобы обновить."
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update_notification)
                .setContentTitle("Доступно обновление")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .addAction(R.drawable.ic_update_notification, "Обновить", pending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            // Android accepts posting asynchronously. Confirm it later rather than assuming delivery.
            UpdateDiscovery(available)
        } catch (e: Exception) {
            Log.w("BuyListUpdates", "Unable to post update notification", e)
            UpdateDiscovery(available)
        }
    }

    suspend fun confirmPosted(context: Context, discovery: UpdateDiscovery?): UpdateDiscovery? {
        val result = discovery ?: return null
        val release = result.release ?: return result
        if (result.systemPosted || !enabled(context) || !canPost(context)) return result
        kotlinx.coroutines.delay(350)
        if (release.version.channel != AppUpdater.selectedChannel(context)) return null
        val active = isActive(context, release)
        if (active) {
            prefs(context).edit().putLong("posted_v2_${release.version.channel.key}", release.version.code).apply()
        }
        return result.copy(systemPosted = active)
    }

    private fun isActive(context: Context, release: AppRelease): Boolean =
        manager(context).activeNotifications.any {
            it.id == NOTIFICATION_ID &&
                it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ==
                "Доступна версия ${release.version.name}. Нажмите, чтобы обновить."
        }
}
