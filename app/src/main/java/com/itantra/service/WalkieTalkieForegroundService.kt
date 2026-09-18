package com.itantra.service

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
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.itantra.ITantraApp
import com.itantra.ai.model.Language
import com.itantra.ui.MainActivity

/**
 * Foreground Service that keeps the iTantra Offline Voice Mesh active
 * and listening in the background even when the device is locked or app is closed.
 * Wakes the screen and posts high-priority heads-up notifications on incoming SOS.
 */
class WalkieTalkieForegroundService : Service() {

    private val TAG = "WalkieForegroundService"
    private val CHANNEL_ID = "iTantra_Mesh_Service_Channel"
    private val EMERGENCY_CHANNEL_ID = "iTantra_Emergency_Alert_Channel"
    private val NOTIFICATION_ID = 1001

    private var partialWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        acquirePartialWakeLock()
        bindEmergencyListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bindEmergencyListener()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquirePartialWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            partialWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "iTantra:MeshRadioBackgroundLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Acquired Partial WakeLock for continuous background mesh listening")
        } catch (e: Exception) {
            Log.w(TAG, "Failed acquiring Partial WakeLock: ${e.message}")
        }
    }

    private fun bindEmergencyListener() {
        try {
            ITantraApp.instance.meshCoordinator.emergencyAlertListener = { alertText, sourceLang, senderName ->
                onEmergencyAlertReceived(alertText, sourceLang, senderName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind emergency alert listener: ${e.message}")
        }
    }

    private fun onEmergencyAlertReceived(alertText: String, sourceLang: Language, senderName: String) {
        Log.i(TAG, "🚨 ForegroundService received emergency alert: '$alertText' from $senderName")

        // 1. Wake device screen up
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "iTantra:EmergencyScreenWake"
            )
            screenLock.acquire(15000)
        } catch (e: Exception) {
            Log.w(TAG, "Screen wake lock: ${e.message}")
        }

        // 2. Vibrate phone with emergency pattern
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val pattern = longArrayOf(0, 600, 200, 600, 200, 600)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (ignored: Exception) {}

        // 3. Post high-priority Heads-Up Notification (visible even when screen locked)
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
            .setContentTitle("🚨 CRITICAL SOS ALERT from $senderName")
            .setContentText(alertText)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify((2000..9999).random(), alertNotification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Background Service Channel
            val channel = NotificationChannel(
                CHANNEL_ID,
                "iTantra Disaster Mesh Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps disaster radio mesh and voice translation active in background."
            }
            manager.createNotificationChannel(channel)

            // High-Priority Emergency SOS Alert Channel
            val emergencyChannel = NotificationChannel(
                EMERGENCY_CHANNEL_ID,
                "iTantra Critical SOS Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical SOS emergency alerts received over offline mesh."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(emergencyChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iTantra — Disaster Mesh Active")
            .setContentText("Autonomous BLE & Wi-Fi Mesh Listening • Ready for incoming SOS")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (partialWakeLock?.isHeld == true) {
                partialWakeLock?.release()
            }
        } catch (ignored: Exception) {}
    }
}
