package com.righelm.android.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** RigHelm's colours (from the desktop app's widgets). */
object Palette {
    val amber = Color(255, 176, 46)
    val cyan = Color(80, 200, 255)
    val green = Color(90, 220, 120)
    val red = Color(235, 70, 60)
    val dim = Color(90, 90, 96)
    val panel = Color(14, 16, 20)
    val card = Color(24, 27, 33)
    val background = Color(9, 11, 14)
    val secondary = Color(0xFF9A9AA2)

    /** The logo's wooden wheel. */
    val wood = Brush.linearGradient(listOf(Color(1f, 0.79f, 0.54f), Color(0.88f, 0.5f, 0.19f), Color(0.59f, 0.31f, 0.11f)))
}

@Composable
fun RigHelmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.amber,
            onPrimary = Color.Black,
            secondary = Palette.cyan,
            error = Palette.red,
            background = Palette.background,
            surface = Palette.background,
            surfaceContainer = Palette.card,
            surfaceContainerHigh = Palette.card,
            surfaceContainerHighest = Color(34, 38, 46),
            surfaceContainerLow = Palette.panel,
            onSurface = Color.White,
            onSurfaceVariant = Palette.secondary,
            outline = Color.White.copy(alpha = 0.15f),
            outlineVariant = Color.White.copy(alpha = 0.08f),
        ),
    ) {
        // Text outside a Surface would otherwise default to black.
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

/** Controls are inert while the station isn't connected. */
val LocalControlsEnabled = compositionLocalOf { true }

val Mono = FontFamily.Monospace

/** A rounded dark panel, like RigHelm's frames. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.card)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), shape)
            .padding(padding),
        content = content,
    )
}

/** Small bold caption above a group ("BAND", "MODE"). */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

/** A button that lights up when on (RigHelm's "LED" buttons). */
@Composable
fun LEDButton(
    title: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Palette.amber,
    action: () -> Unit,
) {
    val enabled = LocalControlsEnabled.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (on) color else Color.White.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = action)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = if (on) Color.Black else Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
    }
}

/** A row of LED buttons sharing the width equally. */
@Composable
fun <T> LEDRow(
    options: List<Pair<T, String>>,
    selected: T?,
    modifier: Modifier = Modifier,
    color: Color = Palette.amber,
    onSelect: (T) -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in options) {
            LEDButton(label, value == selected, Modifier.weight(1f), color) { onSelect(value) }
        }
    }
}

/** A horizontal level bar with a label and value text. */
@Composable
fun MeterBar(
    label: String,
    fraction: Double,
    text: String,
    color: Color = Palette.cyan,
    /** Fraction from which the bar turns red. */
    redFrom: Double? = null,
    ticks: List<Pair<Double, String>> = emptyList(),
    height: Dp = 14.dp,
) {
    val f by animateFloatAsState(fraction.coerceIn(0.0, 1.0).toFloat(), tween(120, easing = LinearEasing), label = "meter")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(44.dp), color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(height)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            val w = maxWidth
            val r = redFrom?.toFloat()
            if (r != null && f > r) {
                Box(Modifier.width(w * r).height(height).background(color))
                Box(Modifier.offset(x = w * r).width(w * (f - r)).height(height).background(Palette.red))
            } else {
                Box(Modifier.width(w * f).height(height).background(color))
            }
            if (height >= 12.dp) {
                for ((at, t) in ticks) {
                    Text(
                        t,
                        Modifier.offset(x = w * at.toFloat() - 10.dp).width(20.dp).align(Alignment.CenterStart),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        style = TextStyle(lineHeight = 8.sp),
                    )
                }
            }
        }
        Text(
            text,
            Modifier.width(66.dp),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = Mono,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

object Haptics {
    fun tick(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    fun heavy(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
