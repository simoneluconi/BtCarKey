package com.simoneluconi.btcarkey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.simoneluconi.btcarkey.smart.SmartEntry
import com.simoneluconi.btcarkey.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Thresholds in -dBm: a smaller number means closer to the car. */
internal val THRESHOLD_RANGE = 40..100

private enum class Preset(val label: Int, val unlock: Int, val lock: Int) {
    NEAR(R.string.preset_near, 55, 65),
    MEDIUM(R.string.preset_medium, 65, 75),
    FAR(R.string.preset_far, 75, 85),
}

@Composable
fun ComfortScreen() {
    val nav = LocalActions.current
    val app = nav.app
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by app.controller.state.collectAsStateWithLifecycle()
    val keepConnected by app.settings.keepConnected.collectAsStateWithLifecycle()
    val smartSerial by app.smart.enabledSerial.collectAsStateWithLifecycle()
    val ready = state is LinkState.Ready
    var confirmSmartOff by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf<AutoLockConfig?>(null) }
    var saved by remember { mutableStateOf<AutoLockConfig?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var rssi by remember { mutableStateOf<Int?>(null) }

    // Read once when the box is ready
    LaunchedEffect(ready) {
        if (!ready || config != null) return@LaunchedEffect
        loading = true
        app.controller.readAutoLock().onSuccess {
            config = it
            saved = it
        }
        loading = false
    }
    // Live signal as the box measures it, next to the sliders: only while connected and this screen is open
    LaunchedEffect(ready) {
        rssi = null
        if (!ready) return@LaunchedEffect
        while (isActive) {
            app.controller.readRssi().onSuccess { rssi = it }
            delay(LIVE_INTERVAL_MS)
        }
    }

    /** Writes the settings; legacy on turns smart unlock off (never both), once the box took it. */
    fun save(target: AutoLockConfig) {
        saving = true
        scope.launch {
            val result = app.controller.writeAutoLock(target)
            saving = false
            if (result.isSuccess) {
                saved = target
                if (target.enabled) SmartEntry.disable(context)
            }
            nav.message(
                resources.getString(if (result.isSuccess) R.string.saved else R.string.failed),
                if (result.isSuccess) MessageKind.GOOD else MessageKind.BAD,
            )
        }
    }

    ScreenScaffold(stringResource(R.string.comfort_title_legacy)) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Radar, colors.accent)
                Spacer(Modifier.width(12.dp))
                Label(stringResource(R.string.comfort_intro), Modifier.weight(1f), size = 14.sp, color = colors.text2)
            }
            Spacer(Modifier.height(10.dp))
            Label(stringResource(R.string.comfort_legacy_vs_smart), size = 14.sp, color = colors.text2)
            if (smartSerial != null) {
                Spacer(Modifier.height(8.dp))
                Label(stringResource(R.string.comfort_smart_is_on), size = 13.sp, color = colors.warn)
            }
        }

        Card {
            SettingRow(
                title = stringResource(R.string.keep_connected),
                subtitle = stringResource(R.string.keep_connected_hint),
                icon = AppIcons.Bluetooth,
                trailing = {
                    Toggle(keepConnected, { on ->
                        if (on) nav.requestNotifications()
                        if (app.setKeepConnected(on)) nav.message(resources.getString(R.string.keep_connected_smart_off), MessageKind.INFO)
                    })
                },
            )
            if (config?.enabled == true && !keepConnected) {
                Label(stringResource(R.string.comfort_needs_link), size = 13.sp, color = colors.warn)
            }
        }

        val current = config
        when {
            !ready -> Card { Label(stringResource(R.string.device_connect_for_more), color = colors.text2) }
            loading || current == null -> Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    Spacer(Modifier.width(12.dp))
                    Label(stringResource(R.string.comfort_reading), color = colors.text2)
                }
            }
            else -> {
                Card {
                    SettingRow(
                        title = stringResource(R.string.comfort_enable),
                        subtitle = stringResource(R.string.comfort_enable_hint),
                        trailing = { Toggle(current.enabled, { config = current.copy(enabled = it) }) },
                    )
                    Hairline()
                    Spacer(Modifier.height(12.dp))
                    SignalMeter(rssi, current.unlockDbm, current.lockDbm)
                    Spacer(Modifier.height(4.dp))
                    Label(stringResource(R.string.comfort_live_hint), size = 13.sp, color = colors.text2)
                    Spacer(Modifier.height(14.dp))
                    Label(stringResource(R.string.comfort_presets), weight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Preset.entries.forEach { preset ->
                            Chip(stringResource(preset.label), current.unlockDbm == preset.unlock && current.lockDbm == preset.lock) {
                                config = current.copy(unlockDbm = preset.unlock, lockDbm = preset.lock)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    ThresholdSlider(stringResource(R.string.comfort_unlock_at), current.unlockDbm) { value ->
                        config = current.copy(unlockDbm = value, lockDbm = maxOf(current.lockDbm, value + AutoLockConfig.MIN_GAP))
                    }
                    Spacer(Modifier.height(8.dp))
                    ThresholdSlider(stringResource(R.string.comfort_lock_at), current.lockDbm) { value ->
                        config = current.copy(lockDbm = value, unlockDbm = minOf(current.unlockDbm, value - AutoLockConfig.MIN_GAP))
                    }
                    Spacer(Modifier.height(6.dp))
                    Label(stringResource(R.string.comfort_scale_hint), size = 13.sp, color = colors.text2)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(stringResource(R.string.save), icon = AppIcons.Check, style = ButtonStyle.PRIMARY,
                            enabled = current != saved && !saving) {
                            if (current.enabled && smartSerial != null) confirmSmartOff = true else save(current)
                        }
                        PillButton(stringResource(R.string.calibrate_title), icon = AppIcons.Radar) { nav.navigate(Screen.Calibrate) }
                    }
                }

            }
        }
    }

    if (confirmSmartOff) {
        ConfirmDialog(
            title = stringResource(R.string.comfort_title_legacy),
            message = stringResource(R.string.comfort_confirm_smart_off),
            confirm = stringResource(R.string.comfort_confirm_switch),
            cancel = stringResource(R.string.cancel),
            onDismiss = { confirmSmartOff = false },
        ) { config?.let(::save) }
    }
}

@Composable
internal fun ThresholdSlider(label: String, value: Int, range: IntRange = THRESHOLD_RANGE, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.weight(1f), color = LocalAppColors.current.text2)
        Label("-$value dBm", weight = FontWeight.Bold, tabular = true)
    }
    ValueSlider(value, range, onChange)
}

/** A signal level (-dBm) against the unlock and lock thresholds: left is near, right is far. */
@Composable
internal fun SignalMeter(rssi: Int?, unlockDbm: Int, lockDbm: Int) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(rssi?.let { "-$it dBm" } ?: "…", size = 28.sp, weight = FontWeight.ExtraBold, tabular = true)
        Spacer(Modifier.width(12.dp))
        val zone = when {
            rssi == null -> ""
            rssi <= unlockDbm -> stringResource(R.string.zone_unlock)
            rssi >= lockDbm -> stringResource(R.string.zone_lock)
            else -> stringResource(R.string.zone_between)
        }
        Label(zone, color = colors.text2)
    }
    Spacer(Modifier.height(10.dp))
    BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
        val span = (THRESHOLD_RANGE.last - THRESHOLD_RANGE.first).toFloat()
        fun x(v: Int) = maxWidth * ((v - THRESHOLD_RANGE.first) / span).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(8.dp).align(Alignment.Center).clip(CircleShape).background(colors.surface2))
        Box(Modifier.offset(x = x(unlockDbm)).width(2.dp).height(22.dp).background(colors.good))
        Box(Modifier.offset(x = x(lockDbm)).width(2.dp).height(22.dp).background(colors.warn))
        if (rssi != null) {
            Box(Modifier.offset(x = x(rssi) - 8.dp).size(16.dp).align(Alignment.CenterStart).clip(CircleShape).background(colors.accent))
        }
    }
}

/**
 * Guided calibration, as in the official app: samples the signal next to the car (unlock) and
 * where the car should lock, then proposes thresholds from the trimmed means.
 */
@Composable
fun CalibrateScreen() {
    val nav = LocalActions.current
    val app = nav.app
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by app.controller.state.collectAsStateWithLifecycle()
    val smartSerial by app.smart.enabledSerial.collectAsStateWithLifecycle()
    val ready = state is LinkState.Ready
    var step by remember { mutableIntStateOf(0) }
    var measuring by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var lastRssi by remember { mutableStateOf<Int?>(null) }
    val unlockSamples = remember { mutableStateListOf<Int>() }
    val lockSamples = remember { mutableStateListOf<Int>() }

    suspend fun measure(into: MutableList<Int>) {
        into.clear()
        measuring = true
        repeat(SAMPLES) { i ->
            app.controller.readRssi().onSuccess {
                into += it
                lastRssi = it
            }
            progress = (i + 1) * 100 / SAMPLES
            delay(SAMPLE_INTERVAL_MS)
        }
        measuring = false
    }

    ScreenScaffold(stringResource(R.string.calibrate_title)) {
        if (!ready) {
            Card { Label(stringResource(R.string.device_connect_for_more), color = colors.text2) }
            return@ScreenScaffold
        }
        val (title, text) = when (step) {
            0 -> R.string.cal_step0_title to R.string.cal_step0_text
            1 -> R.string.cal_step1_title to R.string.cal_step1_text
            2 -> R.string.cal_step2_title to R.string.cal_step2_text
            else -> R.string.cal_step3_title to R.string.cal_step3_text
        }
        Card {
            Label(stringResource(R.string.cal_step_n, minOf(step + 1, 4), 4), size = 13.sp, color = colors.text2)
            Spacer(Modifier.height(4.dp))
            Label(stringResource(title), size = 21.sp, weight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Label(stringResource(text), color = colors.text2)
            if (measuring) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(colors.surface2)) {
                    Box(Modifier.fillMaxWidth(progress / 100f).height(8.dp).clip(CircleShape).background(colors.accent))
                }
                Spacer(Modifier.height(8.dp))
                Label(stringResource(R.string.cal_measuring, lastRssi?.let { "-$it dBm" } ?: "…"), color = colors.text2, tabular = true)
            }
        }
        if (step == 3) {
            val result = AutoLockConfig.calibrate(unlockSamples, lockSamples)
            Card {
                InfoRow(stringResource(R.string.comfort_unlock_at), "-${result.unlockDbm} dBm")
                Hairline()
                InfoRow(stringResource(R.string.comfort_lock_at), "-${result.lockDbm} dBm")
            }
            if (smartSerial != null) {
                Label(stringResource(R.string.comfort_confirm_smart_off), size = 13.sp, color = colors.warn)
            }
            PillButton(stringResource(R.string.cal_apply), icon = AppIcons.Check, style = ButtonStyle.PRIMARY) {
                scope.launch {
                    val written = app.controller.writeAutoLock(result)
                    // Legacy comfort entry and smart unlock are never on together
                    if (written.isSuccess && result.enabled) SmartEntry.disable(context)
                    nav.message(
                        resources.getString(if (written.isSuccess) R.string.saved else R.string.failed),
                        if (written.isSuccess) MessageKind.GOOD else MessageKind.BAD,
                    )
                    if (written.isSuccess) nav.back()
                }
            }
        } else if (!measuring) {
            PillButton(stringResource(if (step == 0) R.string.cal_start else R.string.cal_measure), icon = AppIcons.Radar, style = ButtonStyle.PRIMARY) {
                when (step) {
                    0 -> step = 1
                    1 -> scope.launch {
                        measure(unlockSamples)
                        step = 2
                    }
                    2 -> scope.launch {
                        measure(lockSamples)
                        step = 3
                    }
                }
            }
        }
    }
}

private const val LIVE_INTERVAL_MS = 1_500L
private const val SAMPLES = 30
private const val SAMPLE_INTERVAL_MS = 1_000L
