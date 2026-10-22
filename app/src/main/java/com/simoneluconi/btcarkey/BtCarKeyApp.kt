package com.simoneluconi.btcarkey

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.simoneluconi.btcarkey.keys.DeviceStore
import com.simoneluconi.btcarkey.link.CarKeyController
import com.simoneluconi.btcarkey.link.CommandResult
import com.simoneluconi.btcarkey.protocol.CarCommand
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.simoneluconi.btcarkey.link.KeepConnectedService
import com.simoneluconi.btcarkey.settings.AppSettings
import com.simoneluconi.btcarkey.smart.CompanionLink
import com.simoneluconi.btcarkey.smart.SmartEntry
import com.simoneluconi.btcarkey.smart.SmartEntrySettings
import com.simoneluconi.btcarkey.smart.SmartNotifications

class BtCarKeyApp : Application() {
    private val scope = MainScope()
    lateinit var store: DeviceStore
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var controller: CarKeyController
        private set
    lateinit var smart: SmartEntrySettings
        private set

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        EventLog.log("App started (${BuildConfig.VERSION_NAME})")
        store = DeviceStore(this)
        settings = AppSettings(this)
        smart = SmartEntrySettings(this)
        controller = CarKeyController(this, store)
        // Presence watching is tied to the address the box had when smart unlock was turned on
        store.onMacChanged = { serial, oldMac -> SmartEntry.onMacChanged(this, serial, oldMac) }
        store.onRemoved = { serial, mac -> SmartEntry.onRemoved(this, serial, mac) }
        SmartEntry.refresh(this)
        // A lock from the app also closes a car smart unlock may have left open
        scope.launch {
            controller.results.collect { if (it.command == CarCommand.LOCK && it !is CommandResult.Failed) SmartEntry.onCarLocked(this@BtCarKeyApp) }
        }
        // Presence watching survives in the system, asking again is harmless and repairs a lost one
        smart.enabledSerial.value?.let(store::device)?.mac?.let { CompanionLink.startObserving(this, it) }
        // A pause lasts until the driver resumes: its notification comes back after a reboot or an update
        if (smart.enabledSerial.value != null && smart.paused.value) SmartNotifications.showPaused(this)
        // Connected while the app is on screen, released shortly after it leaves (battery),
        // unless the driver asked to stay connected
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                controller.onForeground()
                // Android only lets a foreground service start while the app is visible
                syncKeepConnected()
            }

            override fun onStop(owner: LifecycleOwner) = controller.onBackground()
        })
    }

    /**
     * Turns Stay connected on or off (call while visible). It and smart unlock exclude each other:
     * a box kept linked never advertises, so Android can't tell when the car comes and goes. Returns
     * whether turning it on turned smart unlock off.
     */
    fun setKeepConnected(on: Boolean): Boolean {
        settings.setKeepConnected(on)
        val smartOff = on && smart.enabledSerial.value != null
        if (smartOff) {
            EventLog.log("Stay connected on: turning smart unlock off (the box wouldn't advertise)")
            SmartEntry.disable(this)
        }
        syncKeepConnected()
        return smartOff
    }

    /** Starts or stops the background connection to match the setting (call while visible). */
    fun syncKeepConnected() {
        KeepConnectedService.sync(this, settings.keepConnected.value && store.selected.value != null)
    }
}
