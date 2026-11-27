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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mok.buylist.R
import com.mok.buylist.ui.MainActivity
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal const val UPDATE_CHECK_INTERVAL_MS = 60_000L

internal fun shouldCheckUpdates(now: Long, last: Long): Boolean =
    last <= 0L || now < last || now - last >= UPDATE_CHECK_INTERVAL_MS

internal fun shouldNotifyUpdate(installed: Long, available: Long, lastNotified: Long): Boolean =
    available > installed && available > lastNotified

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

    /** Called only by the foreground Activity. No periodic or background work. */
    @SuppressLint("MissingPermission")
    suspend fun check(context: Context, force: Boolean = false) {
        if (!enabled(context) || !canPost(context)) return
        val channel = AppUpdater.selectedChannel(context)
        val state = prefs(context)
        val checkKey = "checked_${channel.key}"
        val noticeKey = "notified_${channel.key}"
        val installed = AppUpdater.installedVersion(context)
        val lastNotified = state.getLong(noticeKey, 0L)
        if (lastNotified in 1..installed.code) clearNotification(context)
        if (!force && !shouldCheckUpdates(System.currentTimeMillis(), state.getLong(checkKey, 0L))) return
        val release = AppUpdater.latest(channel)
        coroutineContext.ensureActive()
        if (channel != AppUpdater.selectedChannel(context) || !enabled(context) || !canPost(context)) return
        state.edit().putLong(checkKey, System.currentTimeMillis()).apply()
        if (release == null || !release.version.isNewerThan(installed)) {
            clearNotification(context)
            return
        }
        if (!shouldNotifyUpdate(installed.code, release.version.code, lastNotified)) return
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).setAction(OPEN_UPDATES)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = "Доступна версия ${release.version.name}. Нажмите, чтобы обновить."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update_notification)
            .setContentTitle("Доступно обновление")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .addAction(R.drawable.ic_update_notification, "Обновить", pending)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        state.edit().putLong(noticeKey, release.version.code).apply()
    }
}
