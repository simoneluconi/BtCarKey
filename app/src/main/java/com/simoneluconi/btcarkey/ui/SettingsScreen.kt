package com.simoneluconi.btcarkey.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.btcarkey.BuildConfig
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.R
import com.simoneluconi.btcarkey.link.KeepConnectedService
import com.simoneluconi.btcarkey.settings.Accent
import com.simoneluconi.btcarkey.settings.ActionConfig
import com.simoneluconi.btcarkey.settings.HomeAction
import com.simoneluconi.btcarkey.protocol.RemoteButton
import com.simoneluconi.btcarkey.settings.ThemeMode
import com.simoneluconi.btcarkey.ui.theme.LocalAppColors

@Composable
fun SettingsScreen() {
    val nav = LocalActions.current
    val app = nav.app
    val settings = app.settings
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val theme by settings.theme.collectAsStateWithLifecycle()
    val accent by settings.accent.collectAsStateWithLifecycle()
    val actions by settings.homeActions.collectAsStateWithLifecycle()
    val confirm by settings.confirm.collectAsStateWithLifecycle()
    val haptics by settings.haptics.collectAsStateWithLifecycle()
    val keepConnected by settings.keepConnected.collectAsStateWithLifecycle()
    val configs by settings.actionConfigs.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<HomeAction?>(null) }

    ScreenScaffold(stringResource(R.string.settings_title)) {
        SectionTitle(stringResource(R.string.settings_appearance))
        Card {
            Label(stringResource(R.string.settings_theme), weight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                ).forEach { (mode, label) -> Chip(stringResource(label), theme == mode) { settings.setTheme(mode) } }
            }
            Spacer(Modifier.height(16.dp))
            Label(stringResource(R.string.settings_accent), weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Accent.entries.forEach { option ->
                    val color = Color(if (colors.dark) option.night else option.day)
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(if (option == accent) Modifier.border(3.dp, colors.text, CircleShape) else Modifier)
                            .pressable(label = option.name) { settings.setAccent(option) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (option == accent) Icon(AppIcons.Check, colors.onAccent, size = 18.dp)
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_buttons))
        Card {
            Label(stringResource(R.string.settings_buttons_hint), size = 14.sp, color = colors.text2)
            Spacer(Modifier.height(6.dp))
            val ordered = actions + HomeAction.entries.filter { it !in actions }
            ordered.forEachIndexed { index, action ->
                if (index > 0) Hairline()
                HomeActionRow(
                    action = action,
                    shown = action in actions,
                    needsHold = action in confirm,
                    config = configs[action] ?: ActionConfig(),
                    canMoveUp = action in actions && actions.indexOf(action) > 0,
                    canMoveDown = action in actions && actions.indexOf(action) < actions.lastIndex,
                    onShown = { show -> settings.setHomeActions(if (show) actions + action else actions - action) },
                    onEdit = { editing = action },
                    onMove = { delta ->
                        val list = actions.toMutableList()
                        val from = list.indexOf(action)
                        val to = (from + delta).coerceIn(0, list.lastIndex)
                        list.add(to, list.removeAt(from))
                        settings.setHomeActions(list)
                    },
                )
            }
        }

        SectionTitle(stringResource(R.string.settings_behaviour))
        Card {
            SettingRow(
                title = stringResource(R.string.settings_haptics),
                icon = AppIcons.Hand,
                trailing = { Toggle(haptics, settings::setHaptics) },
            )
            Hairline()
            SettingRow(
                title = stringResource(R.string.keep_connected),
                subtitle = stringResource(R.string.keep_connected_hint),
                icon = AppIcons.Bluetooth,
                trailing = {
                    Toggle(keepConnected, { on ->
                        settings.setKeepConnected(on)
                        if (on) nav.requestNotifications()
                        app.syncKeepConnected()
                    })
                },
            )
            if (keepConnected) {
                Hairline()
                SettingRow(
                    title = stringResource(R.string.settings_notification),
                    subtitle = stringResource(R.string.settings_notification_hint),
                    icon = AppIcons.Info,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, KeepConnectedService.CHANNEL_ID)
                        )
                    },
                )
            }
        }

        SectionTitle(stringResource(R.string.settings_more))
        Card {
            SettingRow(title = stringResource(R.string.garage_title), icon = AppIcons.Car, onClick = { nav.navigate(Screen.Garage) })
            Hairline()
            SettingRow(title = stringResource(R.string.log_title), subtitle = stringResource(R.string.log_hint), icon = AppIcons.List,
                onClick = { nav.navigate(Screen.Log) })
            Hairline()
            InfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
        }
    }

    editing?.let { action ->
        ActionConfigDialog(
            action = action,
            initial = configs[action] ?: ActionConfig(),
            initialNeedsHold = action in confirm,
            onDismiss = { editing = null },
        ) { config, needsHold ->
            settings.setActionConfig(action, config)
            settings.setConfirm(action, needsHold)
        }
    }
}

/** How a one-tap action reaches the car: the box's command, or the remote's button pressed in a pattern. */
@Composable
private fun ActionConfigDialog(
    action: HomeAction,
    initial: ActionConfig,
    initialNeedsHold: Boolean,
    onDismiss: () -> Unit,
    onSave: (ActionConfig, Boolean) -> Unit,
) {
    val colors = LocalAppColors.current
    var config by remember { mutableStateOf(initial) }
    var needsHold by remember { mutableStateOf(initialNeedsHold) }
    AppDialog(title = stringResource(actionLabel(action)), onDismiss = onDismiss, buttons = {
        PillButton(stringResource(R.string.cancel), onClick = onDismiss)
        PillButton(stringResource(R.string.save), style = ButtonStyle.PRIMARY) {
            onDismiss()
            onSave(config, needsHold)
        }
    }) {
        Label(stringResource(R.string.cfg_how), weight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.cfg_method_box), config.method == ActionConfig.Method.BOX) {
                config = config.copy(method = ActionConfig.Method.BOX)
            }
            Chip(stringResource(R.string.cfg_method_remote), config.method == ActionConfig.Method.REMOTE) {
                if (config.method != ActionConfig.Method.REMOTE) config = ActionConfig.remoteDefault(action)
            }
        }
        if (config.method == ActionConfig.Method.BOX) {
            Label(stringResource(R.string.cfg_method_box_hint), size = 13.sp, color = colors.text2)
        } else {
            Label(stringResource(R.string.cfg_button), weight = FontWeight.Bold)
            RemoteButton.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { button ->
                        Chip(stringResource(remoteButtonLabel(button)), config.button == button) { config = config.copy(button = button) }
                    }
                }
            }
            Label(stringResource(R.string.cfg_presses), weight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..ActionConfig.MAX_PRESSES).forEach { n ->
                    Chip(stringResource(R.string.cfg_presses_n, n), config.presses == n) { config = config.copy(presses = n) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.cfg_length), Modifier.weight(1f), weight = FontWeight.Bold)
                Label("%.1f s".format(config.holdMs / 1000f), weight = FontWeight.Bold, tabular = true)
            }
            ValueSlider(config.holdMs / 100, (ActionConfig.HOLD_RANGE_MS.first / 100)..(ActionConfig.HOLD_RANGE_MS.last / 100), {
                config = config.copy(holdMs = it * 100)
            })
            Label(stringResource(R.string.cfg_remote_hint), size = 13.sp, color = colors.text2)
        }
        Hairline()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(stringResource(R.string.settings_needs_hold), weight = FontWeight.Bold)
                Label(stringResource(R.string.cfg_confirm_hint), size = 13.sp, color = colors.text2)
            }
            Spacer(Modifier.width(10.dp))
            Toggle(needsHold, { needsHold = it })
        }
    }
}

@Composable
private fun HomeActionRow(
    action: HomeAction,
    shown: Boolean,
    needsHold: Boolean,
    config: ActionConfig,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onShown: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(colors.surface2), contentAlignment = Alignment.Center) {
            Icon(actionIcon(action), if (shown) colors.accent else colors.text2, size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(stringResource(actionLabel(action)), weight = FontWeight.Bold, maxLines = 1)
            if (action.command != null) {
                val how = if (config.method == ActionConfig.Method.REMOTE) describe(config) else stringResource(R.string.cfg_method_box)
                val tap = stringResource(if (needsHold) R.string.settings_hold_short else R.string.settings_single_tap)
                // Rounded corners, not a pill: the summary may wrap to two lines
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).pressable(onClick = onEdit).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label("$how · $tap", size = 13.sp, color = colors.accent, maxLines = 2)
                    Icon(AppIcons.Chevron, colors.accent, size = 14.dp)
                }
            } else if (action.holdButton != null) {
                Label(stringResource(R.string.hint_hold_button), size = 13.sp, color = colors.text2)
            }
        }
        if (shown) {
            RoundIconButton(AppIcons.Up, "Up", Modifier.alpha(if (canMoveUp) 1f else 0.3f), background = colors.surface2, size = 36.dp) {
                if (canMoveUp) onMove(-1)
            }
            Spacer(Modifier.width(6.dp))
            RoundIconButton(AppIcons.Down, "Down", Modifier.alpha(if (canMoveDown) 1f else 0.3f), background = colors.surface2, size = 36.dp) {
                if (canMoveDown) onMove(1)
            }
            Spacer(Modifier.width(10.dp))
        }
        Toggle(shown, onShown)
    }
}

@Composable
fun LogScreen() {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val lines by EventLog.lines.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(AppIcons.Back, "Back", onClick = nav.back)
            Spacer(Modifier.width(10.dp))
            Label(stringResource(R.string.log_title), Modifier.weight(1f), size = 24.sp, weight = FontWeight.ExtraBold)
            RoundIconButton(AppIcons.Trash, stringResource(R.string.log_clear)) { EventLog.clearScreen() }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(AppIcons.Share, stringResource(R.string.log_share)) {
                val uris = EventLog.files().map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
                if (uris.isEmpty()) return@RoundIconButton
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("text/plain")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.log_share)))
            }
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .clip(TileShape)
                .background(colors.surface)
                .padding(12.dp),
            state = list,
        ) {
            items(lines) { line ->
                Label(line, size = 12.sp, mono = true, color = if (line.contains("fail", true) || line.contains("refused", true)) colors.bad else colors.text)
            }
        }
    }
}
