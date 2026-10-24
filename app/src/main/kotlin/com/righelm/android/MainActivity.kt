package com.righelm.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import com.righelm.android.ui.ConnectScreen
import com.righelm.android.ui.ControlsScreen
import com.righelm.android.ui.OperateScreen
import com.righelm.android.ui.Palette
import com.righelm.android.ui.RigHelmTheme
import com.righelm.android.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = SystemBarStyle.dark(Palette.background.toArgb())
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        super.onCreate(savedInstanceState)
        val rig = (application as RigHelmApp).rig
        setContent {
            RigHelmTheme { Root(rig) }
        }
        rig.launched()
    }
}

@Composable
private fun Root(rig: RigController) {
    val view = LocalView.current
    val context = view.context
    SideEffect { view.keepScreenOn = rig.keepAwake && rig.target != null }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { rig.micPermissionResult(it) }
    LaunchedEffect(rig.micPermissionWanted) {
        if (rig.micPermissionWanted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // The connection notification (and its Disconnect button).
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(rig.target != null) {
        if (rig.target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.background).imePadding()) {
        if (rig.target == null) ConnectScreen(rig) else MainTabs(rig)
    }

    if (rig.showLockedHint) {
        AlertDialog(
            onDismissRequest = { rig.showLockedHint = false },
            containerColor = Palette.card,
            title = { Text("Transmit is locked") },
            text = { Text(rig.transmitBlockReason ?: "Transmit is not available right now.") },
            confirmButton = { TextButton(onClick = { rig.showLockedHint = false }) { Text("OK") } },
        )
    }
    if (rig.micPermissionDenied) {
        AlertDialog(
            onDismissRequest = { rig.micPermissionDenied = false },
            containerColor = Palette.card,
            title = { Text("Microphone access is off") },
            text = { Text("Allow RigHelm to use the microphone in Android Settings to talk through the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    rig.micPermissionDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { rig.micPermissionDenied = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MainTabs(rig: RigController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> OperateScreen(rig)
                1 -> ControlsScreen(rig)
                else -> SettingsScreen(rig)
            }
        }
        NavigationBar(containerColor = Palette.panel) {
            val colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Palette.amber,
                selectedTextColor = Palette.amber,
                indicatorColor = Color.White.copy(alpha = 0.08f),
                unselectedIconColor = Palette.secondary,
                unselectedTextColor = Palette.secondary,
            )
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Radio, null) }, label = { Text("Operate") }, colors = colors)
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Tune, null) }, label = { Text("Controls") }, colors = colors)
            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") }, colors = colors)
        }
    }
}
