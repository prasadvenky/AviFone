package com.example.avifone

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Microphone foreground service. It does no audio work itself (the native engine
 * runs on its own real-time threads); it keeps the process foreground so Android
 * doesn't silence the mic with the screen off, holds a partial wake lock so the
 * CPU stays up, and shows the ongoing notification with a Stop action. It runs while
 * headphone listening, transcription, or both are on.
 */
class ListenService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ListenController.get(this).stopAll()
            return START_NOT_STICKY
        }
        val listening = intent?.getBooleanExtra(EXTRA_LISTENING, false) ?: false
        val transcribing = intent?.getBooleanExtra(EXTRA_TRANSCRIBING, false) ?: false
        val headphones = intent?.getStringExtra(EXTRA_HEADPHONES) ?: "headphones"
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(listening, transcribing, headphones),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException if started from the background.
            Log.w(TAG, "Could not enter foreground", e)
            ListenController.get(this).stopAll()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        // If the process is killed, don't silently restart the mic without the user.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AviFone:listening")
            .apply {
                setReferenceCounted(false)
                // Released in onDestroy; the timeout is only a safety net.
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun buildNotification(listening: Boolean, transcribing: Boolean, headphones: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                getString(
                    when {
                        listening && transcribing -> R.string.notification_title_both
                        transcribing -> R.string.notification_title_transcribing
                        else -> R.string.notification_title
                    }
                )
            )
            .setContentText(
                if (listening) getString(R.string.notification_text, headphones)
                else getString(R.string.notification_text_transcribing)
            )
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "ListenService"
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.example.avifone.STOP"
        private const val EXTRA_HEADPHONES = "headphones"
        private const val EXTRA_LISTENING = "listening"
        private const val EXTRA_TRANSCRIBING = "transcribing"
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        /** Starts the service, or refreshes its notification if it's already running. */
        fun start(context: Context, listening: Boolean, transcribing: Boolean, headphones: String?) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ListenService::class.java)
                    .putExtra(EXTRA_LISTENING, listening)
                    .putExtra(EXTRA_TRANSCRIBING, transcribing)
                    .putExtra(EXTRA_HEADPHONES, headphones),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }
    }
}
