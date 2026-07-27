package com.arman.secureviewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
        if (intent.action !in actions) return
        val prefs    = context.getSharedPreferences("viewer_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", "") ?: ""
        if (deviceId.isEmpty()) return
        val si = Intent(context, ViewerService::class.java).putExtra("device_id", deviceId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(si)
        else context.startService(si)
        ServiceRestartWorker.schedule(context)
        AlarmRestarter.schedule(context)
    }
}