package com.simoneluconi.btcarkey.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.btcarkey.R
import com.simoneluconi.btcarkey.ui.theme.LocalAppColors

/** A point on the [DistanceMap]: a level (-dBm, positive) with its name. */
data class MapPin(val level: Int, val color: Color, val name: String)

/** The scale of the map, -dBm: left is next to the car, right is far. */
private val SCALE = 35..95

/**
 * Smart unlock's distances as a strip from the car (left) outwards: where it opens, where nothing
 * happens, where it locks, with [pins] (measured spots, or the phone's live signal) on it.
 */
@Composable
fun DistanceMap(unlockDbm: Int, lockDbm: Int, pins: List<MapPin>, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    BoxWithConstraints(modifier.fillMaxWidth().height(if (pins.isEmpty()) 56.dp else 96.dp)) {
        val span = (SCALE.last - SCALE.first).toFloat()
        fun x(v: Int): Dp = maxWidth * ((v - SCALE.first) / span).coerceIn(0f, 1f)
        val unlockX by animateDpAsState(x(unlockDbm), tween(250), label = "unlock")
        val lockX by animateDpAsState(x(lockDbm), tween(250), label = "lock")
        val barTop = 30.dp
        val bar = 18.dp
        // Zones
        Box(Modifier.offset(y = barTop).fillMaxWidth().height(bar).clip(RoundedCornerShape(9.dp)).background(colors.surface2))
        Box(Modifier.offset(y = barTop).width(unlockX).height(bar).clip(RoundedCornerShape(9.dp)).background(colors.soft(colors.good)))
        Box(Modifier.offset { IntOffset(lockX.roundToPx(), barTop.roundToPx()) }.width(maxWidth - lockX).height(bar)
            .clip(RoundedCornerShape(9.dp)).background(colors.soft(colors.warn)))
        // Thresholds
        Box(Modifier.offset { IntOffset((unlockX - 1.dp).roundToPx(), (barTop - 4.dp).roundToPx()) }
            .width(2.dp).height(bar + 8.dp).background(colors.good))
        Box(Modifier.offset { IntOffset((lockX - 1.dp).roundToPx(), (barTop - 4.dp).roundToPx()) }
            .width(2.dp).height(bar + 8.dp).background(colors.warn))
        // Zone names above the bar
        Label(stringResource(R.string.smartcal_zone_open), Modifier.offset(y = 4.dp), size = 12.sp, color = colors.good, weight = FontWeight.Bold)
        Label(stringResource(R.string.smartcal_zone_lock), Modifier.align(Alignment.TopEnd).offset(y = 4.dp), size = 12.sp,
            color = colors.warn, weight = FontWeight.Bold)
        // The car at the start of the strip
        Icon(AppIcons.Car, colors.text, Modifier.offset(x = 2.dp, y = barTop - 3.dp), size = bar + 6.dp)
        // Pins: a dot on the bar, name and level under it (moving smoothly with a live signal)
        pins.forEach { pin ->
            key(pin.name) {
                val px by animateDpAsState(x(pin.level), tween(500), label = pin.name)
                Pin(px, barTop + bar / 2 - 7.dp, barTop + bar + 8.dp, maxWidth, pin)
            }
        }
    }
}

/** A dot on the strip with its name and level under it, the label kept inside the strip's [width]. */
@Composable
private fun Pin(x: Dp, dotY: Dp, labelY: Dp, width: Dp, pin: MapPin) {
    val colors = LocalAppColors.current
    val labelWidth = 92.dp
    val left = (x - labelWidth / 2).coerceIn(0.dp, width - labelWidth)
    Box {
        Box(Modifier.offset(x = x - 7.dp, y = dotY).size(14.dp).clip(CircleShape).background(colors.surface)) {
            Box(Modifier.align(Alignment.Center).size(10.dp).clip(CircleShape).background(pin.color))
        }
        Column(Modifier.offset(x = left, y = labelY).width(labelWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            Label(pin.name, size = 12.sp, color = colors.text2, weight = FontWeight.Bold, maxLines = 1)
            Label("-${pin.level} dBm", size = 13.sp, weight = FontWeight.ExtraBold, tabular = true, maxLines = 1)
        }
    }
}
