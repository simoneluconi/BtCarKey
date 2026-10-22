package com.simoneluconi.btcarkey.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.R
import com.simoneluconi.btcarkey.link.LinkState
import com.simoneluconi.btcarkey.protocol.Feature
import com.simoneluconi.btcarkey.protocol.Protocols
import com.simoneluconi.btcarkey.protocol.RawConfigKind
import com.simoneluconi.btcarkey.protocol.hexToBytesOrNull
import com.simoneluconi.btcarkey.protocol.toHex
import com.simoneluconi.btcarkey.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun DeviceScreen(serial: String) {
    val nav = LocalActions.current
    val app = nav.app
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val devices by app.store.devices.collectAsStateWithLifecycle()
    val selected by app.store.selected.collectAsStateWithLifecycle()
    val state by app.controller.state.collectAsStateWithLifecycle()
    val status by app.controller.status.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.serial == serial } ?: run {
        ScreenScaffold(stringResource(R.string.device_title)) {}
        return
    }
    val ready = (state as? LinkState.Ready)?.takeIf { it.serial == serial }
    val protocol = app.controller.protocolOf(device)
    val features = protocol?.features.orEmpty()
    var renaming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<ByteArray?>(null) }
    var plate by remember { mutableStateOf("") }
    var nfcRunning by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val key = app.store.activeKey(serial)
        if (uri == null || key == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(exportJson(device.name, serial, device.protocolId, key).toByteArray()) }
        }.onSuccess {
            EventLog.log("Key: exported for $serial")
            nav.message(resources.getString(R.string.key_exported), MessageKind.GOOD)
        }.onFailure {
            EventLog.log("Key: export failed (${it.javaClass.simpleName})")
            nav.message(resources.getString(R.string.failed), MessageKind.BAD)
        }
    }

    // No screenshots or recents thumbnail while the key is on screen
    if (revealed != null) SecureWindow()

    ScreenScaffold(device.name, actions = {
        RoundIconButton(AppIcons.Edit, stringResource(R.string.device_rename)) { renaming = true }
    }) {
        if (selected != serial) {
            PillButton(stringResource(R.string.device_use_this), icon = AppIcons.Check, style = ButtonStyle.PRIMARY) { app.store.select(serial) }
        }

        SectionTitle(stringResource(R.string.device_info))
        Card {
            InfoRow(stringResource(R.string.info_protocol), protocol?.displayName ?: stringResource(R.string.info_protocol_unknown, device.protocolId))
            Hairline()
            InfoRow(stringResource(R.string.info_serial), serial, mono = true)
            Hairline()
            InfoRow(stringResource(R.string.info_address), device.mac ?: "—", mono = true)
            if (ready != null) {
                ready.info.firmware?.let {
                    Hairline()
                    InfoRow(stringResource(R.string.info_firmware), it)
                }
                ready.info.model?.let {
                    Hairline()
                    InfoRow(stringResource(R.string.info_type), it)
                }
                status.batteryPercent?.let {
                    Hairline()
                    InfoRow(stringResource(R.string.info_battery), "$it%")
                }
                status.voltageMv?.let {
                    Hairline()
                    InfoRow(stringResource(R.string.info_voltage), "%.2f V".format(it / 1000f))
                }
                status.charging?.let {
                    Hairline()
                    InfoRow(stringResource(R.string.info_charging), stringResource(if (it) R.string.yes else R.string.no))
                }
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.info_refresh), icon = AppIcons.Refresh) { app.controller.refreshStatus() }
            } else {
                Spacer(Modifier.height(6.dp))
                Label(stringResource(R.string.device_connect_for_more), size = 14.sp, color = colors.text2)
            }
        }

        SectionTitle(stringResource(R.string.key_title))
        Card {
            val key = revealed
            SettingRow(
                title = stringResource(
                    when {
                        device.hasKey -> R.string.key_state_stored
                        device.hasPendingKey -> R.string.key_state_pending
                        else -> R.string.key_state_none
                    }
                ),
                subtitle = device.keyFingerprint?.let { stringResource(R.string.key_fingerprint, it) }
                    ?: stringResource(R.string.key_none_hint),
                icon = AppIcons.Key,
                iconTint = if (device.hasKey) colors.good else colors.text2,
            )
            if (key != null) {
                Spacer(Modifier.height(6.dp))
                Card(color = colors.surface2, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                    Label(key.toHex(" ").chunked(24).joinToString("\n"), mono = true, size = 17.sp, weight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(R.string.key_copy), icon = AppIcons.Copy) {
                        copySensitive(context, key.toHex())
                        nav.message(resources.getString(R.string.key_copied), MessageKind.INFO)
                    }
                    PillButton(stringResource(R.string.key_hide), icon = AppIcons.Close) { revealed = null }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (device.hasKey && key == null) {
                    PillButton(stringResource(R.string.key_show), icon = AppIcons.Eye) {
                        nav.authenticate(resources.getString(R.string.key_show)) {
                            revealed = app.store.activeKey(serial)
                            EventLog.log("Key: shown for $serial")
                        }
                    }
                }
                if (device.hasKey) {
                    PillButton(stringResource(R.string.key_export), icon = AppIcons.Share) {
                        nav.authenticate(resources.getString(R.string.key_export)) {
                            exportFile.launch("btcarkey-${device.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')}.json")
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            PillButton(stringResource(if (device.hasKey) R.string.key_replace else R.string.key_import), icon = AppIcons.Import) {
                if (device.hasKey) nav.authenticate(resources.getString(R.string.key_replace)) { importing = true } else importing = true
            }
            if (device.hasBackupKey) {
                Spacer(Modifier.height(8.dp))
                Label(stringResource(R.string.key_backup_kept), size = 13.sp, color = colors.text2)
            }
        }

        // Only the settings this device's protocol offers
        val boxFeatures = features.intersect(setOf(Feature.PLATE, Feature.NFC, Feature.REPAIR))
        if (boxFeatures.isNotEmpty()) SectionTitle(stringResource(R.string.device_box))
        if (boxFeatures.isNotEmpty()) Card {
            if (ready == null) {
                Label(stringResource(R.string.device_connect_for_more), size = 14.sp, color = colors.text2)
            } else {
                if (Feature.PLATE in features) {
                    Label(stringResource(R.string.plate_title), weight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    TextInput(plate, { plate = it.take(12).uppercase() }, stringResource(R.string.plate_placeholder),
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                    Spacer(Modifier.height(8.dp))
                    PillButton(stringResource(R.string.save), icon = AppIcons.Plate, enabled = plate.isNotBlank() && !working) {
                        working = true
                        scope.launch {
                            val result = app.controller.setPlate(plate.trim())
                            working = false
                            nav.message(resources.getString(if (result.isSuccess) R.string.saved else R.string.failed), if (result.isSuccess) MessageKind.GOOD else MessageKind.BAD)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (Feature.NFC in features) {
                    Hairline()
                    SettingRow(
                        title = stringResource(R.string.nfc_title),
                        subtitle = stringResource(if (nfcRunning) R.string.nfc_running else R.string.nfc_hint),
                        icon = AppIcons.Card,
                        onClick = if (nfcRunning) null else {
                            {
                                nfcRunning = true
                                scope.launch {
                                    val result = app.controller.registerNfc(NFC_SECONDS)
                                    nfcRunning = false
                                    nav.message(
                                        resources.getString(if (result.isSuccess) R.string.nfc_done else R.string.nfc_failed),
                                        if (result.isSuccess) MessageKind.GOOD else MessageKind.BAD,
                                    )
                                }
                            }
                        },
                        trailing = if (nfcRunning) { { Spinner() } } else null,
                    )
                }
                if (Feature.REPAIR in features) {
                    Hairline()
                    SettingRow(
                        title = stringResource(R.string.repair_title),
                        subtitle = stringResource(R.string.repair_hint),
                        icon = AppIcons.Refresh,
                        onClick = { repairing = true },
                    )
                }
            }
        }

        if (protocol != null && Feature.RAW_CONFIG in features && protocol.rawConfigs.isNotEmpty()) {
            SectionTitle(stringResource(R.string.advanced_title))
            AdvancedConfigCard(protocol.rawConfigs, enabled = ready != null)
        }

        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.device_remove), icon = AppIcons.Trash, style = ButtonStyle.DANGER) { removing = true }
    }

    if (renaming) {
        NameDialog(stringResource(R.string.device_rename), device.name, onDismiss = { renaming = false }) { app.store.rename(serial, it) }
    }
    if (importing) {
        ImportKeyDialog(serial = serial, keySize = protocol?.keySize ?: Protocols.default.keySize, onDismiss = { importing = false }) { _, _, key, _ ->
            app.store.importKey(serial, key)
            revealed = null
            nav.message(resources.getString(R.string.key_imported), MessageKind.GOOD)
            app.controller.retry()
        }
    }
    if (removing) {
        ConfirmDialog(
            title = stringResource(R.string.device_remove),
            message = stringResource(R.string.device_remove_message, device.name),
            confirm = stringResource(R.string.remove),
            cancel = stringResource(R.string.cancel),
            danger = true,
            onDismiss = { removing = false },
        ) {
            nav.authenticate(resources.getString(R.string.device_remove)) {
                app.store.remove(serial)
                nav.back()
            }
        }
    }
    if (repairing) {
        ConfirmDialog(
            title = stringResource(R.string.repair_title),
            message = stringResource(R.string.repair_message),
            confirm = stringResource(R.string.repair_confirm),
            cancel = stringResource(R.string.cancel),
            danger = true,
            onDismiss = { repairing = false },
        ) {
            nav.authenticate(resources.getString(R.string.repair_title)) {
                scope.launch {
                    val result = app.controller.repair()
                    nav.message(
                        resources.getString(if (result.isSuccess) R.string.repair_done else R.string.repair_failed),
                        if (result.isSuccess) MessageKind.GOOD else MessageKind.BAD,
                    )
                }
            }
        }
    }
}

/** Vendor configuration blobs (no catalog without the vendor's server): sent as typed, for experts. */
@Composable
private fun AdvancedConfigCard(kinds: Set<RawConfigKind>, enabled: Boolean) {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val offered = RawConfigKind.entries.filter { it in kinds }
    var target by remember { mutableStateOf(offered.first()) }
    var hex by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val bytes = hex.hexToBytesOrNull()?.takeIf { it.isNotEmpty() }
    Card {
        Label(stringResource(R.string.advanced_hint), size = 14.sp, color = colors.text2)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            offered.forEach { kind ->
                val label = when (kind) {
                    RawConfigKind.LOCK -> R.string.cfg_lock
                    RawConfigKind.UNLOCK -> R.string.cfg_unlock
                    RawConfigKind.REMOTE_START -> R.string.cfg_start
                    RawConfigKind.CAR_TYPE -> R.string.cfg_car
                }
                Chip(stringResource(label), target == kind) { target = kind }
            }
        }
        Spacer(Modifier.height(10.dp))
        TextInput(hex, { hex = it.take(512) }, stringResource(R.string.advanced_placeholder), mono = true)
        Spacer(Modifier.height(10.dp))
        PillButton(stringResource(R.string.advanced_send), icon = AppIcons.Tool, enabled = enabled && bytes != null) { confirming = true }
    }
    if (confirming && bytes != null) {
        ConfirmDialog(
            title = stringResource(R.string.advanced_title),
            message = stringResource(R.string.advanced_confirm, bytes.size),
            confirm = stringResource(R.string.advanced_send),
            cancel = stringResource(R.string.cancel),
            danger = true,
            onDismiss = { confirming = false },
        ) {
            scope.launch {
                val result = nav.app.controller.writeRawConfig(target, bytes)
                nav.message(
                    resources.getString(if (result.isSuccess) R.string.saved else R.string.failed),
                    if (result.isSuccess) MessageKind.GOOD else MessageKind.BAD,
                )
            }
        }
    }
}

@Composable
private fun SecureWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Copies a secret, marked sensitive (hidden from the clipboard preview) and cleared after a minute. */
private fun copySensitive(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("device key", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        val current = clipboard.primaryClip
        if (current != null && current.itemCount > 0 && current.getItemAt(0).text?.toString() == text) clipboard.clearPrimaryClip()
    }, CLIPBOARD_CLEAR_MS)
}

/** The same fields the adb import script reads, so an exported file can be pushed back. */
private fun exportJson(name: String, serial: String, protocolId: String, key: ByteArray): String =
    "{\n  \"name\": ${org.json.JSONObject.quote(name)},\n  \"serial\": \"$serial\",\n  \"protocol\": \"$protocolId\",\n" +
        "  \"deviceKey\": \"${key.toHex()}\"\n}\n"

private const val NFC_SECONDS = 10
private const val CLIPBOARD_CLEAR_MS = 60_000L
