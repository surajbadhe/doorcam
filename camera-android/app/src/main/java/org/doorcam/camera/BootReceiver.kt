package org.doorcam.camera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || SecureConfigStore(context).load() == null) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel("doorcam_boot",
                "DoorCam camera", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(48, NotificationCompat.Builder(context, "doorcam_boot")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("DoorCam is ready")
            .setContentText("Tap to open DoorCam and start the camera stream.")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

