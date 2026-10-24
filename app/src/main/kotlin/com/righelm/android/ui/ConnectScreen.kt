package com.righelm.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.righelm.android.R
import com.righelm.android.RigController
import com.righelm.android.support.SavedServer
import com.righelm.core.RemoteProtocol
import com.righelm.core.ServerBeacon
import kotlinx.coroutines.delay

/**
 * Pick a RigHelm station: the ones heard on the LAN, saved ones, or one
 * typed in by address.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(rig: RigController) {
    var asking by remember { mutableStateOf<SavedServer?>(null) }
    var addingManually by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(1200)
            refreshing = false
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            rig.refreshDiscovery()
        },
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.logo), contentDescription = null, Modifier.size(110.dp))
                    Spacer(Modifier.size(10.dp))
                    Text("RigHelm", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Control your RigHelm station from your phone",
                        color = Palette.secondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            rig.lastError?.let { err ->
                item {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = Palette.red)
                            Spacer(Modifier.width(10.dp))
                            Text(err, color = Palette.red)
                        }
                    }
                }
            }

            item { SectionHeader("On your network") }
            item {
                Card(padding = 0.dp) {
                    if (rig.discovered.isEmpty()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Looking for RigHelm on your network…", color = Palette.secondary)
                        }
                    }
                    for (b in rig.discovered) {
                        BeaconRow(b) { asking = SavedServer(b.name, b.host, b.port) }
                    }
                }
            }
            item {
                SectionFooter("Stations appear here when RigHelm on the radio's computer has “Let other computers use this radio” turned on.")
            }

            val others = rig.saved.filter { s -> rig.discovered.none { it.host == s.host && it.port == s.port } }
            if (others.isNotEmpty()) {
                item { SectionHeader("Saved") }
                item {
                    Card(padding = 0.dp) {
                        for (s in others) SavedRow(s, onClick = { asking = s }, onForget = { rig.forget(s) })
                    }
                }
            }

            item {
                Card(padding = 0.dp, modifier = Modifier.padding(top = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { addingManually = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Lan, null, tint = Palette.amber)
                        Spacer(Modifier.width(12.dp))
                        Text("Connect by address", color = Palette.amber)
                    }
                }
            }
        }
    }

    asking?.let { server ->
        ConnectSheet(rig, server, editableAddress = false) { asking = null }
    }
    if (addingManually) {
        ConnectSheet(rig, SavedServer("", "", RemoteProtocol.CONTROL_PORT), editableAddress = true) { addingManually = false }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 16.dp, top = 12.dp),
        color = Palette.secondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun SectionFooter(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp), color = Palette.secondary, fontSize = 12.sp)
}

@Composable
private fun BeaconRow(beacon: ServerBeacon, onClick: () -> Unit) {
    val on = beacon.radio == "connected"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SettingsInputAntenna, null, Modifier.size(28.dp), tint = if (on) Palette.amber else Palette.dim)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(beacon.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            val radio = when (beacon.radio) {
                "connected" -> "radio on"
                "not-answering" -> "radio not answering"
                else -> "no radio"
            }
            Text("${beacon.host} · $radio", fontSize = 12.sp, color = if (on) Palette.green else Palette.secondary)
        }
        if (beacon.mic) {
            Icon(Icons.Filled.Mic, "Accepts microphone audio", Modifier.size(18.dp), tint = Palette.secondary)
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            if (beacon.auth) Icons.Filled.Lock else Icons.Filled.LockOpen,
            if (beacon.auth) "Password set" else "No password",
            Modifier.size(18.dp),
            tint = Palette.secondary,
        )
    }
}

@Composable
private fun SavedRow(server: SavedServer, onClick: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(server.name)
            Text("${server.host}:${server.port} · not heard right now", fontSize = 12.sp, color = Palette.secondary)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Palette.secondary) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Forget", color = Palette.red) }, onClick = {
                    menu = false
                    onForget()
                })
            }
        }
    }
}

/** Password (and, for a typed-in station, its address), then connect. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectSheet(rig: RigController, server: SavedServer, editableAddress: Boolean, dismiss: () -> Unit) {
    var host by remember { mutableStateOf(server.host) }
    var port by remember { mutableStateOf(server.port.toString()) }
    var password by remember { mutableStateOf(rig.savedPassword(server) ?: "") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun connect() {
        val h = host.trim()
        if (h.isEmpty()) return
        val s = if (editableAddress) {
            val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: RemoteProtocol.CONTROL_PORT
            SavedServer(server.name.ifEmpty { h }, h, p)
        } else {
            server
        }
        rig.connect(s, password)
        dismiss()
    }

    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = Palette.card) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (editableAddress) "Connect by address" else "Connect", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = dismiss) { Text("Cancel") }
            }
            if (editableAddress) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Address (e.g. 192.168.1.20)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LabeledValue("Station", server.name)
                LabeledValue("Address", "${server.host}:${server.port}")
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { connect() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The password set in RigHelm under Remote (LAN). Without one, a station lets you listen and tune but never transmit.",
                fontSize = 12.sp,
                color = Palette.secondary,
            )
            Button(
                onClick = { connect() },
                enabled = host.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect", fontWeight = FontWeight.SemiBold, color = Color.Black)
            }
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, valueColor: Color = Palette.secondary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(value, color = valueColor, textAlign = TextAlign.End)
    }
}
