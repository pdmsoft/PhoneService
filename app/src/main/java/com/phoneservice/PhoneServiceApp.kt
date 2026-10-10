package com.phoneservice

import android.app.Application
import com.phoneservice.call.CallLogRepository
import com.phoneservice.call.CallSyncWorker

class PhoneServiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!CallLogRepository.hasPermission(this)) return
        CallSyncWorker.ensureScheduled(this, syncNow = false)
        CallLogRepository.watch(this)
    }
}
