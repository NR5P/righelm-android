package com.righelm.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the station connected, and its audio playing, while the app is in
 * the background or the screen is off. Runs exactly while [RigController]
 * has a station; its notification says which, and can disconnect.
 */
class RigService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rig = (application as RigHelmApp).rig
        if (intent?.action == ACTION_DISCONNECT) {
            rig.disconnect()
            return START_NOT_STICKY
        }
        if (rig.target == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(this, rig),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (wakeLock == null) {
            // The network threads keep polling the radio with the screen off.
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RigHelm:station")
                .apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "station"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.righelm.android.DISCONNECT"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RigService::class.java))
            } catch (e: Exception) {
                // Not allowed from the background; the app still works while open.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RigService::class.java))
        }

        /** Show the current connection state in the notification. */
        fun refresh(context: Context) {
            val rig = (context.applicationContext as RigHelmApp).rig
            if (rig.target == null) return
            try {
                context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, rig))
            } catch (e: SecurityException) {
                // Notifications are turned off for the app.
            }
        }

        private fun notification(context: Context, rig: RigController): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Station connection", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while RigHelm is connected to a station"
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val disconnect = PendingIntent.getService(
                context, 1,
                Intent(context, RigService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val status = when (val p = rig.phase) {
                RigController.Phase.Connected -> "Connected"
                RigController.Phase.Connecting -> "Connecting…"
                is RigController.Phase.Failed -> "Reconnecting: ${p.why}"
                RigController.Phase.Idle -> "Not connected"
            }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_helm)
                .setContentTitle(rig.serverName.ifEmpty { "RigHelm" })
                .setContentText(status)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, "Disconnect", disconnect)
                .build()
        }
    }
}
