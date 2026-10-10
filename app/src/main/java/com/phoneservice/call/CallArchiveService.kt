package com.phoneservice.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phoneservice.MainActivity
import com.phoneservice.R

class CallArchiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val syncNow = intent?.getBooleanExtra(extraSyncNow, false) == true
        if (!showOngoingNotice()) {
            CallSyncWorker.ensureScheduled(this, syncNow)
            CallLogRepository.watch(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!CallLogRepository.hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        CallSyncWorker.ensureScheduled(this, syncNow)
        CallLogRepository.watch(this)
        return START_STICKY
    }

    private fun showOngoingNotice(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            getString(R.string.archive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.archive_channel_description)
        }
        manager.createNotificationChannel(channel)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_archive)
            .setContentTitle(getString(R.string.archive_notification_title))
            .setContentText(getString(R.string.archive_notification_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(notificationId, notification)
            }
            true
        } catch (error: Exception) {
            Log.w(tag, "foreground notice failed: ${error.javaClass.simpleName}")
            false
        }
    }

    companion object {
        private const val tag = "CallArchive"
        private const val channelId = "call-archive"
        private const val notificationId = 41
        private const val extraSyncNow = "syncNow"

        fun start(context: Context, syncNow: Boolean) {
            val appContext = context.applicationContext
            if (!CallLogRepository.hasPermission(appContext)) return
            val intent = Intent(appContext, CallArchiveService::class.java)
                .putExtra(extraSyncNow, syncNow)
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (error: Exception) {
                Log.w(tag, "archive service not started: ${error.javaClass.simpleName}")
                CallSyncWorker.ensureScheduled(appContext, syncNow)
                CallLogRepository.watch(appContext)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, CallArchiveService::class.java),
            )
        }
    }
}
