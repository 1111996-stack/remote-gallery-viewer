package com.arman.secureviewer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AlarmRestarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmRestarter", "Fired — restarting service")
        val prefs    = context.getSharedPreferences("viewer_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", "") ?: ""
        if (deviceId.isNotEmpty()) {
            val si = Intent(context, ViewerService::class.java)
                .putExtra("device_id", deviceId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(si)
            else context.startService(si)
        }
        schedule(context) // Schedule next alarm
    }

    companion object {
        private const val INTERVAL = 15 * 60 * 1000L
        private const val RC       = 9001

        fun schedule(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(
                    context, RC,
                    Intent(context, AlarmRestarter::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val at = System.currentTimeMillis() + INTERVAL
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    // Fallback to non-exact alarm if permission not granted
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    Log.d("AlarmRestarter", "Exact alarms not allowed, using inexact fallback")
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
                }
                Log.d("AlarmRestarter", "Next alarm in 15 min")
            } catch (e: Exception) {
                Log.e("AlarmRestarter", "Failed to schedule: ${e.message}")
            }
        }
    }
}
