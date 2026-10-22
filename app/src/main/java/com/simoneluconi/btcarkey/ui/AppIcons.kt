package com.simoneluconi.btcarkey.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/** Line icons on a 24x24 grid with round 2-unit strokes; the launcher icon uses the same [Fob]. */
object AppIcons {
    val Lock = icon(
        "M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-7A1.5 1.5 0 0 1 6.5 11z",
        "M8 11V7.5a4 4 0 0 1 8 0V11",
        "M12 15v2",
    )
    val Unlock = icon(
        "M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-7A1.5 1.5 0 0 1 6.5 11z",
        "M8 11V7.5a4 4 0 0 1 7.7-1.5",
        "M12 15v2",
    )
    val Trunk = icon(
        "M4 13h16v4.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5z",
        "M6 13l1.5-4h9L18 13",
        "M12 2.5v4.5",
        "M9.5 5L12 2.5 14.5 5",
    )
    val Power = icon("M12 3v8", "M7 6.3a7 7 0 1 0 10 0")
    val Window = icon("M4 19v-7.5L9.5 5H20v14z", "M14 9v6", "M11.5 12.5L14 15l2.5-2.5")
    val Star = icon("M12 3l2.4 5.2 5.6.6-4.2 3.8 1.2 5.6L12 15.4l-5 2.8 1.2-5.6L4 8.8l5.6-.6z")
    val Hand = icon(
        "M8 13V5.5a1.5 1.5 0 0 1 3 0V11",
        "M11 10.5a1.5 1.5 0 0 1 3 0V12",
        "M14 11a1.5 1.5 0 0 1 3 0v1",
        "M17 12a1.5 1.5 0 0 1 3 0v3a6 6 0 0 1-6 6h-1.5a6 6 0 0 1-5-2.7L4.4 14.5a1.5 1.5 0 0 1 2.4-1.8L8 14",
    )
    val Key = icon(circle(8f, 15f, 4f), "M11 12l8.5-8.5", "M16 7l2 2", "M18.5 4.5l1.5 1.5")
    val Fob = icon(
        "M10 8h4a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3h-4a3 3 0 0 1-3-3v-7a3 3 0 0 1 3-3z",
        circle(12f, 13f, 2f),
        "M11 17.5h2",
        "M10 5.2a2.8 2.8 0 0 1 4 0",
        "M7.5 3.2a6.3 6.3 0 0 1 9 0",
    )
    val Car = icon("M5 16v-5l2-5h10l2 5v5", "M3 16h18v2H3z", "M5 11h14", circle(7.5f, 13.5f, 0.6f), circle(16.5f, 13.5f, 0.6f))
    val Settings = icon(
        "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12",
        circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f),
    )
    val Back = icon("M15 6l-6 6 6 6")
    val Chevron = icon("M9 6l6 6-6 6")
    val Up = icon("M6 15l6-6 6 6")
    val Down = icon("M6 9l6 6 6-6")
    val Plus = icon("M12 5v14M5 12h14")
    val Close = icon("M6 6l12 12M18 6L6 18")
    val Trash = icon("M4 7h16", "M10 11v6M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val Edit = icon("M4 20h4L19 9l-4-4L4 16z", "M13 7l4 4")
    val Bluetooth = icon("M7 7l10 10-5 5V2l5 5L7 17")
    val Battery = icon("M4 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M22 11v2")
    val Radar = icon(
        circle(12f, 12f, 1.2f),
        "M8.5 15.5a5 5 0 0 1 0-7", "M15.5 8.5a5 5 0 0 1 0 7",
        "M5.6 18.4a9 9 0 0 1 0-12.8", "M18.4 5.6a9 9 0 0 1 0 12.8",
    )
    val List = icon("M9 6h11M9 12h11M9 18h11", circle(4.5f, 6f, 0.8f), circle(4.5f, 12f, 0.8f), circle(4.5f, 18f, 0.8f))
    val Check = icon("M5 12.5l4.5 4.5L19 7.5")
    val Copy = icon("M10 9h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z", "M5 15V5a1 1 0 0 1 1-1h10")
    val Share = icon("M12 3v12", "M8 7l4-4 4 4", "M5 12v7a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-7")
    val Import = icon("M12 3v12", "M8 11l4 4 4-4", "M5 12v7a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-7")
    val Refresh = icon("M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val Shield = icon("M12 3l7 3v5c0 5-3.5 8.5-7 10-3.5-1.5-7-5-7-10V6z")
    val Card = icon("M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z", "M8 10a3 3 0 0 1 0 4", "M11 8.5a6 6 0 0 1 0 7")
    val Plate = icon("M4 7h16a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", "M7 12h10")
    val Info = icon(circle(12f, 12f, 9f), "M12 11v5", circle(12f, 8f, 0.4f))
    val Warning = icon("M12 3.5L21.5 20h-19z", "M12 10v4.5", circle(12f, 17.2f, 0.4f))
    val Eye = icon("M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    val Sun = icon(
        circle(12f, 12f, 4f),
        "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
    )
    val Moon = icon("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val Tool = icon("M14.5 6.5a4 4 0 0 0 5 5l-8 8a2.1 2.1 0 0 1-3-3z", "M14.5 6.5L17 4l3 3-2.5 2.5")

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(vararg strokes: String): ImageVector {
        val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in strokes) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}

@Composable
fun Icon(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(color))
}
