package com.simoneluconi.btcarkey

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.simoneluconi.btcarkey.link.CarKeyController
import com.simoneluconi.btcarkey.ui.AppRoot

/** Hosts the Compose UI and the system prompts it needs (permissions, Bluetooth, biometrics). */
class MainActivity : FragmentActivity() {
    private val app get() = application as BtCarKeyApp

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        EventLog.log("Permissions: ${granted.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        app.controller.retry()
    }

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        app.controller.retry()
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        app.syncKeepConnected()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!CarKeyController.hasPermissions(this)) permissions.launch(CarKeyController.requiredPermissions())
        setContent {
            AppRoot(
                app = app,
                requestPermissions = { permissions.launch(CarKeyController.requiredPermissions()) },
                enableBluetooth = {
                    if (CarKeyController.hasPermissions(this)) {
                        enableBluetooth.launch(android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } else {
                        permissions.launch(CarKeyController.requiredPermissions())
                    }
                },
                requestNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                authenticate = ::authenticate,
            )
        }
    }

    /**
     * Fingerprint/face or the phone's PIN/pattern before anything that reveals or changes a key.
     * A phone with no screen lock has nothing to ask: the action goes ahead (and is logged).
     */
    private fun authenticate(title: String, onSuccess: () -> Unit) {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            // STRONG + credential isn't supported before Android 11
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            EventLog.log("Security: no screen lock on this phone, \"$title\" allowed without confirmation")
            onSuccess()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(getString(R.string.auth_prompt_subtitle))
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }
}
