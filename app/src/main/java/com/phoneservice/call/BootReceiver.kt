package com.phoneservice.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!CallLogRepository.hasPermission(context)) return
        CallArchiveService.start(context, syncNow = true)
    }
}
