package com.sacram.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!ProxyState.shouldRun(context)) return
        ProxyService.scheduleWatchdog(context)
        if (AppState.running.value) return
        val start = Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, start)
        } catch (_: Exception) {
        }
    }
}
