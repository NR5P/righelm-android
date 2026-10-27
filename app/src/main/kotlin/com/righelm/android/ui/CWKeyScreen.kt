package com.righelm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.righelm.android.RigController
import com.righelm.core.KeyerMode
import com.righelm.core.fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keyer settings and the text line while the phone key is armed. */
@Composable
fun CWKeyCard(rig: RigController) {
    val st = rig.cwStatus
    val wpm = maxOf(rig.state.keySpeed, 4)
    var text by remember { mutableStateOf("") }
    fun send() {
        rig.cwSendText(text)
        text = ""
    }
    Card(Modifier.border(1.5.dp, Palette.red.copy(alpha = 0.7f), RoundedCornerShape(14.dp))) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("● PHONE KEY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.red)
                if (!st.ready && st.error == null) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = Palette.amber, strokeWidth = 2.dp)
                    Text("opening the keying line…", fontSize = 12.sp, color = Palette.amber)
                } else if (st.sendingText) {
                    Text("sending…", fontSize = 12.sp, color = Palette.amber)
                }
                Spacer(Modifier.weight(1f))
                LEDButton("Radio key", false, Modifier.widthIn(min = 84.dp)) { rig.setPhoneKey(false) }
            }
            LEDRow(KeyerMode.entries.map { it to it.label }, rig.keyerMode) { rig.keyerMode = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Caption("SPEED")
                SpeedButton("−", wpm - 1, rig)
                Text("$wpm WPM", Modifier.widthIn(min = 70.dp), fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                SpeedButton("+", wpm + 1, rig)
                Spacer(Modifier.weight(1f))
                Switch(
                    rig.cwSidetone, { rig.cwSidetone = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.cyan),
                    modifier = Modifier.semantics { contentDescription = "Sidetone" },
                )
                Icon(
                    if (rig.cwSidetone) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null,
                    tint = if (rig.cwSidetone) Palette.cyan else Palette.dim,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("CQ CQ DE …") },
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                val canSend = text.isNotBlank() && st.ready
                LEDButton("Send", canSend, Modifier.widthIn(min = 60.dp).alpha(if (canSend) 1f else 0.5f), Palette.red) { if (canSend) send() }
                LEDButton("Stop", false, Modifier.widthIn(min = 56.dp)) { rig.cwStop() }
            }
            st.note?.let { Text(it, fontSize = 12.sp, color = Palette.amber) }
            Text(
                if (rig.keyerMode == KeyerMode.STRAIGHT) {
                    "Hold the key below to transmit. Typed text is sent at the speed above."
                } else {
                    "Left paddle = dit, right = dah; hold both to squeeze. Touching the paddles stops typed text."
                },
                fontSize = 12.sp, color = Palette.secondary,
            )
        }
    }
}

/** − / +: sets the radio's keyer speed, repeating while held. */
@Composable
private fun SpeedButton(label: String, to: Int, rig: RigController) {
    val target by rememberUpdatedState(to)
    val scope = rememberCoroutineScope()
    fun set() = rig.send("KS%03d;".fmt(target.coerceIn(4, 60)))
    Box(
        Modifier
            .size(40.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(9.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    set()
                    val repeat = scope.launch {
                        delay(400)
                        while (true) {
                            set()
                            delay(150)
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
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The paddles (or straight key), pinned above the tab bar in place of
 * push-to-talk while the phone key is armed. Multi-touch with no delay:
 * the left half is dit, the right half dah, and each finger stays on the
 * paddle it first touched (so thumbs on both halves squeeze).
 */
@Composable
fun PaddleBar(rig: RigController) {
    var dit by remember { mutableStateOf(false) }
    var dah by remember { mutableStateOf(false) }
    val straight = rig.keyerMode == KeyerMode.STRAIGHT
    val ready = rig.cwStatus.ready
    val view = LocalView.current
    fun report(d: Boolean, a: Boolean) {
        if (d == dit && a == dah) return
        if ((d && !dit) || (a && !dah)) Haptics.tick(view)
        dit = d
        dah = a
        rig.cwPaddles(d, a)
    }
    // Taken off screen with a finger down: let go.
    DisposableEffect(Unit) { onDispose { rig.cwPaddles(false, false) } }
    Box(Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .alpha(if (ready) 1f else 0.45f)
                .semantics { contentDescription = "CW key" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // Each finger and whether it is on the dit side.
                        val fingers = mutableMapOf<PointerId, Boolean>()
                        try {
                            do {
                                val e = awaitPointerEvent()
                                for (c in e.changes) {
                                    if (c.pressed) {
                                        if (c.id !in fingers) fingers[c.id] = c.position.x < size.width / 2
                                    } else {
                                        fingers.remove(c.id)
                                    }
                                    c.consume()
                                }
                                report(true in fingers.values, false in fingers.values)
                            } while (e.changes.any { it.pressed })
                        } finally {
                            report(false, false)
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (straight) {
                Pad("KEY", dit || dah || rig.cwStatus.down, "hold to transmit")
            } else {
                Pad("DIT", dit, "left paddle")
                Pad("DAH", dah, "right paddle")
            }
        }
    }
}

@Composable
private fun RowScope.Pad(name: String, lit: Boolean, hint: String) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(if (lit) Palette.red else Color(0.25f, 0.08f, 0.08f), shape)
            .border(1.5.dp, Palette.red.copy(alpha = 0.6f), shape),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(hint, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}
