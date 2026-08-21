package com.sacram.proxy

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ProxyService : Service() {

    companion object {
        const val CHANNEL_ID = "sacram_proxy"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.sacram.proxy.START"
        const val ACTION_STOP = "com.sacram.proxy.STOP"
        private const val TAG = "SacramService"
        private const val WATCHDOG_REQ = 7
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        fun scheduleWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, WATCHDOG_REQ,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val trigger = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        }

        private fun cancelWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, WATCHDOG_REQ,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var socks: Socks5Server? = null
    private var http: HttpProxyServer? = null
    private var panel: PanelServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fileObserver: FileObserver? = null
    private var restartJob: Job? = null
    private var keepAliveJob: Job? = null
    private var startedAt: Long = 0L

    private fun uptimeSeconds(): String =
        ((System.currentTimeMillis() - startedAt) / 1000).toString()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createChannel()
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            Telemetry.send(this, "foreground_start_failed", mapOf("reason" to (e.message ?: "unknown")))
        }
        acquireLocks()
        startFileWatcher()
        PanelApproval.onRestart = { restartProxy() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ProxyState.setShouldRun(this, false)
            cancelWatchdog(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (started.compareAndSet(false, true)) {
            startedAt = System.currentTimeMillis()
            AppState.serviceStartedAt = startedAt
            ProxyState.setShouldRun(this, true)
            scheduleWatchdog(this)
            keepAliveJob = KeepAlive.launch(scope, this)
            scope.launch { runPipeline() }
        }
        return START_STICKY
    }

    private suspend fun runPipeline() {
        Log.i(TAG, "runPipeline start, sdk=${Build.VERSION.SDK_INT}, model=${Build.MODEL}")
        updateStatus("Starting...")
        try {
            val config = ConfigManager.ensureConfig(this)
            if (!started.get()) return

            AppState.running.value = true
            updateStatus("Enabling WiFi...")
            val wifiOk = WifiDirectManager(this@ProxyService).ensureWifiOn()
            Log.i(TAG, "ensureWifiOn result=$wifiOk")
            if (!wifiOk) {
                updateStatus("Note: could not auto-enable WiFi (some Android versions block it) - trying anyway")
            }
            Telemetry.send(this, "proxy_starting", mapOf("wifi_auto_ok" to "$wifiOk", "port" to "${config.port}"))
            val p2p = WifiDirectManager(this)

            updateStatus("Creating WiFi Direct group...")
            var createOk = false
            var createMsg = ""
            p2p.removeExistingGroup {
                val band = if (config.disableBandSelector) "2.4" else config.band
                p2p.createGroup(config.ssid, config.password, band) { ok, msg ->
                    createOk = ok
                    createMsg = msg
                    Log.i(TAG, "createGroup result ok=$ok msg=$msg band=$band")
                }
            }
            var waited = 0
            while (!createOk && waited < 5000) {
                delay(200); waited += 200
            }
            Log.i(TAG, "createGroup waited=${waited}ms ok=$createOk msg=$createMsg")
            if (!createOk) {
                Telemetry.send(this, "proxy_error", mapOf("reason" to createMsg) + Telemetry.batteryInfo(this))
                updateStatus("ERROR: $createMsg")
                stopSelf()
                return
            }

            // wait until group info is available
            var groupSsid = ""
            var groupPass = ""
            var formed = false
            for (i in 0 until 60) {
                delay(500)
                var got = false
                p2p.requestGroupInfo { g ->
                    got = true
                    if (g != null) {
                        formed = true
                        groupSsid = g.networkName
                        groupPass = g.passphrase
                    }
                }
                var loop = 0
                while (!got && loop < 20) { delay(50); loop++ }
                if (formed) break
            }
            if (!formed) {
                Telemetry.send(this, "proxy_error", mapOf("reason" to "group did not form") + Telemetry.batteryInfo(this))
                updateStatus("ERROR: group did not form")
                stopSelf()
                return
            }

            val goIp = p2p.getGroupOwnerIp()
            val actualSsid = groupSsid.ifEmpty { config.ssid }
            val actualPass = groupPass.ifEmpty { config.password }
            Log.i(TAG, "group formed ssid=$actualSsid goIp=$goIp")

            val hybrid = config.isHybrid()
            val httpMode = config.effectiveMode() == "http"
            AppState.httpMode.value = httpMode || hybrid

            when {
                httpMode -> {
                    updateStatus("Starting HTTP proxy on $goIp:${config.httpPort}...")
                    val server = HttpProxyServer(
                        port = config.httpPort,
                        context = this,
                        goIp = goIp,
                        panelPort = config.panelPort,
                        onLog = { updateStatus("  $it") },
                        onStaleDetected = { restartProxy() }
                    )
                    http = server
                    server.start()
                    Log.i(TAG, "HTTP proxy started on $goIp:${config.httpPort}")
                    AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0, config.panelPort)
                    updateStatus("RUNNING (HTTP) - connect to '$actualSsid' then HTTP proxy $goIp:${config.httpPort}")
                    updateNotification(actualSsid, actualPass, goIp, config.httpPort, 0, false, config.panelPort)
                    Telemetry.send(this, "proxy_started", mapOf("mode" to "http", "port" to "${config.httpPort}", "wifi_auto_ok" to "$wifiOk") + Telemetry.batteryInfo(this))
                }
                hybrid -> {
                    updateStatus("Starting SOCKS5 proxy on $goIp:${config.port}...")
                    socks = Socks5Server(
                        port = config.port,
                        advertiseIp = goIp,
                        context = this,
                        onLog = { updateStatus("  $it") }
                    ).also { it.start() }
                    Log.i(TAG, "SOCKS5 started on $goIp:${config.port}")
                    updateStatus("Starting HTTP proxy on $goIp:${config.httpPort}...")
                    val server = HttpProxyServer(
                        port = config.httpPort,
                        context = this,
                        goIp = goIp,
                        panelPort = config.panelPort,
                        onLog = { updateStatus("  $it") },
                        onStaleDetected = { restartProxy() }
                    )
                    http = server
                    server.start()
                    Log.i(TAG, "HTTP proxy started on $goIp:${config.httpPort}")
                    AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0, config.panelPort)
                    updateStatus("RUNNING (HYBRID) - connect to '$actualSsid' then SOCKS5 $goIp:${config.port} and HTTP $goIp:${config.httpPort}")
                    updateNotification(actualSsid, actualPass, goIp, config.port, config.httpPort, true, config.panelPort)
                    Telemetry.send(this, "proxy_started", mapOf("mode" to "hybrid", "port" to "${config.port}", "http_port" to "${config.httpPort}", "wifi_auto_ok" to "$wifiOk") + Telemetry.batteryInfo(this))
                }
                else -> {
                    updateStatus("Starting SOCKS5 proxy on $goIp:${config.port}...")
                    socks = Socks5Server(
                        port = config.port,
                        advertiseIp = goIp,
                        context = this,
                        onLog = { updateStatus("  $it") }
                    ).also { it.start() }
                    Log.i(TAG, "SOCKS5 started on $goIp:${config.port}")
                    AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0, config.panelPort)
                    updateStatus("RUNNING - connect to '$actualSsid' then SOCKS5 $goIp:${config.port}")
                    updateNotification(actualSsid, actualPass, goIp, config.port, 0, false, config.panelPort)
                    Telemetry.send(this, "proxy_started", mapOf("mode" to "socks5", "port" to "${config.port}", "wifi_auto_ok" to "$wifiOk") + Telemetry.batteryInfo(this))
                }
            }

            // Control panel runs on its own port + own thread pool, independent of
            // the proxy traffic, so it stays responsive even when the proxy is
            // saturated by a heavy page. Started in every mode (it only serves
            // local content and never touches the egress network).
            if (config.panelEnabled) {
                val ps = PanelServer(
                    port = config.panelPort,
                    context = this,
                    enabled = true,
                    onLog = { updateStatus("  $it") },
                    onRestartRequest = { handlePanelRestart() }
                )
                panel = ps
                ps.start()
                Log.i(TAG, "Control panel started on $goIp:${config.panelPort}")
                updateStatus("Panel: http://$goIp:${config.panelPort}/")
            }

            // client count poller + group-keepalive + health heartbeat (every 5 min)
            var beats = 0
            var groupRecreateGuard = false
            while (currentCoroutineContext().isActive && started.get()) {
                delay(5000)
                p2p.requestGroupInfo { g ->
                    if (g == null) {
                        // Android silently tears down the P2P group on inactivity
                        // (no connected client / no traffic) even though the wifi
                        // radio stays on. Recreating it rebuilds the underlying
                        // network interface, which kills any TCP socket a client
                        // has open to us. Previously this only ran for socks5/hybrid
                        // because recreating drops in-flight HTTP connections - but
                        // leaving HTTP mode's group dead forever (status still says
                        // RUNNING while 192.168.49.1 is unreachable) is worse: the
                        // client's browser just redials on the next request anyway.
                        if (!groupRecreateGuard && started.get()) {
                            groupRecreateGuard = true
                            Log.w(TAG, "WiFi Direct group lost (inactivity) - recreating to keep AP alive")
                            Telemetry.send(this, "p2p_group_recreated", mapOf("reason" to "inactivity_drop"))
                            scope.launch {
                                recreateGroup(p2p, config)
                                groupRecreateGuard = false
                            }
                        }
                        AppState.apInfo.value = AppState.apInfo.value.copy(clients = 0)
                        AppState.status.value = "RUNNING - AP re-forming..."
                    } else {
                        val n = g.clientList?.size ?: 0
                        AppState.apInfo.value = AppState.apInfo.value.copy(clients = n)
                        AppState.status.value = "RUNNING - clients connected: $n"
                    }
                }
                beats++
                if (beats % 60 == 0) {
                    val (modeLabel, reportPort) = when {
                        httpMode -> "http" to "${config.httpPort}"
                        hybrid -> "hybrid" to "${config.port}"
                        else -> "socks5" to "${config.port}"
                    }
                    Telemetry.send(
                        this,
                        "heartbeat",
                        mapOf(
                            "uptime" to uptimeSeconds(),
                            "clients" to "${AppState.apInfo.value.clients}",
                            "mode" to modeLabel,
                            "port" to reportPort,
                            "running" to "true",
                            "last_active" to "${System.currentTimeMillis()}"
                        ) + Telemetry.batteryInfo(this)
                    )
                    Telemetry.flush(this)
                }
            }
        } catch (e: Exception) {
            // A cancelled coroutine (normal shutdown / restart) is not an error.
            if (e is kotlinx.coroutines.CancellationException) return
            Log.e(TAG, "pipeline error", e)
            Telemetry.send(this, "proxy_error", mapOf("reason" to (e.message ?: "unknown")) + Telemetry.batteryInfo(this))
            updateStatus("ERROR: ${e.message}")
            stopSelf()
        }
    }

    private fun startFileWatcher() {
        val watched = ConfigManager.externalConfigFile(this)
        if (!watched.exists()) ConfigManager.mirrorToExternal(this)
        fileObserver = object : FileObserver(watched.absolutePath) {
            override fun onEvent(event: Int, path: String?) {
                if (event and FileObserver.CLOSE_WRITE != 0 && started.get()) {
                    restartJob?.cancel()
                    restartJob = scope.launch {
                        delay(1200)
                        ConfigManager.mirrorToExternal(this@ProxyService)
                        restartProxy()
                    }
                }
            }
        }.apply { startWatching() }
    }

    private fun handlePanelRestart() {
        val cfg = ConfigManager.load(this)
        if (cfg.requireApprovalRestart) {
            PanelApproval.submit(mapOf("action" to "restart"))
        } else {
            restartProxy()
        }
    }

    /**
     * Re-create the WiFi Direct group in place (without tearing down the SOCKS5 /
     * HTTP proxy servers) after Android dropped it due to inactivity. Uses the
     * same SSID/passphrase so any reconnecting client just sees the AP come back.
     */
    private suspend fun recreateGroup(p2p: WifiDirectManager, config: AppConfig) {
        if (!started.get()) return
        p2p.removeExistingGroup {
            val band = if (config.disableBandSelector) "2.4" else config.band
            p2p.createGroup(config.ssid, config.password, band) { ok, msg ->
                Log.i(TAG, "recreateGroup createGroup ok=$ok msg=$msg band=$band")
            }
        }
        var formed = false
        for (i in 0 until 30) {
            delay(500)
            if (!started.get()) return
            var got = false
            p2p.requestGroupInfo { g -> got = true; if (g != null) formed = true }
            var loop = 0
            while (!got && loop < 20) { delay(50); loop++ }
            if (formed) break
        }
        if (formed) {
            p2p.requestGroupInfo { g ->
                if (g != null) {
                    val goIp = p2p.getGroupOwnerIp()
                    val hybrid = config.isHybrid()
                    val httpMode = config.effectiveMode() == "http"
                    AppState.apInfo.value = AppState.apInfo.value.copy(
                        ssid = g.networkName,
                        passphrase = g.passphrase,
                        goIp = goIp
                    )
                    updateNotification(
                        g.networkName, g.passphrase, goIp,
                        if (httpMode) config.httpPort else config.port,
                        if (hybrid) config.httpPort else 0,
                        hybrid,
                        config.panelPort
                    )
                    Log.i(TAG, "WiFi Direct group recreated (kept alive) ssid=${g.networkName} goIp=$goIp")
                }
            }
        } else {
            Log.w(TAG, "recreateGroup failed to reform group in time")
        }
    }

    private fun restartProxy() {
        if (!started.get()) return
        scope.launch {
            Telemetry.send(this@ProxyService, "proxy_restart", mapOf("reason" to "config_changed", "uptime" to uptimeSeconds()) + Telemetry.batteryInfo(this@ProxyService))
            updateStatus("Config changed, restarting...")
            runCatching { socks?.stop() }
            runCatching { http?.stop() }
            runCatching { panel?.stop() }
            socks = null
            http = null
            panel = null
            val p2p = WifiDirectManager(this@ProxyService)
            p2p.removeGroup { }
            delay(1500)
            runPipeline()
        }
    }

    override fun onDestroy() {
        started.set(false)
        ProxyState.setShouldRun(this, false)
        cancelWatchdog(this)
            Telemetry.send(this, "proxy_stopped", mapOf("uptime" to uptimeSeconds()) + Telemetry.batteryInfo(this))
            Telemetry.flush(this)
        restartJob?.cancel()
        keepAliveJob?.cancel()
        runCatching { fileObserver?.stopWatching() }
        runCatching { socks?.stop() }
        runCatching { http?.stop() }
        runCatching { panel?.stop() }
        socks = null
        http = null
        panel = null
        runCatching { WifiDirectManager(this).removeGroup() }
        releaseLocks()
        scope.cancel()
        AppState.running.value = false
        AppState.httpMode.value = false
        AppState.status.value = "Stopped"
        AppState.apInfo.value = ApInfo()
        super.onDestroy()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sacram:proxy").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Sacram:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Sacram Proxy", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps the WiFi Direct UDP proxy alive"
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_s)
            .setContentTitle("Sacram UDP Proxy")
            .setContentText("Running - see app for connection details")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "STOP", stopIntent)
            .build()
    }

    private fun updateNotification(ssid: String, pass: String, ip: String, socksPort: Int, httpPort: Int, hybrid: Boolean, panelPort: Int = 0) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val panelLine = if (panelPort > 0) "\nPanel: $ip:$panelPort" else ""
        val detail = if (hybrid) {
            "SSID: $ssid\nSOCKS5: $ip:$socksPort\nHTTP: $ip:$httpPort\nPassword: $pass$panelLine"
        } else {
            "SSID: $ssid\nIP: $ip:$socksPort\nPassword: $pass$panelLine"
        }
        val summary = if (hybrid) "$ssid | SOCKS5 $ip:$socksPort | HTTP $ip:$httpPort | pass: $pass"
        else "$ssid | $ip:$socksPort | pass: $pass"
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_s)
            .setContentTitle("Sacram UDP Proxy - RUNNING")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "STOP", PendingIntent.getService(
                this, 1,
                Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun updateStatus(msg: String) {
        AppState.status.value = msg
    }
}
