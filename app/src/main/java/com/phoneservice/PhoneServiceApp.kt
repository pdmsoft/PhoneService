package com.phoneservice

import android.app.Application
import com.phoneservice.call.CallArchiveService

class PhoneServiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CallArchiveService.start(this, syncNow = false)
    }
}
