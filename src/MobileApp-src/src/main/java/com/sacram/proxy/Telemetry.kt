package com.sacram.proxy

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Anonymous telemetry. Never sends SSID, password, IPs, personal data or
 * destination hosts - only device model, Android version, app version and
 * event names/health stats.
 *
 * Events are buffered and flushed as a single batch (see [flush]) so the
 * collector does ONE store write per flush instead of one per event. A
 * background flusher drains the buffer every [FLUSH_MS] so events sent outside
 * the proxy loop are still delivered promptly (the dashboard is realtime).
 */
object Telemetry {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = mutableListOf<JSONObject>()
    private val flusherRunning = AtomicBoolean(false)

    private const val FLUSH_MS = 30_000L

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

    /** Queue an event. No network happens here - it goes into the buffer. */
    fun send(context: Context, event: String, extra: Map<String, String> = emptyMap()) {
        val cfg = ConfigManager.load(context)
        if (!cfg.telemetryEnabled) return
        val payload = JSONObject()
        payload.put("event", event)
        payload.put("device", Build.MODEL)
        payload.put("manufacturer", Build.MANUFACTURER)
        payload.put("android", Build.VERSION.RELEASE)
        payload.put("api", Build.VERSION.SDK_INT)
        payload.put("app", BuildConfig.VERSION_NAME)
        payload.put("time", System.currentTimeMillis())
        extra.forEach { (k, v) -> payload.put(k, v) }
        synchronized(buffer) { buffer.add(payload) }
        ensureFlusher(context.applicationContext)
    }

    private fun ensureFlusher(ctx: Context) {
        if (flusherRunning.compareAndSet(false, true)) {
            scope.launch {
                try {
                    while (true) {
                        delay(FLUSH_MS)
                        flush(ctx)
                        val empty: Boolean
                        synchronized(buffer) { empty = buffer.isEmpty() }
                        if (empty) break
                    }
                } finally {
                    flusherRunning.set(false)
                }
            }
        }
    }

    /** Send everything currently buffered as one batch. Safe to call often. */
    fun flush(context: Context) {
        val cfg = ConfigManager.load(context)
        if (!cfg.telemetryEnabled) {
            synchronized(buffer) { buffer.clear() }
            return
        }
        val batch: List<JSONObject>
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            batch = buffer.toList()
            buffer.clear()
        }
        val url = cfg.collectorUrl.trimEnd('/') + "/collect"
        scope.launch {
            try {
                val arr = JSONArray()
                batch.forEach { arr.put(it) }
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val token = cfg.collectorToken
                if (token.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }
                conn.outputStream.use { it.write(arr.toString().toByteArray()) }
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (_: Exception) {
                // telemetry must never break the app - drop the batch
            }
        }
    }
}
