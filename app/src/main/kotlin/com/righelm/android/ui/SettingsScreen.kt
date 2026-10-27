package com.righelm.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.righelm.android.BuildConfig
import com.righelm.android.R
import com.righelm.android.RigController
import com.righelm.core.KeyerMode
import com.righelm.core.fmt

@Composable
fun SettingsScreen(rig: RigController) {
    val sliderColors = SliderDefaults.colors(thumbColor = Palette.amber, activeTrackColor = Palette.amber, inactiveTrackColor = Color.White.copy(alpha = 0.12f))
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle("Settings")

        Section("Station") {
            LabeledValue("Name", rig.serverName)
            rig.target?.let { LabeledValue("Address", "${it.host}:${it.port}") }
            LabeledValue("Status", statusText(rig))
            rig.targetBeacon?.let { LabeledValue("Accepts phone mic", if (it.mic) "Yes" else "No") }
            TextButton(onClick = { rig.disconnect() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Disconnect", color = Palette.red)
            }
        }

        Section("Receive audio", footer = "Keeps playing with the screen off or in another app.") {
            SwitchRow("Play the radio's audio", rig.receiveAudio) { rig.receiveAudio = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = Palette.secondary)
                Slider(rig.volume, { rig.volume = it }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 0f..2f, colors = sliderColors)
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Palette.secondary)
            }
            val a = rig.audioStats
            if (a.connected) {
                Text("Stream: ${a.rate / 1000} kHz · ${a.packets} packets · ${a.lost} lost · ${rig.audioDropouts} dropouts", fontSize = 13.sp, color = Palette.secondary)
            } else if (a.error != null) {
                Text(a.error!!, fontSize = 13.sp, color = Palette.red)
            }
        }

        Section(
            "Transmit",
            footer = "While this is off, nothing that keys the radio leaves the phone: PTT, MOX, TUNE, VOX and the CW key are refused. " +
                "It stays on until you turn it off. The radio is also let go when the app goes to the background, " +
                "after the time-out, when a phone call takes the audio, and by the station if the phone drops off the network.",
        ) {
            SwitchRow("Allow transmit from this phone", rig.transmitAllowed, color = Palette.red) { rig.transmitAllowed = it }
            PickerRow(
                "Let go after", rig.txTimeoutSeconds,
                listOf(60, 120, 180, 300, 600).map { it to "${it / 60} min" }, "${rig.txTimeoutSeconds / 60} min",
            ) { rig.txTimeoutSeconds = it }
        }

        Section(
            "Phone microphone",
            footer = "Switch between the radio's and the phone's mic on the Operate page. The phone mic sets the radio's AM, FM and SSB " +
                "MIC SEL menus (053, 085, 103) to USB and puts them back when you switch off or disconnect. " +
                "Speak at a normal level: peaks should stay out of the red.",
        ) {
            LabeledValue("Microphone", if (rig.phoneMic) "Phone (MIC SEL = USB)" else "Radio")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mic gain")
                Slider(rig.micGain, { rig.micGain = it }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 0.25f..4f, colors = sliderColors)
                Text("%.1f×".fmt(rig.micGain), Modifier.width(44.dp), fontFamily = Mono, fontSize = 13.sp)
            }
            if (rig.phoneMic) MicMeter(rig, Palette.green)
        }

        Section(
            "CW key",
            footer = "RADIO KEY / PHONE KEY on the Operate page picks where CW comes from. The app always starts on the radio's key. " +
                "The phone key puts the radio in CW with break-in on, and the paddles take the place of push-to-talk. " +
                "The speed is the radio's keyer speed. It goes back to the radio's key when the station disconnects, the radio leaves CW, " +
                "transmit is locked, the app goes to the background, or after 10 minutes unused. A key held down longer than 6 seconds " +
                "is released, and the station lets go if the phone goes quiet.",
        ) {
            PickerRow("Phone key type", rig.keyerMode, KeyerMode.entries.map { it to it.label }, rig.keyerMode.label) { rig.keyerMode = it }
            SwitchRow("Sidetone on this phone", rig.cwSidetone) { rig.cwSidetone = it }
        }

        Section("General") {
            SwitchRow("Keep the screen on", rig.keepAwake) { rig.keepAwake = it }
            SwitchRow("Reconnect when the app opens", rig.reconnectOnLaunch) { rig.reconnectOnLaunch = it }
        }

        Section("Log") {
            if (rig.notes.isEmpty()) Text("Nothing yet", color = Palette.secondary)
            for (n in rig.notes.takeLast(30).reversed()) {
                Text(n, fontFamily = Mono, fontSize = 12.sp)
            }
        }

        Section(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("RigHelm", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("Remote control for a RigHelm station (Yaesu FTDX3000)", fontSize = 12.sp, color = Palette.secondary)
                    Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = Palette.secondary)
                }
            }
        }
    }
}

private fun statusText(rig: RigController): String = when (val p = rig.phase) {
    RigController.Phase.Idle -> "Not connected"
    RigController.Phase.Connecting -> "Connecting…"
    RigController.Phase.Connected -> if (rig.state.powerOn == false) "Connected, radio off" else "Connected"
    is RigController.Phase.Failed -> p.why
}
