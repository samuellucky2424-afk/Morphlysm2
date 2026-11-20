package top.niunaijun.blackboxa.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.main.StreamActivity

class DecartStreamingService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "start foreground live stream guard action=${intent?.action}")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ rejects a typeless startForeground, and Android 15+
                // only keeps camera/mic capture alive in the background when the
                // matching types are passed and those permissions are granted.
                var type = 0
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (type == 0) {
                    type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(NOTIFICATION_ID, notification(), type)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            START_STICKY
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to enter the live foreground service", error)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val openIntent = Intent(this, StreamActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Morphly live stream")
                .setContentText("Live output is feeding the virtual camera.")
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Morphly live stream")
                .setContentText("Live output is feeding the virtual camera.")
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the live engine connected while cloned apps use the virtual camera."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "DECART_FGS"
        private const val CHANNEL_ID = "morphly_decart_stream"
        private const val NOTIFICATION_ID = 4107

        fun start(context: Context) {
            try {
                val intent = Intent(context, DecartStreamingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Could not start live foreground stream guard", error)
            }
        }

        fun stop(context: Context?) {
            if (context == null) {
                return
            }
            try {
                context.applicationContext.stopService(Intent(context, DecartStreamingService::class.java))
            } catch (error: Throwable) {
                Log.w(TAG, "Could not stop live foreground stream guard", error)
            }
        }
    }
}
