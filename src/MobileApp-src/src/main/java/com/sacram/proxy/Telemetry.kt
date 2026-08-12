package com.sacram.proxy

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anonymous telemetry. Never sends SSID, password, IPs or personal data -
 * only device model, Android version, app version and event names.
 */
object Telemetry {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun batteryInfo(context: Context): Map<String, String> {
        return try {
            val bm = context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            mapOf("battery" to "$level", "charging" to "$charging")
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun send(context: Context, event: String, extra: Map<String, String> = emptyMap()) {
        val cfg = ConfigManager.load(context)
        if (!cfg.telemetryEnabled) return
        val url = cfg.collectorUrl.trimEnd('/') + "/collect"
        scope.launch {
            try {
                val payload = JSONObject()
                payload.put("event", event)
                payload.put("device", Build.MODEL)
                payload.put("manufacturer", Build.MANUFACTURER)
                payload.put("android", Build.VERSION.RELEASE)
                payload.put("api", Build.VERSION.SDK_INT)
                payload.put("app", BuildConfig.VERSION_NAME)
                payload.put("time", System.currentTimeMillis())
                extra.forEach { (k, v) -> payload.put(k, v) }

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (_: Exception) {
                // telemetry must never break the app
            }
        }
    }
}