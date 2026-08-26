package com.sacram.proxy

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Anonymous telemetry, batched.
 *
 * Instead of shipping one request per event (which flooded the collector and
 * blew past the storage cap), every event is appended as a single human-readable
 * line into an in-memory "batch" together with running aggregate counters
 * (per-event counts, total up/down bytes, failure count). The whole batch is
 * sent as ONE request to `/collect_batch` every [BATCH_MS] (10 min). The
 * collector stores it as a single row + the full text, so 10k events become
 * ~144 rows/day instead of 10k.
 *
 * A separate lightweight poller calls `/cmd?device=...` every [CMD_POLL_MS]; the
 * dashboard can drop a "flush now" command there, which interrupts the 10-min
 * timer and ships the current batch immediately (realtime on demand).
 *
 * Nothing here ever sends SSID, password, IPs, personal data or destination
 * hosts - only device model, Android version, app version, event names and
 * aggregate health stats.
 */
object Telemetry {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    // Current batch accumulator.
    private var batchStart = 0L
    private val batchLines = mutableListOf<String>()
    private val batchCounts = mutableMapOf<String, Int>()
    private var batchUp = 0L
    private var batchDn = 0L
    private var batchFails = 0
    private var batchClients = -1
    private var batchMode = ""
    private var batchUptime = -1L
    private var batchBat = -1

    private val flusherRunning = AtomicBoolean(false)
    private val cmdPollerRunning = AtomicBoolean(false)

    private const val BATCH_MS = 10 * 60_000L
    private const val CMD_POLL_MS = 20_000L

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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

    /** Queue an event. No network here - it is appended to the current batch. */
    fun send(context: Context, event: String, extra: Map<String, String> = emptyMap()) {
        val cfg = ConfigManager.load(context)
        if (!cfg.telemetryEnabled) return
        synchronized(lock) {
            if (batchStart == 0L) batchStart = System.currentTimeMillis()
            batchLines.add(formatLine(event, extra))
            batchCounts[event] = (batchCounts[event] ?: 0) + 1
            batchUp += extra["up_bytes"]?.toLongOrNull() ?: 0L
            batchDn += extra["dn_bytes"]?.toLongOrNull() ?: 0L
            val status = extra["status"]
            val isFail = event == "proxy_error" ||
                (status != null && (status == "fail" || status.toIntOrNull()?.let { it >= 400 } ?: false))
            if (isFail) batchFails++
            extra["clients"]?.toIntOrNull()?.let { batchClients = it }
            extra["mode"]?.let { batchMode = it }
            extra["uptime"]?.toLongOrNull()?.let { batchUptime = it }
            extra["battery"]?.toIntOrNull()?.let { batchBat = it }
        }
        ensureFlusher(context.applicationContext)
        ensureCmdPoller(context.applicationContext)
    }

    /** Flush the current batch now (also called by the 10-min timer and on command). */
    fun flush(context: Context) {
        val cfg = ConfigManager.load(context)
        if (!cfg.telemetryEnabled) {
            synchronized(lock) { resetBatch() }
            return
        }
        val payload = synchronized(lock) {
            if (batchLines.isEmpty()) return
            buildPayload()
        }
        sendBatch(cfg, payload)
    }

    /**
     * Record an event and ship a batch immediately. Used the moment telemetry is
     * opted in (and at app launch when already enabled) so the collector gets at
     * least one device row right away - otherwise the dashboard's "request
     * flush" command has nothing to target until the 10-min timer first fires.
     */
    fun flushNow(context: Context, event: String = "session_start") {
        send(context, event)
        scope.launch { flush(context) }
    }

    private fun resetBatch() {
        batchStart = 0L
        batchLines.clear()
        batchCounts.clear()
        batchUp = 0L
        batchDn = 0L
        batchFails = 0
        batchClients = -1
        batchMode = ""
        batchUptime = -1L
        batchBat = -1
    }

    private fun buildPayload(): String {
        val end = System.currentTimeMillis()
        val counts = batchCounts.entries.joinToString(",") { "${it.key}=${it.value}" }
        val text = batchLines.joinToString("\n")
        val o = JSONObject()
        o.put("device", Build.MODEL)
        o.put("manufacturer", Build.MANUFACTURER)
        o.put("android", Build.VERSION.RELEASE)
        o.put("api", Build.VERSION.SDK_INT)
        o.put("app", BuildConfig.VERSION_NAME)
        o.put("batch_start", batchStart)
        o.put("batch_end", end)
        o.put("event_count", batchLines.size)
        o.put("counts", counts)
        o.put("up", batchUp)
        o.put("dn", batchDn)
        o.put("failures", batchFails)
        if (batchClients >= 0) o.put("clients", batchClients)
        if (batchMode.isNotEmpty()) o.put("mode", batchMode)
        if (batchUptime >= 0) o.put("uptime", batchUptime)
        if (batchBat >= 0) o.put("battery", batchBat)
        o.put("text", text)
        resetBatch()
        return o.toString()
    }

    /** One readable line, e.g. `12:59:21  http_tunnel  port=443 · lat=103460ms · up=3518B · dn=3100B · fb=46ms`. */
    private fun formatLine(event: String, extra: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append(timeFmt.format(Date()))
        sb.append("  ").append(event)
        val detail = formatDetail(extra)
        if (detail.isNotEmpty()) sb.append("  ").append(detail)
        return sb.toString()
    }

    private fun formatDetail(extra: Map<String, String>): String {
        val parts = mutableListOf<String>()
        extra["status"]?.let { parts.add("status=$it") }
        extra["method"]?.let { parts.add("method=$it") }
        extra["mode"]?.let { parts.add("mode=$it") }
        extra["port"]?.let { parts.add("port=$it") }
        extra["dms"]?.let { parts.add("lat=${it}ms") }
        extra["up_bytes"]?.let { parts.add("up=${it}B") }
        extra["dn_bytes"]?.let { parts.add("dn=${it}B") }
        extra["first_byte_ms"]?.let { parts.add("fb=${it}ms") }
        extra["clients"]?.let { parts.add("clients=$it") }
        extra["uptime"]?.let { parts.add("uptime=${fmtUptime(it.toLongOrNull() ?: 0L)}") }
        extra["battery"]?.let { parts.add("bat=${it}%" + (if (extra["charging"] == "true") " (charging)" else "")) }
        extra["wifi_auto_ok"]?.let { parts.add("wifi=$it") }
        extra["idle_s"]?.let { parts.add("idle=${it}s") }
        extra["retry"]?.let { parts.add("retry=$it") }
        extra["running"]?.let { parts.add("running=$it") }
        extra["reason"]?.let { parts.add("reason=$it") }
        return parts.joinToString(" · ")
    }

    private fun fmtUptime(sec: Long): String {
        if (sec < 0) return "0m"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}h${String.format("%02d", m)}m" else "${m}m"
    }

    private fun sendBatch(cfg: AppConfig, payload: String) {
        val url = cfg.collectorUrl.trimEnd('/') + "/collect_batch"
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val token = cfg.collectorToken
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.inputStream.use { it.readBytes() }
            conn.disconnect()
        } catch (_: Exception) {
            // telemetry must never break the app
        }
    }

    private fun ensureFlusher(ctx: Context) {
        if (flusherRunning.compareAndSet(false, true)) {
            scope.launch {
                try {
                    while (ConfigManager.load(ctx).telemetryEnabled) {
                        delay(BATCH_MS)
                        flush(ctx)
                    }
                } finally {
                    flusherRunning.set(false)
                }
            }
        }
    }

    private fun ensureCmdPoller(ctx: Context) {
        if (cmdPollerRunning.compareAndSet(false, true)) {
            scope.launch {
                try {
                    while (ConfigManager.load(ctx).telemetryEnabled) {
                        delay(CMD_POLL_MS)
                        pollCommand(ctx)
                    }
                } finally {
                    cmdPollerRunning.set(false)
                }
            }
        }
    }

    /** Ask the collector if a flush was requested for this device; if so, flush now. */
    private fun pollCommand(ctx: Context) {
        try {
            val cfg = ConfigManager.load(ctx)
            val device = URLEncoder.encode(Build.MODEL, "UTF-8")
            val url = cfg.collectorUrl.trimEnd('/') + "/cmd?device=" + device
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val token = cfg.collectorToken
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                if (body.contains("\"flush\"") && body.contains("true")) flush(ctx)
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
    }
}
