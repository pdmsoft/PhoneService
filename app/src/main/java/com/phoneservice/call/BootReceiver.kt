package com.phoneservice.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!CallLogRepository.hasPermission(context)) return
        val pending = goAsync()
        Thread {
            try {
                CallSyncWorker.ensureScheduled(context, syncNow = true)
                CallLogRepository.watch(context)
            } catch (error: Exception) {
                Log.w(tag, "boot schedule failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val tag = "CallArchive"
    }
}
