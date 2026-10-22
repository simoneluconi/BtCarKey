package com.simoneluconi.btcarkey.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.simoneluconi.btcarkey.BtCarKeyApp
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.MainActivity
import com.simoneluconi.btcarkey.R
import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.settings.HomeAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the box connected with the app closed (the "Stay connected" setting, used by comfort entry).
 * A foreground service of type connectedDevice; its notification shows the link and has Lock and
 * Unlock buttons. The work is the controller's: this only keeps the process alive and tells it so.
 */
class KeepConnectedService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as BtCarKeyApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(getString(R.string.notif_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Android refuses a foreground service started from the background: next time the app opens
            EventLog.log("Background connection: can't start now (${e.javaClass.simpleName})")
            stopSelf()
            return
        }
        app.controller.setKeepAlive(true)
        scope.launch {
            combine(app.controller.state, app.store.devices) { state, devices ->
                val name = devices.firstOrNull { it.serial == app.store.selected.value }?.name.orEmpty()
                describe(state, name)
            }.distinctUntilChanged().collect { text ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOCK -> app.controller.send(CarCommand.LOCK, app.settings.actionConfig(HomeAction.LOCK))
            ACTION_UNLOCK -> app.controller.send(CarCommand.UNLOCK, app.settings.actionConfig(HomeAction.UNLOCK))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        app.controller.setKeepAlive(false)
        super.onDestroy()
    }

    private fun describe(state: LinkState, name: String): String = when (state) {
        is LinkState.Ready -> getString(R.string.notif_connected, name)
        is LinkState.Connecting, is LinkState.Locating, is LinkState.Authenticating -> getString(R.string.notif_waiting, name)
        LinkState.BluetoothOff -> getString(R.string.state_bluetooth_off)
        is LinkState.Problem -> getString(R.string.notif_problem, name)
        else -> getString(R.string.notif_waiting, name)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_lock), action(ACTION_LOCK, 1))
            .addAction(0, getString(R.string.action_unlock), action(ACTION_UNLOCK, 2))
            .build()
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, KeepConnectedService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        /** The notification's channel; Settings links to it so the driver can hide it. */
        const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_LOCK = "com.simoneluconi.btcarkey.LOCK"
        private const val ACTION_UNLOCK = "com.simoneluconi.btcarkey.UNLOCK"

        /**
         * Android requires a notification for a foreground service. Minimum importance keeps it out of
         * the status bar and silent; the driver can switch the channel off entirely in the system
         * settings ([CHANNEL_ID]), and the service keeps running.
         */
        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.notif_channel_description)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** Starts or stops the service; only call while the app is in the foreground (Android's rule). */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, KeepConnectedService::class.java)
            if (enabled) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { EventLog.log("Background connection: start refused (${it.javaClass.simpleName})") }
            } else {
                context.stopService(intent)
            }
        }
    }
}
