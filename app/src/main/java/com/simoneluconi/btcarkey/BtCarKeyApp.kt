package com.simoneluconi.btcarkey

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.simoneluconi.btcarkey.keys.DeviceStore
import com.simoneluconi.btcarkey.link.CarKeyController
import com.simoneluconi.btcarkey.link.KeepConnectedService
import com.simoneluconi.btcarkey.settings.AppSettings

class BtCarKeyApp : Application() {
    lateinit var store: DeviceStore
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var controller: CarKeyController
        private set

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        EventLog.log("App started (${BuildConfig.VERSION_NAME})")
        store = DeviceStore(this)
        settings = AppSettings(this)
        controller = CarKeyController(this, store)
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

    /** Starts or stops the background connection to match the setting (call while visible). */
    fun syncKeepConnected() {
        KeepConnectedService.sync(this, settings.keepConnected.value && store.selected.value != null)
    }
}
