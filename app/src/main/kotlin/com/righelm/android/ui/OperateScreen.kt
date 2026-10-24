package com.righelm.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.righelm.android.R
import com.righelm.android.RigController
import com.righelm.core.Band
import com.righelm.core.Frequency
import com.righelm.core.fmt
import com.righelm.core.Meter
import com.righelm.core.MeterCal
import com.righelm.core.Mode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2

/** The main page: frequency, meters, tuning, band and mode, and transmit. */
@Composable
fun OperateScreen(rig: RigController) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderBar(rig)
            ConnectionBanner(rig)
            FrequencyCard(rig)
            MeterCard(rig)
            TuningCard(rig)
            BandModeCard(rig)
            VFOCard(rig)
            TransmitCard(rig)
        }
        PTTBar(rig)
    }
}

// MARK: - Header

@Composable
private fun HeaderBar(rig: RigController) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("RigHelm", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (rig.isConnected) Palette.green else Palette.amber, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(rig.serverName, fontSize = 12.sp, color = Palette.secondary, maxLines = 1)
            }
        }
        if (rig.state.transmitting) {
            Text(
                "TX",
                Modifier.background(Palette.red, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (rig.audioStats.connected && rig.receiveAudio) {
            Icon(
                if (rig.audioStats.rxLevelDb > -70) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                "Receiving audio",
                tint = Palette.cyan,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Palette.secondary) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Disconnect", color = Palette.red) }, onClick = {
                    menu = false
                    rig.disconnect()
                })
            }
        }
    }
}

@Composable
private fun ConnectionBanner(rig: RigController) {
    when (val p = rig.phase) {
        RigController.Phase.Connecting -> Banner("Connecting to ${rig.serverName}…", Palette.amber, spinner = true)
        is RigController.Phase.Failed -> Banner("Reconnecting: ${p.why}", Palette.red, spinner = true)
        else -> {
            val b = rig.targetBeacon
            if (rig.state.powerOn == false) {
                Banner("The radio is switched off", Palette.amber, spinner = false)
            } else if (b != null && b.radio != "connected") {
                Banner("The station's radio is ${if (b.radio == "not-answering") "not answering" else "not connected"}", Palette.amber, spinner = false)
            }
        }
    }
}

@Composable
private fun Banner(text: String, color: Color, spinner: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) {
            CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// MARK: - Frequency

@Composable
private fun FrequencyCard(rig: RigController) {
    val s = rig.state
    var entering by remember { mutableStateOf(false) }
    val view = LocalView.current
    val stepPx = with(LocalDensity.current) { 14.dp.toPx() }
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag(if (s.rxOnB) "VFO B" else "VFO A", Palette.amber)
            s.rxMode?.let { Tag(it.label, Palette.cyan) }
            s.widthHz?.let { w -> Tag(if (w >= 1000) "%.1f kHz".fmt(w / 1000.0) else "$w Hz", Palette.dim) }
            Band.of(rig.displayRxFreq)?.let { Tag("${it.label} m", Palette.dim) }
            Spacer(Modifier.weight(1f))
            if (s.busy) Tag("BUSY", Palette.green)
            if (s.lock and 1 == 1) Icon(Icons.Filled.Lock, "Dial locked", Modifier.size(14.dp), tint = Palette.red)
        }
        Spacer(Modifier.height(8.dp))
        // Drag sideways to tune; tap to type a frequency.
        Box(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onTap = { entering = true }) }
                .pointerInput(Unit) {
                    var total = 0f
                    var done = 0
                    detectHorizontalDragGestures(
                        onDragStart = {
                            total = 0f
                            done = 0
                        },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            total += dx
                            val steps = (total / stepPx).toInt()
                            if (steps != done) {
                                rig.tune(steps - done)
                                done = steps
                                Haptics.tick(view)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            FrequencyText(rig.displayRxFreq, 46.sp, if (s.transmitting && !s.split) Palette.red else Palette.amber)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.rxOnB) "A" else "B", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.dim)
            FrequencyText(if (s.rxOnB) s.freqA else s.freqB, 20.sp, Palette.cyan.copy(alpha = 0.85f))
            Text((if (s.rxOnB) s.modeA else s.modeB)?.label ?: "", fontSize = 12.sp, color = Palette.secondary)
            Spacer(Modifier.weight(1f))
            if (s.split) Tag("SPLIT", Palette.amber)
            if (s.rxClar || s.txClar) Tag("CLAR %+d".fmt(s.infoA.clarOffset), Palette.amber)
        }
    }
    if (entering) {
        FrequencyEntry(rig.displayRxFreq, set = { rig.setRxFrequency(it) }, dismiss = { entering = false })
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** "14.074.000" with the Hz digits smaller, like a radio's display. */
@Composable
fun FrequencyText(hz: Int, size: TextUnit, color: Color) {
    val text = if (hz > 0) Frequency.format(hz) else "—.———.———"
    val split = (text.length - 3).coerceAtLeast(0)
    Text(
        buildAnnotatedString {
            append(text.substring(0, split))
            withStyle(SpanStyle(fontSize = size * 0.72f, color = color.copy(alpha = color.alpha * 0.8f))) {
                append(text.substring(split))
            }
        },
        color = color,
        maxLines = 1,
        softWrap = false,
        style = TextStyle(
            fontFamily = Mono,
            fontSize = size,
            fontWeight = FontWeight.Medium,
            shadow = Shadow(color.copy(alpha = 0.35f), Offset.Zero, 16f),
        ),
    )
}

/** Type a frequency in kHz (or MHz with a decimal point). */
@Composable
private fun FrequencyEntry(current: Int, set: (Int) -> Unit, dismiss: () -> Unit) {
    var text by remember { mutableStateOf("%.3f".fmt(current / 1000.0)) }
    val focus = remember { FocusRequester() }
    fun parse(s: String): Int? {
        val v = s.replace(",", ".").toDoubleOrNull() ?: return null
        if (v <= 0) return null
        // Under 100 is MHz (14.074), otherwise kHz (14074).
        val hz = if (v < 100) Math.round(v * 1_000_000).toInt() else Math.round(v * 1000).toInt()
        return hz.takeIf { it in Frequency.MIN..Frequency.MAX }
    }
    val parsed = parse(text)
    fun commit() {
        parsed?.let(set)
        dismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Palette.card,
        title = { Text("Frequency") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 26.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (parsed != null) commit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(6.dp))
                Text("In kHz (14074) or MHz (14.074).", fontSize = 12.sp, color = Palette.secondary)
            }
        },
        confirmButton = { TextButton(onClick = { commit() }, enabled = parsed != null) { Text("Set") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

// MARK: - Meters

@Composable
private fun MeterCard(rig: RigController) {
    val s = rig.state
    Card(padding = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (s.transmitting) {
                val po = MeterCal.interpolate(MeterCal.po, s.meter(Meter.PO) ?: 0)
                MeterBar("PO", po / 100, "%.0f W".fmt(po), Palette.amber, ticks = listOf(0.25 to "25", 0.5 to "50", 0.75 to "75"))
                val swrRaw = s.meter(Meter.SWR) ?: 0
                val swr = MeterCal.interpolate(MeterCal.swr, swrRaw)
                MeterBar("SWR", swrRaw / 255.0, "%.1f".fmt(swr), Palette.cyan, redFrom = 89.0 / 255)
                val alc = (s.meter(Meter.ALC) ?: 0) / 255.0
                MeterBar("ALC", alc, "%.0f%%".fmt(alc * 100), Palette.cyan, redFrom = 0.5)
            } else {
                MeterBar(
                    "S", s.sMeter / 255.0, MeterCal.sText(s.sMeter), Palette.green, redFrom = 130.0 / 255,
                    ticks = listOf(27.0 / 255 to "3", 55.0 / 255 to "5", 80.0 / 255 to "7", 130.0 / 255 to "9", 172.0 / 255 to "+20", 220.0 / 255 to "+40"),
                    height = 18.dp,
                )
                if (rig.receiveAudio) {
                    val a = rig.audioStats
                    val lvl = if (a.connected) a.rxLevelDb.toDouble() else -100.0
                    MeterBar("AUDIO", (lvl + 70) / 70, if (a.connected) "%.0f dB".fmt(lvl) else "—", Palette.cyan.copy(alpha = 0.7f), height = 6.dp)
                }
            }
            if (s.highSWR) Text("HI-SWR", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.red)
        }
    }
}

// MARK: - Tuning

private val tuneSteps = listOf(10, 100, 500, 1000, 5000, 10_000, 100_000)

@Composable
private fun TuningCard(rig: RigController) {
    val view = LocalView.current
    Card {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(-1, rig)
            HelmKnob(Modifier.size(168.dp)) {
                rig.tune(it)
                Haptics.tick(view)
            }
            StepButton(1, rig)
        }
        Spacer(Modifier.height(12.dp))
        LEDRow(tuneSteps.map { it to if (it >= 1000) "${it / 1000}k" else "$it" }, rig.tuneStep) { rig.tuneStep = it }
    }
}

/** ◀ / ▶: one step per tap, repeating while held. */
@Composable
private fun StepButton(dir: Int, rig: RigController) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(52.dp)
            .background(Color.White.copy(alpha = 0.07f), CircleShape)
            .semantics { contentDescription = if (dir < 0) "Tune down" else "Tune up" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    fun step() {
                        rig.tune(dir)
                        Haptics.tick(view)
                    }
                    step()
                    val repeat = scope.launch {
                        delay(400)
                        while (true) {
                            step()
                            delay(80)
                        }
                    }
                    try {
                        do {
                            val e = awaitPointerEvent()
                        } while (e.changes.any { it.pressed })
                    } finally {
                        repeat.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (dir < 0) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight, null, Modifier.size(30.dp))
    }
}

/**
 * The tuning knob, drawn like the RigHelm logo's helm wheel. Turning it
 * one notch (12°) moves the dial one step.
 */
@Composable
private fun HelmKnob(modifier: Modifier, onSteps: (Int) -> Unit) {
    var angle by remember { mutableFloatStateOf(0f) }
    val steps by rememberUpdatedState(onSteps)
    val notch = 12f
    Canvas(
        modifier
            .semantics { contentDescription = "Tuning knob" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    fun angleOf(p: Offset) = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                    var last = angleOf(down.position)
                    var residue = 0f
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val a = angleOf(change.position)
                        var d = a - last
                        if (d > 180) d -= 360
                        if (d < -180) d += 360
                        angle += d
                        residue += d
                        val n = (residue / notch).toInt()
                        if (n != 0) {
                            residue -= n * notch
                            steps(n)
                        }
                        last = a
                        change.consume()
                    }
                }
            },
    ) {
        val s = minOf(size.width, size.height)
        val c = center
        rotate(angle, c) {
            // Spokes and rim.
            for (i in 0 until 4) {
                rotate(i * 45f, c) {
                    val w = s * 0.045f
                    val h = s * 0.98f
                    drawRoundRect(Palette.wood, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(w / 2))
                }
            }
            drawCircle(Palette.wood, s * 0.33f, c, style = Stroke(s * 0.06f))
            // The knob in the middle.
            val r = s * 0.18f
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF616161), Color(0xFF292929), Color(0xFF121212)),
                    center = Offset(c.x - r * 0.2f, c.y - r * 0.3f),
                    radius = s * 0.3f,
                ),
                r, c,
            )
            drawCircle(Color.Black, r, c, style = Stroke(2.dp.toPx()))
            for (i in 0 until 30) {
                rotate(i * 12f, c) {
                    val w = 1.5.dp.toPx()
                    val h = s * 0.025f
                    drawRect(Color.Black.copy(alpha = 0.85f), Offset(c.x - w / 2, c.y - s * 0.165f - h / 2), Size(w, h))
                }
            }
            val iw = s * 0.024f
            val ih = s * 0.1f
            drawRoundRect(Palette.amber, Offset(c.x - iw / 2, c.y - s * 0.09f - ih / 2), Size(iw, ih), CornerRadius(iw / 2))
        }
    }
}

// MARK: - Band and mode

private val mainModes = listOf(Mode.LSB, Mode.USB, Mode.CW, Mode.CW_R, Mode.AM, Mode.FM, Mode.DATA_LSB, Mode.DATA_USB)

@Composable
private fun BandModeCard(rig: RigController) {
    val s = rig.state
    val current = Band.of(rig.displayRxFreq)?.code
    var more by remember { mutableStateOf(false) }
    Card {
        Caption("BAND")
        Spacer(Modifier.height(8.dp))
        Grid(Band.all, columns = 6) { b, m ->
            LEDButton(b.label, b.code == current, m) { rig.send(b.setCommand) }
        }
        Spacer(Modifier.height(12.dp))
        Caption("MODE")
        Spacer(Modifier.height(8.dp))
        Grid(mainModes, columns = 4) { mode, m ->
            LEDButton(mode.label, s.rxMode == mode, m, Palette.cyan) { rig.setMode(mode) }
        }
        Box(Modifier.padding(top = 4.dp)) {
            Row(
                Modifier.clickable(enabled = LocalControlsEnabled.current) { more = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.MoreHoriz, null, Modifier.size(18.dp), tint = Palette.amber)
                Spacer(Modifier.width(6.dp))
                Text("More modes", fontSize = 13.sp, color = Palette.amber)
            }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                for (mode in Mode.ordered.filter { it !in mainModes }) {
                    DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                        more = false
                        rig.setMode(mode)
                    })
                }
            }
        }
    }
}

/** Equal-width cells, [columns] to a row. */
@Composable
fun <T> Grid(items: List<T>, columns: Int, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in items.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (item in row) cell(item, Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun VFOCard(rig: RigController) {
    val s = rig.state
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LEDButton("A ▸ B", false, Modifier.weight(1f)) { rig.send("AB;") }
            LEDButton("B ▸ A", false, Modifier.weight(1f)) { rig.send("BA;") }
            LEDButton("A ⇄ B", false, Modifier.weight(1f)) { rig.send("SV;") }
            LEDButton("SPLIT", s.split, Modifier.weight(1f)) { rig.toggleSplit() }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LEDButton("RX A", !s.rxOnB, Modifier.weight(1f), Palette.cyan) { rig.send("FR0;") }
            LEDButton("RX B", s.rxOnB, Modifier.weight(1f), Palette.cyan) { rig.send("FR4;") }
            val locked = s.lock and 1 == 1
            LEDButton("LOCK", locked, Modifier.weight(1f), Palette.red) { rig.send(if (locked) "LK0;" else "LK1;") }
            LEDButton("ATU", s.tuner != 0, Modifier.weight(1f)) { rig.send(if (s.tuner != 0) "AC000;" else "AC001;") }
        }
    }
}

// MARK: - Transmit

@Composable
private fun TransmitCard(rig: RigController) {
    val s = rig.state
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("TRANSMIT", Modifier.weight(1f))
            if (!rig.transmitAllowed) {
                Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = Palette.red)
                Spacer(Modifier.width(4.dp))
                Text("Locked", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.red)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LEDButton(if (rig.phoneMic) "PHONE MIC" else "RADIO MIC", rig.phoneMic, Modifier.weight(1f), Palette.red) {
                rig.switchPhoneMic(!rig.phoneMic)
            }
            LEDButton("MOX", rig.latched || (s.transmitting && rig.txStartedAt != null), Modifier.weight(1f), Palette.red) {
                rig.toggleLatch()
            }
            LEDButton(if (s.tuner == 2) "TUNING" else "TUNE", s.tuner == 2, Modifier.weight(1f)) { rig.startTune() }
        }
        if (rig.phoneMic) {
            Spacer(Modifier.height(10.dp))
            MicMeter(rig, if (rig.sendingMic) Palette.red else Palette.green)
        }
        Spacer(Modifier.height(10.dp))
        Text(statusLine(rig), fontSize = 12.sp, color = if (rig.sendingMic) Palette.red else Palette.secondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption("ANT", Modifier.width(32.dp))
            for (a in 1..3) {
                LEDButton("$a", s.antenna == a, Modifier.weight(1f), Palette.cyan) { rig.send("AN0$a;") }
            }
        }
    }
}

@Composable
fun MicMeter(rig: RigController, color: Color) {
    MeterBar(
        "MIC", (rig.micLevelDb + 60.0) / 60, if (rig.micLevelDb > -99) "%.0f dB".fmt(rig.micLevelDb) else "—",
        color, redFrom = 54.0 / 60, height = 8.dp,
    )
}

private fun statusLine(rig: RigController): String {
    val s = rig.state
    val why = rig.transmitBlockReason
    if (why != null && (!rig.transmitAllowed || !rig.isConnected)) return why
    if (rig.phoneMic) {
        if (rig.sendingMic) return "Sending the phone's microphone to the radio"
        val m = s.txMode
        if (s.transmitting && m != null && !m.isVoice) return "${m.label} does not take the microphone"
        if (rig.targetBeacon?.mic == false) return "⚠︎ The station does not accept microphone audio (RigHelm → Remote → Accept microphone audio)"
        return "Hold the button below to talk through the phone"
    }
    return "Radio mic: the station's own microphone is used"
}

/** Hold-to-talk, pinned above the tab bar. */
@Composable
private fun PTTBar(rig: RigController) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val txing = rig.state.transmitting
    val locked = !rig.canTransmit
    val started = rig.txStartedAt
    val now by produceState(System.currentTimeMillis(), started) {
        while (started != null) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    // Never stay keyed because the bar went away mid-press.
    DisposableEffect(Unit) { onDispose { if (pressed) rig.pttUp() } }
    val shape = RoundedCornerShape(16.dp)
    val fg = if (locked) Color.White.copy(alpha = 0.45f) else Color.White
    Box(Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .background(
                    when {
                        locked -> Color.White.copy(alpha = 0.06f)
                        txing || pressed -> Palette.red
                        else -> Color(0.35f, 0.1f, 0.09f)
                    },
                    shape,
                )
                .border(1.5.dp, Palette.red.copy(alpha = if (locked) 0.15f else 0.6f), shape)
                .semantics { contentDescription = if (locked) "Transmit locked" else "Push to talk" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        pressed = true
                        Haptics.heavy(view)
                        rig.pttDown()
                        try {
                            do {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                        } finally {
                            pressed = false
                            rig.pttUp()
                        }
                    }
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when {
                    locked -> Icons.Filled.Lock
                    txing -> Icons.Filled.Podcasts
                    else -> Icons.Filled.Mic
                },
                null, Modifier.size(22.dp), tint = fg,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    locked -> "TRANSMIT LOCKED"
                    txing -> "TRANSMITTING"
                    rig.latched -> "MOX ON"
                    else -> "HOLD TO TALK"
                },
                color = fg, fontSize = 17.sp, fontWeight = FontWeight.Black,
            )
            if (started != null && txing) {
                val secs = ((now - started) / 1000).coerceAtLeast(0)
                Spacer(Modifier.width(10.dp))
                Text("%d:%02d".fmt(secs / 60, secs % 60), color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono)
            }
        }
    }
}
