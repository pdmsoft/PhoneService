package com.phoneservice.call

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class CallSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!CallLogRepository.hasPermission(applicationContext)) return Result.success()
        return try {
            CallLogRepository.sync(applicationContext)
            Result.success()
        } catch (_: SecurityException) {
            Result.success()
        } catch (error: Exception) {
            Log.w(tag, "background archive failed: ${error.javaClass.simpleName}")
            if (runAttemptCount >= 3) Result.success() else Result.retry()
        }
    }

    companion object {
        const val syncIntervalHours = 3
        private const val tag = "CallArchive"
        private const val periodicName = "phone-service-call-sync"
        private const val onceName = "phone-service-call-sync-now"
        private const val prefsName = "phone_service_sync"
        private const val versionKey = "interval_version"
        private const val intervalVersion = 2

        fun ensureScheduled(context: Context, syncNow: Boolean) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            // UPDATE는 주기를 바꿀 때만 쓴다. 매번 UPDATE하면 다음 실행 시각이 뒤로 밀릴 수 있다.
            val policy = if (prefs.getInt(versionKey, 0) == intervalVersion) {
                ExistingPeriodicWorkPolicy.KEEP
            } else {
                ExistingPeriodicWorkPolicy.UPDATE
            }
            val periodic = PeriodicWorkRequestBuilder<CallSyncWorker>(
                syncIntervalHours.toLong(),
                TimeUnit.HOURS,
            ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            val workManager = WorkManager.getInstance(appContext)
            workManager.enqueueUniquePeriodicWork(periodicName, policy, periodic)
            if (policy == ExistingPeriodicWorkPolicy.UPDATE) {
                prefs.edit().putInt(versionKey, intervalVersion).apply()
            }
            if (!syncNow) return
            val once = OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(onceName, ExistingWorkPolicy.KEEP, once)
        }
    }
}
