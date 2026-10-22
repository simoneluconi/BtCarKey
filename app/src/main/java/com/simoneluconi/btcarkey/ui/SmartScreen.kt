package com.simoneluconi.btcarkey.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.btcarkey.R
import com.simoneluconi.btcarkey.link.LinkState
import com.simoneluconi.btcarkey.protocol.AutoLockConfig
import com.simoneluconi.btcarkey.protocol.Feature
import com.simoneluconi.btcarkey.protocol.canSmartUnlock
import com.simoneluconi.btcarkey.smart.CompanionLink
import com.simoneluconi.btcarkey.smart.SmartEntry
import com.simoneluconi.btcarkey.smart.SmartEntryLogic
import com.simoneluconi.btcarkey.smart.SmartStatus
import com.simoneluconi.btcarkey.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Smart unlock: the system tells the app when the car is in range, the phone measures the signal and
 * unlocks/locks. Turning it on associates the car (a system dialog, once) and turns legacy comfort
 * entry off: the two are never on together.
 */
@Composable
fun SmartScreen() {
    val nav = LocalActions.current
    val app = nav.app
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by app.controller.state.collectAsStateWithLifecycle()
    val devices by app.store.devices.collectAsStateWithLifecycle()
    val selected by app.store.selected.collectAsStateWithLifecycle()
    val enabledSerial by app.smart.enabledSerial.collectAsStateWithLifecycle()
    val status by SmartEntry.status.collectAsStateWithLifecycle()
    val unlockDbm by app.smart.unlockDbm.collectAsStateWithLifecycle()
    val lockDbm by app.smart.lockDbm.collectAsStateWithLifecycle()
    val requireUnlocked by app.smart.requirePhoneUnlocked.collectAsStateWithLifecycle()
    val cooldown by app.smart.cooldown.collectAsStateWithLifecycle()
    val confirmLock by app.smart.confirmLock.collectAsStateWithLifecycle()
    val returnWindow by app.smart.returnWindowMin.collectAsStateWithLifecycle()
    val legacy by app.controller.legacyAutoLock.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.serial == selected }
    val protocol = device?.let(app.controller::protocolOf)
    val ready = (state as? LinkState.Ready)?.takeIf { it.serial == device?.serial }
    val enabled = enabledSerial != null && enabledSerial == device?.serial
    var working by remember { mutableStateOf(false) }
    var legacyToTurnOff by remember { mutableStateOf<AutoLockConfig?>(null) }
    var level by remember { mutableStateOf<Int?>(null) }
    // Checked again each time the screen comes back (e.g. from the system's notification settings)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val notificationsOn = remember(lifecycle) { NotificationManagerCompat.from(context).areNotificationsEnabled() }

    // Live signal as the phone measures it, next to the sliders: only while connected and this screen is open
    LaunchedEffect(ready != null) {
        level = null
        if (ready == null) return@LaunchedEffect
        while (isActive) {
            level = app.controller.readLinkRssi()?.let { -it }
            delay(LIVE_MS)
        }
    }

    fun message(id: Int, kind: MessageKind) = nav.message(resources.getString(id), kind)

    /** Last step: associate (system dialog, once per car), then start watching for it. */
    fun associateAndEnable() {
        val serial = device?.serial ?: return
        val mac = device.mac ?: return
        val finish = {
            working = false
            if (SmartEntry.enable(context, serial, mac)) {
                message(R.string.smart_turned_on, MessageKind.GOOD)
                // The safety alerts (cooldown, car maybe left unlocked) need it; asked after the association dialog
                nav.requestNotifications()
            } else {
                message(R.string.smart_failed, MessageKind.BAD)
            }
        }
        if (CompanionLink.isAssociated(context, mac)) {
            finish()
        } else {
            nav.associate(mac) { ok ->
                if (ok) finish() else {
                    working = false
                    message(R.string.smart_association_failed, MessageKind.BAD)
                }
            }
        }
    }

    /** Turning on: legacy comfort entry off first (never both), then the association. */
    fun turnOn() {
        working = true
        scope.launch {
            val legacy = if (protocol != null && Feature.PROXIMITY in protocol.features) app.controller.readAutoLock().getOrNull() else null
            if (legacy?.enabled == true) {
                working = false
                legacyToTurnOff = legacy
            } else {
                associateAndEnable()
            }
        }
    }

    ScreenScaffold(stringResource(R.string.smart_title)) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Radar, colors.accent)
                Spacer(Modifier.width(12.dp))
                Label(stringResource(R.string.smart_intro), Modifier.weight(1f), size = 14.sp, color = colors.text2)
            }
        }

        if (device == null) return@ScreenScaffold
        val canSmart = protocol?.canSmartUnlock == true
        Card {
            SettingRow(
                title = stringResource(R.string.smart_enable),
                subtitle = statusText(if (enabled) status else SmartStatus.Off),
                icon = AppIcons.Key,
                iconTint = if (enabled) colors.good else colors.text2,
                trailing = {
                    if (working) Spinner() else Toggle(
                        checked = enabled,
                        enabled = enabled || (ready != null && device.mac != null && CompanionLink.supported && canSmart),
                        onChange = { on -> if (on) turnOn() else SmartEntry.disable(context) },
                    )
                },
            )
            val requirement = when {
                // While turning on the app lets go of the box on purpose (association)
                enabled || working -> null
                !CompanionLink.supported -> R.string.smart_needs_android12
                !canSmart -> R.string.smart_not_supported
                ready == null -> R.string.smart_needs_connection
                else -> null
            }
            if (requirement != null) Label(stringResource(requirement), size = 13.sp, color = colors.warn)
            // The safety alerts (car maybe left unlocked, suspended, paused) are notifications
            if (enabled && !notificationsOn) {
                Spacer(Modifier.height(8.dp))
                Label(stringResource(R.string.smart_notifications_off), size = 13.sp, color = colors.warn)
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.smart_notifications_turn_on), icon = AppIcons.Warning) {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            }
            if ((status is SmartStatus.Suspended || status == SmartStatus.Paused) && enabled) {
                Spacer(Modifier.height(10.dp))
                PillButton(stringResource(R.string.smart_resume), icon = AppIcons.Refresh, style = ButtonStyle.PRIMARY) {
                    SmartEntry.resume(context)
                }
            }
        }

        // Read from the box on each connection: legacy still on (e.g. set by the vendor app)
        val legacyOn = legacy
        if (ready != null && legacyOn?.enabled == true) {
            Card(color = colors.soft(colors.warn)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Warning, colors.warn)
                    Spacer(Modifier.width(12.dp))
                    Label(stringResource(R.string.smart_legacy_on), Modifier.weight(1f), weight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Label(stringResource(R.string.smart_legacy_on_hint), size = 14.sp, color = colors.text2)
                Spacer(Modifier.height(10.dp))
                PillButton(stringResource(R.string.smart_legacy_turn_off), icon = AppIcons.Close, enabled = !working) {
                    working = true
                    scope.launch {
                        val result = app.controller.writeAutoLock(legacyOn.copy(enabled = false))
                        working = false
                        message(if (result.isSuccess) R.string.smart_legacy_turned_off else R.string.failed,
                            if (result.isSuccess) MessageKind.GOOD else MessageKind.BAD)
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.smart_distances))
        Card {
            val live = level?.takeIf { ready != null }
            if (ready != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(live?.let { "-$it dBm" } ?: "…", size = 28.sp, weight = FontWeight.ExtraBold, tabular = true)
                    Spacer(Modifier.width(12.dp))
                    val zone = when {
                        live == null -> null
                        live <= unlockDbm -> R.string.zone_unlock
                        live >= lockDbm -> R.string.zone_lock
                        else -> R.string.zone_between
                    }
                    if (zone != null) Label(stringResource(zone), color = colors.text2)
                }
                Spacer(Modifier.height(10.dp))
            }
            // The zones follow the sliders; the phone's live signal moves along them
            DistanceMap(unlockDbm, lockDbm, live?.let { listOf(MapPin(it, colors.accent, stringResource(R.string.smartcal_you))) }.orEmpty())
            Spacer(Modifier.height(4.dp))
            Label(stringResource(if (ready != null) R.string.smart_live_hint else R.string.live_needs_connection), size = 13.sp, color = colors.text2)
            Spacer(Modifier.height(14.dp))
            // Bounded ranges: unlock only close to the car, lock while the link is still there
            ThresholdSlider(stringResource(R.string.comfort_unlock_at), unlockDbm, SmartEntryLogic.UNLOCK_RANGE) { value ->
                app.smart.setThresholds(value, maxOf(lockDbm, value + SmartEntryLogic.MIN_GAP))
            }
            Spacer(Modifier.height(8.dp))
            ThresholdSlider(stringResource(R.string.comfort_lock_at), lockDbm, SmartEntryLogic.LOCK_RANGE) { value ->
                app.smart.setThresholds(minOf(unlockDbm, value - SmartEntryLogic.MIN_GAP), value)
            }
            Spacer(Modifier.height(6.dp))
            Label(stringResource(R.string.smart_scale_hint), size = 13.sp, color = colors.text2)
            Spacer(Modifier.height(4.dp))
            Label(stringResource(R.string.smart_thresholds_hint), size = 13.sp, color = colors.text2)
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.smartcal_title), Modifier.fillMaxWidth(), icon = AppIcons.Radar,
                style = ButtonStyle.PRIMARY, enabled = ready != null) { nav.navigate(Screen.SmartCalibrate) }
            Spacer(Modifier.height(10.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))
            Label(stringResource(R.string.smart_return_window), weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Label(stringResource(R.string.smart_return_window_hint), size = 13.sp, color = colors.text2)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartEntryLogic.RETURN_WINDOW_CHOICES.forEach { minutes ->
                    Chip(stringResource(R.string.smart_minutes, minutes), returnWindow == minutes) { app.smart.setReturnWindowMin(minutes) }
                }
            }
        }

        SectionTitle(stringResource(R.string.smart_safety))
        Card {
            SettingRow(
                title = stringResource(R.string.smart_require_unlocked),
                subtitle = stringResource(R.string.smart_require_unlocked_hint),
                icon = AppIcons.Shield,
                trailing = { Toggle(requireUnlocked, app.smart::setRequirePhoneUnlocked) },
            )
            Hairline()
            SettingRow(
                title = stringResource(R.string.smart_confirm_lock),
                subtitle = stringResource(R.string.smart_confirm_lock_hint),
                icon = AppIcons.Lock,
                trailing = { Toggle(confirmLock, app.smart::setConfirmLock) },
            )
            Hairline()
            SettingRow(
                title = stringResource(R.string.smart_cooldown),
                subtitle = stringResource(R.string.smart_cooldown_hint),
                icon = AppIcons.Refresh,
                trailing = { Toggle(cooldown, app.smart::setCooldown) },
            )
        }

        if (protocol != null && Feature.PROXIMITY in protocol.features) {
            SectionTitle(stringResource(R.string.smart_other_mode))
            Card {
                Label(stringResource(R.string.smart_vs_legacy), size = 14.sp, color = colors.text2)
                Spacer(Modifier.height(6.dp))
                SettingRow(
                    title = stringResource(R.string.comfort_title_legacy),
                    subtitle = stringResource(R.string.smart_legacy_hint),
                    icon = AppIcons.Bluetooth,
                    onClick = { nav.navigate(Screen.Comfort) },
                )
            }
        }
    }

    legacyToTurnOff?.let { legacy ->
        ConfirmDialog(
            title = stringResource(R.string.smart_title),
            message = stringResource(R.string.smart_confirm_legacy_off),
            confirm = stringResource(R.string.comfort_confirm_switch),
            cancel = stringResource(R.string.cancel),
            onDismiss = { legacyToTurnOff = null },
        ) {
            working = true
            scope.launch {
                if (app.controller.writeAutoLock(legacy.copy(enabled = false)).isSuccess) {
                    message(R.string.smart_legacy_turned_off, MessageKind.INFO)
                    associateAndEnable()
                } else {
                    working = false
                    message(R.string.failed, MessageKind.BAD)
                }
            }
        }
    }
}

@Composable
private fun statusText(status: SmartStatus): String = when (status) {
    SmartStatus.Off -> stringResource(R.string.smart_status_off)
    SmartStatus.Watching -> stringResource(R.string.smart_status_watching)
    is SmartStatus.Near -> when {
        status.phase == SmartEntryLogic.Phase.UNLOCKED -> stringResource(R.string.smart_status_unlocked)
        status.phase == SmartEntryLogic.Phase.LOCKED_NEARBY -> stringResource(R.string.smart_status_locked_nearby)
        status.level != null -> stringResource(R.string.smart_status_near_level, status.level)
        else -> stringResource(R.string.smart_status_near)
    }
    SmartStatus.Resting -> stringResource(R.string.smart_status_resting)
    SmartStatus.Paused -> stringResource(R.string.smart_status_paused)
    is SmartStatus.Suspended -> stringResource(
        R.string.smart_status_suspended,
        DateFormat.getTimeFormat(LocalContext.current).format(Date(status.until)),
    )
}

private const val LIVE_MS = 1_000L
