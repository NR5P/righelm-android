package com.righelm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.righelm.android.RigController
import com.righelm.core.AGC
import com.righelm.core.Roofing
import com.righelm.core.WidthClass
import com.righelm.core.fmt
import com.righelm.core.widthChoices
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Receiver, DSP, transmitter, clarifier and CW settings. */
@Composable
fun ControlsScreen(rig: RigController) {
    val s = rig.state
    CompositionLocalProvider(LocalControlsEnabled provides rig.isConnected) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScreenTitle("Controls")

            Section("Receiver") {
                CatSlider(rig, "AF gain", s.afGain, 0..255) { "AG0%03d;".fmt(it) }
                CatSlider(rig, "RF gain", s.rfGain, 0..255) { "RG0%03d;".fmt(it) }
                CatSlider(rig, "Squelch", s.squelch, 0..255) { "SQ0%03d;".fmt(it) }
                CatPicker(rig, "AGC", minOf(s.agc, 4), AGC.choices, AGC.label(s.agc)) { "GT0$it;" }
                CatSegments(rig, "Preamp", s.preamp, listOf(0 to "IPO", 1 to "AMP1", 2 to "AMP2")) { "PA0$it;" }
                CatSegments(rig, "ATT", s.att, listOf(0 to "OFF", 1 to "6", 2 to "12", 3 to "18")) { "RA0$it;" }
                CatPicker(
                    rig, "Roofing", roofingSetIndex(s.roofing),
                    Roofing.choices.mapIndexed { i, c -> i to c.second }, Roofing.label(s.roofing) ?: "—",
                ) { "RF0${Roofing.choices[it].first};" }
            }

            Section("Filter") {
                CatToggle(rig, "Narrow", s.narrow) { "NA0${if (it) 1 else 0};" }
                val m = s.rxMode
                if (m != null && s.widthClass(m) != WidthClass.FIXED) {
                    val choices = widthChoices(s.widthClass(m), s.narrow)
                    CatPicker(
                        rig, "Width", s.widthIndex, choices.map { it.first to "${it.second} Hz" },
                        s.widthHz?.let { "$it Hz" } ?: "—",
                    ) { "SH0%02d;".fmt(it) }
                }
                CatSlider(rig, "IF shift", s.ifShift, -1200..1200, step = 20, unit = " Hz") {
                    "IS0%s%04d;".fmt(if (it < 0) "-" else "+", abs(it))
                }
            }

            Section("Noise and notch") {
                CatSegments(rig, "Noise blanker", s.nb, listOf(0 to "OFF", 1 to "ON", 2 to "WIDE")) { "NB0$it;" }
                CatSlider(rig, "NB level", s.nbLevel, 0..100) { "NL0%03d;".fmt(it) }
                CatToggle(rig, "Noise reduction (DNR)", s.nr) { "NR0${if (it) 1 else 0};" }
                CatSlider(rig, "DNR level", maxOf(s.nrLevel, 1), 1..15) { "RL0%02d;".fmt(it) }
                CatToggle(rig, "Auto notch (DNF)", s.autoNotch) { "BC0${if (it) 1 else 0};" }
                CatToggle(rig, "Manual notch", s.manualNotch) { "BP00%03d;".fmt(if (it) 1 else 0) }
                CatSlider(rig, "Notch frequency", maxOf(s.notchFreq, 1) * 10, 10..4000, step = 10, unit = " Hz") {
                    "BP01%03d;".fmt((it / 10).coerceIn(1, 400))
                }
                CatSegments(rig, "Contour", s.contourMode, listOf(0 to "OFF", 1 to "CONTOUR", 2 to "APF")) { "CO00%02d;".fmt(it) }
                if (s.contourMode == 2) {
                    CatSlider(rig, "APF offset", (s.apfFreq - 25) * 10, -250..250, step = 10, unit = " Hz") {
                        "CO02%02d;".fmt((it / 10 + 25).coerceIn(0, 50))
                    }
                } else if (s.contourMode == 1) {
                    CatSlider(rig, "Contour frequency", maxOf(s.contourFreq, 1) * 100, 100..4000, step = 100, unit = " Hz") {
                        "CO01%02d;".fmt((it / 100).coerceIn(1, 40))
                    }
                }
            }

            Section(
                "Transmitter",
                footer = "These change the radio's settings; none of them transmits. VOX can key the radio, so it follows the transmit lock.",
            ) {
                CatSlider(rig, "Power", s.power, 5..100, unit = " W") { "PC%03d;".fmt(it) }
                CatSlider(rig, "Mic gain", s.micGain, 0..100) { "MG%03d;".fmt(it) }
                CatToggle(rig, "Speech processor", s.processor) { "PR0${if (it) 1 else 0};" }
                CatSlider(rig, "Processor level", s.procLevel, 0..100) { "PL%03d;".fmt(it) }
                CatToggle(rig, "Parametric mic EQ", s.micEQ) { "PR1${if (it) 1 else 0};" }
                CatToggle(rig, "Monitor", s.monitor) { "ML0%03d;".fmt(if (it) 1 else 0) }
                CatSlider(rig, "Monitor level", maxOf(s.monitorLevel, 1), 1..100) { "ML1%03d;".fmt(it) }
                CatToggle(rig, "VOX", s.vox, transmits = true) { "VX${if (it) 1 else 0};" }
                CatSlider(rig, "VOX gain", s.voxGain, 0..100) { "VG%03d;".fmt(it) }
                CatSlider(rig, "VOX delay", maxOf(s.voxDelay, 30), 30..3000, step = 10, unit = " ms") { "VD%04d;".fmt(it) }
            }

            Section("Clarifier", footer = "The FTDX3000 has one clarifier offset, shared by RX and TX clarifier.") {
                CatToggle(rig, "RX clarifier", s.rxClar) { "RT${if (it) 1 else 0};" }
                CatToggle(rig, "TX clarifier", s.txClar) { "XT${if (it) 1 else 0};" }
                LabeledValue("Offset", "%+d Hz".fmt(s.infoA.clarOffset), if (s.rxClar || s.txClar) Palette.amber else Palette.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in listOf(-100, -10, 10, 100)) {
                        LEDButton(if (d > 0) "+$d" else "−${-d}", false, Modifier.weight(1f)) {
                            rig.send(if (d < 0) "RD%04d;".fmt(-d) else "RU%04d;".fmt(d))
                        }
                    }
                    LEDButton("CLEAR", false, Modifier.weight(1.4f)) { rig.send("RC;") }
                }
            }

            Section("CW") {
                CatToggle(rig, "Keyer", s.keyer) { "KR${if (it) 1 else 0};" }
                CatSlider(rig, "Speed", maxOf(s.keySpeed, 4), 4..60, unit = " WPM") { "KS%03d;".fmt(it) }
                CatSlider(rig, "Pitch", 300 + 10 * s.keyPitch, 300..1050, step = 10, unit = " Hz") {
                    "KP%02d;".fmt(((it - 300) / 10).coerceIn(0, 75))
                }
                CatToggle(rig, "Break-in", s.breakIn) { "BI${if (it) 1 else 0};" }
                CatSlider(rig, "Break-in delay", maxOf(s.bkDelay, 30), 30..3000, step = 10, unit = " ms") { "SD%04d;".fmt(it) }
            }

            Section("Dial") {
                CatToggle(rig, "Dial lock", s.lock and 1 == 1) { if (it) "LK1;" else "LK0;" }
                CatToggle(rig, "Fast tuning", s.fast and 1 == 1) { if (it) "FS1;" else "FS0;" }
            }
        }
    }
}

/** Answer codes (with AUTO variants) to the Set choices' index. */
private fun roofingSetIndex(code: Char): Int = when (code.uppercaseChar()) {
    '1' -> 1
    '2' -> 2
    '3' -> 3
    '7' -> 4
    '8' -> 5
    else -> 0
}

@Composable
fun ScreenTitle(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp), fontSize = 30.sp, fontWeight = FontWeight.Bold)
}

/** A titled group of rows, like a settings list section. */
@Composable
fun Section(title: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SectionHeader(title)
    Card(padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
    if (footer != null) SectionFooter(footer)
}

/**
 * A slider that follows the radio, sends while it is dragged (a few times
 * a second), and always sends where it was let go.
 */
@Composable
fun CatSlider(
    rig: RigController,
    title: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    unit: String = "",
    command: (Int) -> String,
) {
    val enabled = LocalControlsEnabled.current
    var local by remember { mutableStateOf<Int?>(null) }
    var lastSent by remember { mutableLongStateOf(0L) }
    var released by remember { mutableIntStateOf(0) }
    // Keep showing it until the radio has answered.
    LaunchedEffect(released) {
        if (released > 0) {
            delay(800)
            local = null
        }
    }
    val shown = local ?: value
    fun snap(v: Float): Int = (range.first + ((v - range.first) / step).roundToInt() * step).coerceIn(range.first, range.last)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text("$shown$unit", fontFamily = Mono, fontSize = 14.sp, color = Palette.amber)
        }
        Slider(
            value = shown.coerceIn(range.first, range.last).toFloat(),
            onValueChange = { v ->
                val i = snap(v)
                local = i
                if (System.currentTimeMillis() - lastSent > 150) {
                    lastSent = System.currentTimeMillis()
                    rig.send(command(i))
                }
            },
            onValueChangeFinished = {
                local?.let { rig.send(command(it)) }
                released += 1
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = Palette.amber, activeTrackColor = Palette.amber, inactiveTrackColor = Color.White.copy(alpha = 0.12f)),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun CatToggle(
    rig: RigController,
    title: String,
    isOn: Boolean,
    /** Turning it on can key the radio: it obeys the transmit lock. */
    transmits: Boolean = false,
    command: (Boolean) -> String,
) {
    val enabled = LocalControlsEnabled.current
    SwitchRow(title, isOn, enabled) { on ->
        if (on && transmits && !rig.canTransmit) {
            rig.showLockedHint = true
        } else {
            rig.send(command(on))
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, color: Color = Palette.amber, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = color, checkedThumbColor = Color.Black),
        )
    }
}

@Composable
fun <T> CatPicker(
    rig: RigController,
    title: String,
    selection: T,
    options: List<Pair<T, String>>,
    display: String,
    command: (T) -> String,
) {
    PickerRow(title, selection, options, display, LocalControlsEnabled.current) { rig.send(command(it)) }
}

/** A row showing the current choice; tapping it lists the others. */
@Composable
fun <T> PickerRow(title: String, selection: T, options: List<Pair<T, String>>, display: String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.padding(vertical = 6.dp).alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f))
            Text(display, color = Palette.amber)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.UnfoldMore, null, Modifier.size(16.dp), tint = Palette.secondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, label) in options) {
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (value == selection) Icon(Icons.Filled.Check, null, tint = Palette.amber) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
fun CatSegments(rig: RigController, title: String, selection: Int, options: List<Pair<Int, String>>, command: (Int) -> String) {
    Column {
        Text(title)
        Spacer(Modifier.height(6.dp))
        LEDRow(options, selection) { rig.send(command(it)) }
    }
}
