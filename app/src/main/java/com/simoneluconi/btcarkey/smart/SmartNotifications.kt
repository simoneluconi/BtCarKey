package com.simoneluconi.btcarkey.smart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.MainActivity
import com.simoneluconi.btcarkey.R

/** Smart unlock's notifications: the quiet one while near the car, and the safety alerts. */
object SmartNotifications {
    private const val CHANNEL_ACTIVE = "smart_active"
    private const val CHANNEL_ALERTS = "smart_alerts"
    const val ACTIVE_ID = 10
    private const val SUSPENDED_ID = 11
    private const val NOT_LOCKED_ID = 12
    private const val NEEDS_SETUP_ID = 13
    private const val PAUSED_ID = 14
    const val ACTION_RESUME = "com.simoneluconi.btcarkey.SMART_RESUME"
    const val ACTION_PAUSE = "com.simoneluconi.btcarkey.SMART_PAUSE"

    @Volatile
    private var channelsReady = false

    /** Creates the channels once per process (creating an existing one is a no-op, but a binder call each). */
    private fun channels(context: Context) {
        if (channelsReady) return
        channelsReady = true
        val manager = context.getSystemService(NotificationManager::class.java)
        // Android requires a notification while the service runs; it only exists near the car
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, context.getString(R.string.smart_channel_active), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.smart_channel_active_description)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.smart_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.smart_channel_alerts_description)
            }
        )
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, SmartActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    /** The foreground service's notification, while the car is in range; Pause stops everything automatic. */
    fun active(context: Context, text: String): Notification {
        channels(context)
        return NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.smart_title))
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, context.getString(R.string.smart_pause), broadcast(context, ACTION_PAUSE, 2))
            .build()
    }

    /** The cooldown or the safety net paused smart unlock: with a Resume button. */
    fun showSuspended(context: Context) =
        alert(context, SUSPENDED_ID, context.getString(R.string.smart_suspended_title), context.getString(R.string.smart_suspended_text)) {
            addAction(0, context.getString(R.string.smart_resume), broadcast(context, ACTION_RESUME, 1))
        }

    /** Paused by the driver: stays (silent) until they resume, so the pause is never forgotten. */
    fun showPaused(context: Context) =
        alert(context, PAUSED_ID, context.getString(R.string.smart_paused_title), context.getString(R.string.smart_paused_text)) {
            setOngoing(true)
            setSilent(true)
            setAutoCancel(false)
            addAction(0, context.getString(R.string.smart_resume), broadcast(context, ACTION_RESUME, 1))
        }

    fun cancelNotLocked(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOT_LOCKED_ID)
    }

    fun cancelPaused(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PAUSED_ID)
    }

    /** The lock couldn't be sent (link dropped, or the box refused it): the car may still be open. */
    fun showNotLocked(context: Context, name: String) =
        alert(context, NOT_LOCKED_ID, context.getString(R.string.smart_not_locked_title, name), context.getString(R.string.smart_not_locked_text))

    /** Smart unlock stopped because the box changed address: it needs turning on again (new association). */
    fun showNeedsSetup(context: Context) =
        alert(context, NEEDS_SETUP_ID, context.getString(R.string.smart_needs_setup_title), context.getString(R.string.smart_needs_setup_text))

    private fun alert(context: Context, id: Int, title: String, text: String, extra: NotificationCompat.Builder.() -> Unit = {}) {
        channels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .apply(extra)
            .build()
        // Without the notification permission this is silently dropped: the screens ask for it when turning on
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
            .onFailure { EventLog.log("Smart: couldn't show \"$title\" (${it.javaClass.simpleName})") }
    }

    fun cancelSuspended(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(SUSPENDED_ID)
    }
}

/** The notifications' "Pause" and "Resume" buttons. */
class SmartActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SmartNotifications.ACTION_RESUME -> SmartEntry.resume(context)
            SmartNotifications.ACTION_PAUSE -> SmartEntry.pause(context)
        }
    }
}
