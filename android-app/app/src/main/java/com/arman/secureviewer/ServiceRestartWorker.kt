package com.arman.secureviewer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

class ServiceRestartWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val prefs    = applicationContext.getSharedPreferences("viewer_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", "") ?: ""
        if (deviceId.isNotEmpty()) {
            val i = Intent(applicationContext, ViewerService::class.java)
                .putExtra("device_id", deviceId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                applicationContext.startForegroundService(i)
            else applicationContext.startService(i)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ServiceRestartWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "viewer_worker", ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}