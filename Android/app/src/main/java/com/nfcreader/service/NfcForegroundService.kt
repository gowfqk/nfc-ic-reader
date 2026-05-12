package com.nfcreader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.nfcreader.R
import com.nfcreader.receiver.StopServiceReceiver
import com.nfcreader.ui.MainActivity

/**
 * NFC 前台服务
 * 保持进程存活，确保 App 切到后台后不被系统杀死
 */
class NfcForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nfc_foreground_channel"
        private const val WAKE_LOCK_TAG = "NFCReader::WakeLock"

        // 供外部（如 NfcBackgroundActivity）更新通知
        private var instance: NfcForegroundService? = null

        fun updateNotificationStatic(context: android.content.Context, text: String) {
            instance?.updateNotification(text)
        }

        fun stopService(context: android.content.Context) {
            context.stopService(Intent(context, NfcForegroundService::class.java))
        }
    }

    // Binder for Activity binding
    inner class LocalBinder : Binder() {
        fun getService(): NfcForegroundService = this@NfcForegroundService
    }
    
    private var wakeLock: PowerManager.WakeLock? = null

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("NFC 读卡器运行中"))
        
        // 获取唤醒锁，保持 CPU 运行（支持后台读卡）
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire(12 * 60 * 60 * 1000L)  // 最长 12 小时
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_NOT_STICKY: 被杀后不自动重启，避免通知无法关闭
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    /**
     * 更新通知文本（供 Activity 调用）
     */
    fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 退出按钮（使用 BroadcastReceiver，更可靠）
        val stopIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, StopServiceReceiver::class.java).apply {
                action = StopServiceReceiver.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.notification_action_stop),
            stopIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(contentIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        // 释放唤醒锁
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
