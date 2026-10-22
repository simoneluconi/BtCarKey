package com.simoneluconi.btcarkey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.btcarkey.R
import com.simoneluconi.btcarkey.auth.Serials
import com.simoneluconi.btcarkey.keys.SavedDevice
import com.simoneluconi.btcarkey.link.CarKeyController
import com.simoneluconi.btcarkey.link.FoundBox
import com.simoneluconi.btcarkey.protocol.KeyProtocol
import com.simoneluconi.btcarkey.protocol.Protocols
import com.simoneluconi.btcarkey.protocol.hexToBytesOrNull
import com.simoneluconi.btcarkey.ui.theme.LocalAppColors
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun GarageScreen() {
    val nav = LocalActions.current
    val store = nav.app.store
    val devices by store.devices.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    ScreenScaffold(stringResource(R.string.garage_title)) {
        if (devices.isEmpty()) {
            Card { Label(stringResource(R.string.garage_empty), color = LocalAppColors.current.text2) }
        }
        devices.forEach { device ->
            DeviceRow(
                device = device,
                active = device.serial == selected,
                onSelect = {
                    store.select(device.serial)
                    nav.back()
                },
                onOpen = { nav.navigate(Screen.Device(device.serial)) },
            )
        }
        PillButton(stringResource(R.string.garage_add), icon = AppIcons.Plus, style = ButtonStyle.PRIMARY) { nav.navigate(Screen.AddDevice) }
    }
}

@Composable
private fun DeviceRow(device: SavedDevice, active: Boolean, onSelect: () -> Unit, onOpen: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(TileShape)
            .background(if (active) colors.accentSoft else colors.surface)
            .pressable(label = device.name, onLongClick = onOpen, onClick = onSelect)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (active) colors.accent else colors.surface2), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Car, if (active) colors.onAccent else colors.text)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Label(device.name, size = 18.sp, weight = FontWeight.ExtraBold, maxLines = 1)
            Label(device.serial, size = 13.sp, color = colors.text2, mono = true)
            Label(Protocols.byId(device.protocolId)?.displayName ?: device.protocolId, size = 13.sp, color = colors.text2)
            Label(
                stringResource(
                    when {
                        device.hasKey -> R.string.key_state_stored
                        device.hasPendingKey -> R.string.key_state_pending
                        else -> R.string.key_state_none
                    }
                ),
                size = 13.sp,
                color = if (device.hasKey) colors.good else colors.text2,
            )
        }
        RoundIconButton(AppIcons.Edit, stringResource(R.string.device_title), background = colors.surface2, size = 44.dp, onClick = onOpen)
    }
}

@Composable
fun AddDeviceScreen() {
    val nav = LocalActions.current
    val app = nav.app
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val found = remember { mutableStateMapOf<String, FoundBox>() }
    var scanning by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf<FoundBox?>(null) }
    var importing by remember { mutableStateOf(false) }
    val devices by app.store.devices.collectAsStateWithLifecycle()
    val permitted = CarKeyController.hasPermissions(context)

    // A bounded scan: started when the screen opens (and on "Scan again"), stopped after 30 s
    LaunchedEffect(round, permitted) {
        if (!permitted) return@LaunchedEffect
        scanning = true
        withTimeoutOrNull(SCAN_MS) {
            app.controller.scanForBoxes().collect { box -> found[box.serial] = box }
        }
        scanning = false
    }

    ScreenScaffold(stringResource(R.string.add_title)) {
        Label(stringResource(R.string.add_hint), color = colors.text2)
        if (!permitted) {
            PillButton(stringResource(R.string.action_grant), icon = AppIcons.Shield, style = ButtonStyle.PRIMARY, onClick = nav.requestPermissions)
        }
        SectionTitle(stringResource(R.string.add_nearby))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    Spinner()
                    Spacer(Modifier.width(12.dp))
                    Label(stringResource(R.string.add_scanning), Modifier.weight(1f), color = colors.text2)
                } else {
                    Label(stringResource(R.string.add_scan_done, found.size), Modifier.weight(1f), color = colors.text2)
                    PillButton(stringResource(R.string.add_scan_again), icon = AppIcons.Refresh, enabled = permitted) {
                        found.clear()
                        round++
                    }
                }
            }
            found.values.sortedByDescending { it.rssi }.forEach { box ->
                val known = devices.any { Serials.same(it.serial, box.serial) }
                Spacer(Modifier.height(8.dp))
                Hairline()
                SettingRow(
                    title = box.name ?: stringResource(R.string.add_unnamed_box),
                    subtitle = "${box.serial} · ${box.rssi} dBm\n${box.protocol.displayName}" +
                        if (known) " · " + stringResource(R.string.add_already_saved) else "",
                    icon = AppIcons.Fob,
                    onClick = { naming = box },
                )
            }
        }
        SectionTitle(stringResource(R.string.add_with_key))
        Card {
            Label(stringResource(R.string.add_with_key_hint), color = colors.text2)
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.key_import), icon = AppIcons.Key) { importing = true }
        }
    }

    naming?.let { box ->
        NameDialog(
            title = stringResource(R.string.add_name_title),
            initial = devices.firstOrNull { Serials.same(it.serial, box.serial) }?.name.orEmpty(),
            onDismiss = { naming = null },
        ) { name ->
            val existing = devices.firstOrNull { Serials.same(it.serial, box.serial) }
            val serial = existing?.serial ?: box.serial
            app.store.add(serial, name, box.mac, box.protocol.id)
            app.controller.use(serial)
            nav.message(resources.getString(R.string.add_saved, name.ifBlank { serial }), MessageKind.GOOD)
            nav.back()
        }
    }
    if (importing) {
        ImportKeyDialog(serial = null, keySize = Protocols.default.keySize, onDismiss = { importing = false }) { serial, name, key, protocol ->
            app.store.add(serial, name, null, protocol.id)
            app.store.importKey(serial, key)
            app.controller.use(serial)
            nav.message(resources.getString(R.string.key_imported), MessageKind.GOOD)
            nav.back()
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AppDialog(title = title, onDismiss = onDismiss, buttons = {
        PillButton(stringResource(R.string.cancel), onClick = onDismiss)
        PillButton(stringResource(R.string.save), style = ButtonStyle.PRIMARY) {
            onDismiss()
            onSave(name.trim())
        }
    }) {
        TextInput(name, { name = it.take(40) }, stringResource(R.string.add_name_placeholder),
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
    }
}

/**
 * Serial and name (unless a saved box is given), and the key in hex. Used to add a box with a known
 * key, and to replace the key of a saved one. A new box also needs its protocol: chips appear when
 * the app knows more than one.
 */
@Composable
fun ImportKeyDialog(
    serial: String?,
    keySize: Int,
    onDismiss: () -> Unit,
    onImport: (serial: String, name: String, key: ByteArray, protocol: KeyProtocol) -> Unit,
) {
    val colors = LocalAppColors.current
    var serialText by remember { mutableStateOf(serial.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var keyText by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(Protocols.default) }
    val size = if (serial == null) protocol.keySize else keySize
    val parsedSerial = Serials.normalize(serialText)
    val parsedKey = keyText.hexToBytesOrNull()?.takeIf { it.size == size }
    AppDialog(title = stringResource(R.string.key_import), onDismiss = onDismiss, buttons = {
        PillButton(stringResource(R.string.cancel), onClick = onDismiss)
        PillButton(stringResource(R.string.key_import_confirm), style = ButtonStyle.PRIMARY, enabled = parsedSerial != null && parsedKey != null) {
            onDismiss()
            onImport(parsedSerial!!, name.trim(), parsedKey!!, protocol)
        }
    }) {
        if (serial == null) {
            if (Protocols.all.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Protocols.all.forEach { option -> Chip(option.displayName, option == protocol) { protocol = option } }
                }
            }
            TextInput(serialText, { serialText = it.take(17) }, "AA:BB:CC:DD:EE:FF", mono = true,
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            TextInput(name, { name = it.take(40) }, stringResource(R.string.add_name_placeholder),
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        }
        TextInput(keyText, { keyText = it.take(size * 3) }, stringResource(R.string.key_hex_placeholder, size * 2), mono = true)
        val invalid = keyText.isNotEmpty() && parsedKey == null
        Label(
            if (invalid) stringResource(R.string.key_hex_invalid, size, size * 2) else stringResource(R.string.key_import_warning),
            size = 13.sp,
            color = if (invalid) colors.bad else colors.text2,
        )
    }
}

private const val SCAN_MS = 30_000L
