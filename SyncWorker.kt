package com.amgateway.smsforwarder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Sends queued SMS (with automatic retry when offline) and a heartbeat every ~15 minutes so the dashboard knows the phone is alive. */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val p = Prefs(applicationContext)
        if (!p.isPaired) return Result.success()
        val state = Uploader.flush(applicationContext)
        Uploader.ping(applicationContext)
        return if (state == Uploader.Flush.RETRY) Result.retry() else Result.success()
    }

    companion object {
        private fun net(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun flushNow(ctx: Context) {
            val req = OneTimeWorkRequest.Builder(SyncWorker::class.java)
                .setConstraints(net())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork("amg_flush", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequest.Builder(SyncWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(net())
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork("amg_heartbeat", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
