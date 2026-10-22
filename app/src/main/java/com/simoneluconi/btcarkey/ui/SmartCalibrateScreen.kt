package com.simoneluconi.btcarkey.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.R
import com.simoneluconi.btcarkey.link.LinkState
import com.simoneluconi.btcarkey.protocol.AutoLockConfig
import com.simoneluconi.btcarkey.smart.SmartCalibration
import com.simoneluconi.btcarkey.smart.SmartEntry
import com.simoneluconi.btcarkey.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class CalStep { INTRO, DOOR, AWAY, RESULT }

/** The scale of the pictures, -dBm: left is next to the car, right is far. */
private val SCALE = 35..95

/**
 * Smart unlock's guided calibration: the signal is sampled by the door and where the car should lock,
 * and [SmartCalibration] proposes the thresholds. Smart unlock doesn't act meanwhile.
 */
@Composable
fun SmartCalibrateScreen() {
    val nav = LocalActions.current
    val app = nav.app
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by app.controller.state.collectAsStateWithLifecycle()
    val selected by app.store.selected.collectAsStateWithLifecycle()
    val ready = (state as? LinkState.Ready)?.takeIf { it.serial == selected } != null
    var step by rememberSaveable { mutableStateOf(CalStep.INTRO) }
    val door = remember { mutableStateListOf<Int>() }
    val away = remember { mutableStateListOf<Int>() }
    var measuring by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var live by remember { mutableStateOf<Int?>(null) }

    // Walking to and from the car on purpose: a visit in progress must not open or lock it meanwhile
    DisposableEffect(Unit) {
        SmartEntry.calibrating = true
        EventLog.log("Smart: guided calibration started, no automatic actions meanwhile")
        onDispose {
            SmartEntry.calibrating = false
            EventLog.log("Smart: guided calibration closed")
        }
    }
    // Live signal between measurements
    LaunchedEffect(ready) {
        live = null
        while (ready && isActive) {
            if (!measuring) live = app.controller.readLinkRssi()?.let(::abs)
            delay(LIVE_MS)
        }
    }

    fun measure(into: MutableList<Int>, next: CalStep) {
        into.clear()
        measuring = true
        progress = 0f
        scope.launch {
            repeat(SmartCalibration.SAMPLES) { i ->
                app.controller.readLinkRssi()?.let {
                    into += it
                    live = abs(it)
                }
                progress = (i + 1f) / SmartCalibration.SAMPLES
                delay(SmartCalibration.SAMPLE_MS)
            }
            measuring = false
            if (into.isEmpty()) {
                nav.message(resources.getString(R.string.smartcal_no_signal), MessageKind.BAD)
            } else {
                val spot = if (next == CalStep.AWAY) "door" else "lock spot"
                EventLog.log("Smart: calibration, $spot at -${AutoLockConfig.trimmedMean(into.map(::abs))} dBm (${into.size} samples)")
                step = next
            }
        }
    }

    ScreenScaffold(stringResource(R.string.smartcal_title)) {
        StepBar(step)
        if (!ready) {
            Card { Label(stringResource(R.string.smart_needs_connection), color = colors.warn) }
            return@ScreenScaffold
        }
        AnimatedContent(step, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "step") { current ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current) {
                    CalStep.INTRO -> {
                        Card {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                SignalOrb(level = live, progress = 0f, measuring = false)
                            }
                            Spacer(Modifier.height(16.dp))
                            Label(stringResource(R.string.smartcal_intro_title), size = 21.sp, weight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(8.dp))
                            Label(stringResource(R.string.smartcal_intro_text), color = colors.text2)
                        }
                        PillButton(stringResource(R.string.smartcal_start), Modifier.fillMaxWidth(), icon = AppIcons.Radar,
                            style = ButtonStyle.PRIMARY) { step = CalStep.DOOR }
                    }
                    CalStep.DOOR, CalStep.AWAY -> {
                        val isDoor = current == CalStep.DOOR
                        Card {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                SignalOrb(level = live, progress = if (measuring) progress else 0f, measuring = measuring)
                            }
                            Spacer(Modifier.height(16.dp))
                            Label(stringResource(if (isDoor) R.string.smartcal_door_title else R.string.smartcal_away_title),
                                size = 21.sp, weight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(8.dp))
                            Label(stringResource(if (isDoor) R.string.smartcal_door_text else R.string.smartcal_away_text), color = colors.text2)
                        }
                        if (measuring) {
                            Card(color = colors.accentSoft) {
                                Label(stringResource(R.string.smartcal_measuring), Modifier.fillMaxWidth(), align = TextAlign.Center,
                                    weight = FontWeight.Bold, color = colors.accent)
                            }
                        } else {
                            PillButton(stringResource(R.string.smartcal_measure), Modifier.fillMaxWidth(), icon = AppIcons.Radar,
                                style = ButtonStyle.PRIMARY) {
                                if (isDoor) measure(door, CalStep.AWAY) else measure(away, CalStep.RESULT)
                            }
                        }
                    }
                    CalStep.RESULT -> {
                        val proposal = SmartCalibration.propose(door, away)
                        if (proposal == null) {
                            // The samples didn't survive (the screen was recreated): measure again
                            LaunchedEffect(Unit) { step = CalStep.DOOR }
                            return@Column
                        }
                        Card {
                            Label(stringResource(R.string.smartcal_result_title), size = 21.sp, weight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(16.dp))
                            DistanceMap(
                                proposal.unlockDbm, proposal.lockDbm,
                                listOf(
                                    MapPin(proposal.doorDbm, colors.good, stringResource(R.string.smartcal_door_point)),
                                    MapPin(proposal.awayDbm, colors.warn, stringResource(R.string.smartcal_away_point)),
                                ),
                            )
                            Spacer(Modifier.height(18.dp))
                            InfoRow(stringResource(R.string.comfort_unlock_at), "-${proposal.unlockDbm} dBm")
                            Hairline()
                            InfoRow(stringResource(R.string.comfort_lock_at), "-${proposal.lockDbm} dBm")
                        }
                        if (proposal.tooClose) {
                            Card(color = colors.soft(colors.warn)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Warning, colors.warn)
                                    Spacer(Modifier.width(12.dp))
                                    Label(stringResource(R.string.smartcal_too_close), Modifier.weight(1f), size = 14.sp)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(stringResource(R.string.smartcal_redo), Modifier.weight(1f), icon = AppIcons.Refresh) {
                                door.clear()
                                away.clear()
                                step = CalStep.DOOR
                            }
                            PillButton(stringResource(R.string.smartcal_apply), Modifier.weight(1f), icon = AppIcons.Check,
                                style = ButtonStyle.PRIMARY) {
                                app.smart.setThresholds(proposal.unlockDbm, proposal.lockDbm)
                                EventLog.log("Smart: calibration applied, unlock -${proposal.unlockDbm} dBm, lock -${proposal.lockDbm} dBm")
                                nav.message(resources.getString(R.string.smartcal_applied), MessageKind.GOOD)
                                nav.back()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The three steps as a segmented bar, the current one and those done in the accent color. */
@Composable
private fun StepBar(step: CalStep) {
    val colors = LocalAppColors.current
    val labels = listOf(R.string.smartcal_step_door, R.string.smartcal_step_away, R.string.smartcal_step_result)
    val reached = step.ordinal // INTRO = 0: none reached yet
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i < reached
            val fill by animateFloatAsState(if (on) 1f else 0f, tween(350), label = "step$i")
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.surface2)) {
                    Box(Modifier.fillMaxWidth(fill).height(6.dp).clip(CircleShape).background(colors.accent))
                }
                Spacer(Modifier.height(6.dp))
                Label(stringResource(label), size = 12.sp, color = if (on) colors.text else colors.text2, weight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The signal as a pulsing beacon: waves spread faster and brighter the stronger the signal, a ring
 * fills while measuring, the level sits in the middle.
 */
@Composable
private fun SignalOrb(level: Int?, progress: Float, measuring: Boolean, size: Dp = 250.dp) {
    val colors = LocalAppColors.current
    // 0 = far (or no reading), 1 = right next to the car
    val strength by animateFloatAsState(
        level?.let { 1f - ((it - SCALE.first).toFloat() / (SCALE.last - SCALE.first)).coerceIn(0f, 1f) } ?: 0f,
        tween(600), label = "strength",
    )
    val ring by animateFloatAsState(progress, tween(SmartCalibration.SAMPLE_MS.toInt(), easing = LinearEasing), label = "ring")
    val waves = rememberInfiniteTransition(label = "waves")
    val phase by waves.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val breath by waves.animateFloat(0.92f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val tint = if (level == null) colors.text2 else colors.accent
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val maxR = this.size.minDimension / 2f
            // Room for the level and its caption inside
            val core = maxR * 0.58f
            // Waves: three, evenly spaced in time, from the core outwards
            for (i in 0 until 3) {
                val t = (phase + i / 3f) % 1f
                val r = core + (maxR * 0.92f - core) * t
                val alpha = (1f - t) * (0.15f + 0.45f * strength)
                drawCircle(tint.copy(alpha = alpha), radius = r, style = Stroke(width = (2 + 3 * strength).dp.toPx()))
            }
            // The core, breathing
            drawCircle(tint.copy(alpha = 0.12f + 0.10f * strength), radius = core * breath)
            // Progress ring while measuring
            val stroke = 8.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcAt = Offset(inset, inset)
            val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            drawArc(colors.surface2, 0f, 360f, false, topLeft = arcAt, size = arcSize, style = Stroke(stroke))
            if (measuring || ring > 0f) {
                drawArc(colors.accent, -90f, 360f * ring, false, topLeft = arcAt, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(level?.let { "-$it" } ?: "…", size = 44.sp, weight = FontWeight.ExtraBold, tabular = true, color = colors.text)
            Label("dBm", size = 13.sp, color = colors.text2, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Label(stringResource(strengthLabel(level)), size = 13.sp, color = tint, weight = FontWeight.Bold)
        }
    }
}

private fun strengthLabel(level: Int?): Int = when {
    level == null -> R.string.smartcal_strength_none
    level <= 55 -> R.string.smartcal_strength_strong
    level <= 70 -> R.string.smartcal_strength_good
    level <= 80 -> R.string.smartcal_strength_weak
    else -> R.string.smartcal_strength_very_weak
}

private const val LIVE_MS = 700L
