package com.phoneservice

import android.app.Application
import com.phoneservice.call.CallArchiveService
import com.phoneservice.call.CallLogRepository

class PhoneServiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!CallLogRepository.hasPermission(this)) return
        CallArchiveService.start(this, syncNow = false)
    }
}
