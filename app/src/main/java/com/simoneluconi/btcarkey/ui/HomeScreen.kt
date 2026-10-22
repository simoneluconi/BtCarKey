package com.simoneluconi.btcarkey.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.btcarkey.R
import com.simoneluconi.btcarkey.auth.AuthStep
import com.simoneluconi.btcarkey.keys.SavedDevice
import com.simoneluconi.btcarkey.link.CommandResult
import com.simoneluconi.btcarkey.link.LinkState
import com.simoneluconi.btcarkey.link.ProblemKind
import com.simoneluconi.btcarkey.protocol.BoxStatus
import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.protocol.Feature
import com.simoneluconi.btcarkey.protocol.KeyProtocol
import com.simoneluconi.btcarkey.protocol.RemoteButton
import com.simoneluconi.btcarkey.settings.ActionConfig
import com.simoneluconi.btcarkey.settings.HomeAction
import com.simoneluconi.btcarkey.ui.theme.LocalAppColors

@Composable
fun HomeScreen() {
    val nav = LocalActions.current
    val app = nav.app
    val context = LocalContext.current
    val haptic = rememberHaptics()
    val state by app.controller.state.collectAsStateWithLifecycle()
    val status by app.controller.status.collectAsStateWithLifecycle()
    val busy by app.controller.busy.collectAsStateWithLifecycle()
    val devices by app.store.devices.collectAsStateWithLifecycle()
    val selected by app.store.selected.collectAsStateWithLifecycle()
    val homeActions by app.settings.homeActions.collectAsStateWithLifecycle()
    val confirm by app.settings.confirm.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.serial == selected }

    LaunchedEffect(Unit) {
        app.controller.results.collect { result ->
            val name = context.getString(commandLabel(result.command))
            when (result) {
                is CommandResult.Done -> {
                    haptic(Haptic.CONFIRM)
                    nav.message(context.getString(R.string.result_done, name), MessageKind.GOOD)
                }
                is CommandResult.Unconfirmed -> nav.message(context.getString(R.string.result_unconfirmed, name), MessageKind.INFO)
                is CommandResult.Failed -> {
                    haptic(Haptic.REJECT)
                    nav.message(context.getString(R.string.result_failed, name, result.reason), MessageKind.BAD)
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(device, onCar = { nav.navigate(Screen.Garage) }, onSettings = { nav.navigate(Screen.Settings) })
        if (device == null) {
            WelcomeCard { nav.navigate(Screen.AddDevice) }
            return@Column
        }
        StatusCard(state, status)
        val ready = state is LinkState.Ready
        // Only what this device's protocol can do
        val protocol = app.controller.protocolOf(device)
        ActionGrid(homeActions.filter { protocol == null || it.supportedBy(protocol) }, confirm, ready, busy)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (protocol == null || Feature.PROXIMITY in protocol.features) {
                QuickLink(AppIcons.Radar, stringResource(R.string.comfort_title), Modifier.weight(1f)) { nav.navigate(Screen.Comfort) }
            }
            QuickLink(AppIcons.Fob, stringResource(R.string.device_title), Modifier.weight(1f)) { nav.navigate(Screen.Device(device.serial)) }
        }
    }
}

@Composable
private fun Header(device: SavedDevice?, onCar: () -> Unit, onSettings: () -> Unit) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // Rounded corners, not a pill: on two lines of text a pill clip would cut the label's corner
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .pressable(label = stringResource(R.string.garage_title), onClick = onCar)
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Label(stringResource(R.string.home_your_car), size = 14.sp, color = colors.text2)
                Label(device?.name ?: stringResource(R.string.app_name), size = 28.sp, weight = FontWeight.ExtraBold, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            Icon(AppIcons.Down, colors.text2, size = 22.dp, modifier = Modifier.padding(top = 18.dp))
        }
        RoundIconButton(AppIcons.Settings, stringResource(R.string.settings_title), onClick = onSettings)
    }
}

@Composable
private fun WelcomeCard(onAdd: () -> Unit) {
    val colors = LocalAppColors.current
    Card {
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Fob, colors.accent, size = 32.dp)
        }
        Spacer(Modifier.height(14.dp))
        Label(stringResource(R.string.welcome_title), size = 22.sp, weight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Label(stringResource(R.string.welcome_text), color = colors.text2)
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.garage_add), icon = AppIcons.Plus, style = ButtonStyle.PRIMARY, onClick = onAdd)
    }
}

/** Where the link stands: a headline, the four steps, battery, and the way out of a problem. */
@Composable
private fun StatusCard(state: LinkState, status: BoxStatus) {
    val colors = LocalAppColors.current
    val nav = LocalActions.current
    val app = nav.app
    val tone = when (state) {
        is LinkState.Ready -> colors.good
        is LinkState.Problem, LinkState.BluetoothOff, LinkState.NeedsPermission -> colors.warn
        else -> colors.accent
    }
    val toneAnimated by animateColorAsState(tone, tween(300), label = "tone")
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(colors.soft(toneAnimated)), contentAlignment = Alignment.Center) {
                when (state) {
                    is LinkState.Locating, is LinkState.Connecting, is LinkState.Authenticating -> Spinner(toneAnimated, 24.dp)
                    is LinkState.Ready -> Icon(AppIcons.Check, toneAnimated)
                    is LinkState.Problem, LinkState.BluetoothOff, LinkState.NeedsPermission -> Icon(AppIcons.Warning, toneAnimated)
                    else -> Icon(AppIcons.Bluetooth, toneAnimated)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(stateTitle(state), size = 19.sp, weight = FontWeight.ExtraBold)
                Label(stateDetail(state), size = 14.sp, color = colors.text2)
            }
            status.batteryPercent?.let { BatteryBadge(it) }
        }
        Spacer(Modifier.height(16.dp))
        Steps(state)
        when (state) {
            LinkState.NeedsPermission -> ActionLine(stringResource(R.string.action_grant), AppIcons.Shield, nav.requestPermissions)
            LinkState.BluetoothOff -> ActionLine(stringResource(R.string.action_turn_on_bt), AppIcons.Bluetooth, nav.enableBluetooth)
            is LinkState.Problem -> {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(R.string.action_retry), icon = AppIcons.Refresh, style = ButtonStyle.PRIMARY) { app.controller.retry() }
                    if (state.kind == ProblemKind.KEY_MISSING || state.kind == ProblemKind.KEY_REJECTED) {
                        state.serial?.let { serial ->
                            PillButton(stringResource(R.string.key_import), icon = AppIcons.Key) { nav.navigate(Screen.Device(serial)) }
                        }
                    }
                }
            }
            is LinkState.Ready -> if (state.newPairing) {
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.state_new_pairing), size = 14.sp, color = colors.good)
            }
            else -> Unit
        }
    }
}

@Composable
private fun ActionLine(text: String, icon: ImageVector, onClick: () -> Unit) {
    Spacer(Modifier.height(14.dp))
    PillButton(text, icon = icon, style = ButtonStyle.PRIMARY, onClick = onClick)
}

@Composable
private fun BatteryBadge(percent: Int) {
    val colors = LocalAppColors.current
    val tint = when {
        percent <= 15 -> colors.bad
        percent <= 30 -> colors.warn
        else -> colors.text
    }
    Row(
        Modifier.clip(CircleShape).background(colors.surface2).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Battery, tint, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Label("$percent%", color = tint, weight = FontWeight.Bold, tabular = true)
    }
}

/** Search → connect → verify → ready, with the current one highlighted. */
@Composable
private fun Steps(state: LinkState) {
    val colors = LocalAppColors.current
    val current = when (state) {
        is LinkState.Locating -> 0
        is LinkState.Connecting -> 1
        is LinkState.Authenticating -> 2
        is LinkState.Ready -> 4
        else -> -1
    }
    val labels = listOf(R.string.step_search, R.string.step_connect, R.string.step_verify, R.string.step_ready)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val done = index < current || (index == 3 && current == 4)
            val active = index == current
            val color = when {
                done -> colors.good
                active -> colors.accent
                else -> colors.line
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(color))
                Spacer(Modifier.height(6.dp))
                Label(stringResource(label), size = 12.sp, color = if (done || active) colors.text else colors.text2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun stateTitle(state: LinkState): String = stringResource(
    when (state) {
        LinkState.NoDevice -> R.string.state_no_device
        LinkState.Idle -> R.string.state_idle
        LinkState.BluetoothOff -> R.string.state_bluetooth_off
        LinkState.NeedsPermission -> R.string.state_permission
        is LinkState.Locating -> R.string.state_locating
        is LinkState.Connecting -> if (state.waiting) R.string.state_waiting else R.string.state_connecting
        is LinkState.Authenticating -> R.string.state_authenticating
        is LinkState.Ready -> R.string.state_ready
        is LinkState.Problem -> when (state.kind) {
            ProblemKind.KEY_MISSING -> R.string.problem_key_missing
            ProblemKind.KEY_REJECTED -> R.string.problem_key_rejected
            ProblemKind.PAIRING_FAILED -> R.string.problem_pairing
            ProblemKind.NOT_SUPPORTED -> R.string.problem_not_supported
            ProblemKind.NOT_FOUND -> R.string.problem_not_found
            ProblemKind.PROTOCOL -> R.string.problem_protocol
        }
    }
)

@Composable
private fun stateDetail(state: LinkState): String = when (state) {
    is LinkState.Connecting -> stringResource(if (state.waiting) R.string.state_waiting_detail else R.string.state_connecting_detail)
    is LinkState.Authenticating -> stringResource(
        when (state.step) {
            AuthStep.HELLO -> R.string.auth_hello
            AuthStep.VERIFYING -> R.string.auth_verifying
            AuthStep.PAIRING -> R.string.auth_pairing
            AuthStep.UNBINDING -> R.string.auth_unbinding
        }
    )
    is LinkState.Ready -> state.info.firmware?.let { stringResource(R.string.state_ready_detail_fw, state.protocol.displayName, it) }
        ?: stringResource(R.string.state_ready_detail, state.protocol.displayName)
    is LinkState.Problem -> state.detail
    LinkState.Idle -> stringResource(R.string.state_idle_detail)
    LinkState.BluetoothOff -> stringResource(R.string.state_bluetooth_off_detail)
    LinkState.NeedsPermission -> stringResource(R.string.state_permission_detail)
    is LinkState.Locating -> stringResource(R.string.state_locating_detail)
    LinkState.NoDevice -> ""
}

@Composable
private fun ActionGrid(actions: List<HomeAction>, confirm: Set<HomeAction>, ready: Boolean, busy: CarCommand?) {
    actions.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { action ->
                ActionTile(action, needsHold = action in confirm, ready = ready, busy = busy != null && action.command == busy, Modifier.weight(1f))
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionTile(action: HomeAction, needsHold: Boolean, ready: Boolean, busy: Boolean, modifier: Modifier) {
    val colors = LocalAppColors.current
    val nav = LocalActions.current
    val context = LocalContext.current
    val controller = nav.app.controller
    val configs by nav.app.settings.actionConfigs.collectAsStateWithLifecycle()
    val config = configs[action] ?: ActionConfig()
    val label = stringResource(actionLabel(action))
    val hold = action.holdButton
    val notReady = { nav.message(context.getString(R.string.not_connected), MessageKind.INFO) }
    val run = { controller.send(action.command!!, config) }
    // Same gesture modifier whatever the link state (the state is checked inside): swapping
    // modifiers in the chain when the box connected left the tiles without their background
    val gesture = if (hold != null) {
        Modifier.holdable(
            onDown = { if (ready) controller.press(hold, true) else notReady() },
            onUp = { if (ready) controller.press(hold, false) },
        )
    } else {
        Modifier.pressable(
            label = label,
            onLongClick = if (needsHold) ({ if (ready) run() else notReady() }) else null,
            onClick = {
                when {
                    !ready -> notReady()
                    needsHold -> nav.message(context.getString(R.string.hold_to_confirm), MessageKind.INFO)
                    else -> run()
                }
            },
        )
    }
    val bg = if (busy) colors.accentSoft else colors.surface
    // The background lives on its own node, apart from the gesture and fade layers
    Box(modifier.height(132.dp).clip(TileShape).background(bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .then(gesture)
                .alpha(if (ready) 1f else 0.55f)
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                if (busy) Spinner(colors.accent, 22.dp) else Icon(actionIcon(action), colors.accent)
            }
            Column {
                Label(label, size = 18.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                val hint = when {
                    hold != null -> stringResource(R.string.hint_hold_button)
                    config.method == ActionConfig.Method.REMOTE -> describe(config) +
                        if (needsHold) " · " + stringResource(R.string.hint_long_press) else ""
                    needsHold -> stringResource(R.string.hint_long_press)
                    else -> null
                }
                if (hint != null) Label(hint, size = 13.sp, color = colors.text2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun QuickLink(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier
            .clip(TileShape)
            .background(colors.surface)
            .pressable(label = text, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, colors.text2, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Label(text, Modifier.weight(1f), weight = FontWeight.Bold, maxLines = 1)
        Icon(AppIcons.Chevron, colors.text2, size = 18.dp)
    }
}

/** "Trunk button ×2 · 2 s": how a remote-button action is sent, in a few words. */
@Composable
fun describe(config: ActionConfig): String {
    val button = stringResource(remoteButtonLabel(config.button))
    val presses = if (config.presses > 1) " ×${config.presses}" else ""
    val length = if (config.holdMs > ActionConfig.SHORT_PRESS_MS) " · %.1f s".format(config.holdMs / 1000f) else ""
    return button + presses + length
}

fun remoteButtonLabel(button: RemoteButton): Int = when (button) {
    RemoteButton.UNLOCK -> R.string.remote_unlock
    RemoteButton.LOCK -> R.string.remote_lock
    RemoteButton.TRUNK -> R.string.remote_trunk
    RemoteButton.FUNCTION -> R.string.remote_function
}

/** The one-tap command behind an action, null for hold buttons. */
val HomeAction.command: CarCommand?
    get() = when (this) {
        HomeAction.UNLOCK -> CarCommand.UNLOCK
        HomeAction.LOCK -> CarCommand.LOCK
        HomeAction.TRUNK -> CarCommand.TRUNK
        HomeAction.REMOTE_START -> CarCommand.REMOTE_START
        HomeAction.DROP_WINDOWS -> CarCommand.DROP_WINDOWS
        else -> null
    }

val HomeAction.holdButton: RemoteButton?
    get() = when (this) {
        HomeAction.HOLD_UNLOCK -> RemoteButton.UNLOCK
        HomeAction.HOLD_LOCK -> RemoteButton.LOCK
        HomeAction.HOLD_TRUNK -> RemoteButton.TRUNK
        HomeAction.HOLD_FUNCTION -> RemoteButton.FUNCTION
        else -> null
    }

/** Whether [protocol] can run this action: its command, or the remote button it holds. */
fun HomeAction.supportedBy(protocol: KeyProtocol): Boolean {
    command?.let { return it in protocol.commands }
    val button = holdButton ?: return false
    return Feature.HOLD_BUTTONS in protocol.features && button in protocol.buttons
}

fun actionLabel(action: HomeAction): Int = when (action) {
    HomeAction.UNLOCK -> R.string.action_unlock
    HomeAction.LOCK -> R.string.action_lock
    HomeAction.TRUNK -> R.string.action_trunk
    HomeAction.REMOTE_START -> R.string.action_remote_start
    HomeAction.DROP_WINDOWS -> R.string.action_windows
    HomeAction.HOLD_UNLOCK -> R.string.action_hold_unlock
    HomeAction.HOLD_LOCK -> R.string.action_hold_lock
    HomeAction.HOLD_TRUNK -> R.string.action_hold_trunk
    HomeAction.HOLD_FUNCTION -> R.string.action_hold_function
}

fun actionIcon(action: HomeAction): ImageVector = when (action) {
    HomeAction.UNLOCK, HomeAction.HOLD_UNLOCK -> AppIcons.Unlock
    HomeAction.LOCK, HomeAction.HOLD_LOCK -> AppIcons.Lock
    HomeAction.TRUNK, HomeAction.HOLD_TRUNK -> AppIcons.Trunk
    HomeAction.REMOTE_START -> AppIcons.Power
    HomeAction.DROP_WINDOWS -> AppIcons.Window
    HomeAction.HOLD_FUNCTION -> AppIcons.Star
}

fun commandLabel(command: CarCommand): Int = when (command) {
    CarCommand.UNLOCK -> R.string.action_unlock
    CarCommand.LOCK -> R.string.action_lock
    CarCommand.TRUNK -> R.string.action_trunk
    CarCommand.REMOTE_START -> R.string.action_remote_start
    CarCommand.DROP_WINDOWS -> R.string.action_windows
}
