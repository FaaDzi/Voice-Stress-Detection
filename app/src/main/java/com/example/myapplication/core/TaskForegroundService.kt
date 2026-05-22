package com.example.myapplication.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R

class TaskForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "active_task_channel"
        private const val CHANNEL_NAME = "Active Tasks"
        private const val NOTIFICATION_ID = 4102
        private const val EXTRA_MODE = "extra_mode"
        private const val ACTION_START = "com.example.myapplication.action.START_TASK"
        private const val ACTION_STOP = "com.example.myapplication.action.STOP_TASK"

        const val MODE_RECORDING = "recording"
        const val MODE_PROCESSING = "processing"

        fun start(context: Context, mode: String) {
            val intent = Intent(context, TaskForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TaskForegroundService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseWakeLock()
                stopSelf()
            }

            else -> {
                val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_PROCESSING
                createChannelIfNeeded()
                acquireWakeLock(mode)
                startForeground(NOTIFICATION_ID, buildNotification(mode))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(mode: String): Notification {
        val title = if (mode == MODE_RECORDING) {
            "Recording in background"
        } else {
            "Processing in background"
        }

        val text = if (mode == MODE_RECORDING) {
            "Recording stays active while the screen is off or the app is minimized."
        } else {
            "Audio processing stays active while the screen is off or the app is minimized."
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock(mode: String) {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MyApplication4:${mode}WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }
}
