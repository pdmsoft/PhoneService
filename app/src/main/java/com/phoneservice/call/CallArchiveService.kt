package com.phoneservice.call

import android.app.NotificationManager
import android.content.Context

object CallArchiveService {
    private const val channelId = "call-archive"
    private const val notificationId = 41

    fun start(context: Context, syncNow: Boolean) {
        val appContext = context.applicationContext
        dismissNotice(appContext)
        if (!CallLogRepository.hasPermission(appContext)) return
        CallSyncWorker.ensureScheduled(appContext, syncNow)
        CallLogRepository.watch(appContext)
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        dismissNotice(appContext)
        CallLogRepository.unwatch(appContext)
    }

    private fun dismissNotice(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(notificationId)
        manager.deleteNotificationChannel(channelId)
    }
}
