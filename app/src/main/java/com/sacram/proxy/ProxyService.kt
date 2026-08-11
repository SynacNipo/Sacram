package com.sacram.proxy

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var socks: Socks5Server? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fileObserver: FileObserver? = null
    private var restartJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        acquireLocks()
        startFileWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started.compareAndSet(false, true)) {
            scope.launch { runPipeline() }
        }
        return START_STICKY
    }

    private suspend fun runPipeline() {
        updateStatus("Starting...")
        try {
            val config = ConfigManager.ensureConfig(this)
            if (!started.get()) return

            AppState.running.value = true
            updateStatus("Enabling WiFi...")
            if (!WifiDirectManager(this@ProxyService).ensureWifiOn()) {
                updateStatus("ERROR: WiFi could not be enabled")
                stopSelf()
                return
            }
            val p2p = WifiDirectManager(this)

            updateStatus("Creating WiFi Direct group...")
            var createOk = false
            var createMsg = ""
            p2p.removeExistingGroup {
                p2p.createGroup(config.ssid, config.password) { ok, msg ->
                    createOk = ok
                    createMsg = msg
                }
            }
            var waited = 0
            while (!createOk && waited < 5000) {
                delay(200); waited += 200
            }
            if (!createOk) {
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
                updateStatus("ERROR: group did not form")
                stopSelf()
                return
            }

            val goIp = p2p.getGroupOwnerIp()
            val actualSsid = groupSsid.ifEmpty { config.ssid }
            val actualPass = groupPass.ifEmpty { config.password }

            updateStatus("Starting SOCKS5 proxy on $goIp:$config.port...")
            val server = Socks5Server(
                port = config.port,
                advertiseIp = goIp,
                onLog = { updateStatus("  $it") }
            )
            socks = server
            server.start()

            AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0)
            updateStatus("RUNNING - connect to '$actualSsid' then SOCKS5 $goIp:${config.port}")
            updateNotification(actualSsid, actualPass, goIp, config.port)

            // client count poller
            while (currentCoroutineContext().isActive && started.get()) {
                delay(5000)
                p2p.requestGroupInfo { g ->
                    val n = g?.clientList?.size ?: 0
                    AppState.apInfo.value = AppState.apInfo.value.copy(clients = n)
                    AppState.status.value = "RUNNING - clients connected: $n"
                }
            }
        } catch (e: Exception) {
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

    private fun restartProxy() {
        if (!started.get()) return
        scope.launch {
            updateStatus("Config changed, restarting...")
            runCatching { socks?.stop() }
            socks = null
            val p2p = WifiDirectManager(this@ProxyService)
            p2p.removeGroup { }
            delay(1500)
            runPipeline()
        }
    }

    override fun onDestroy() {
        started.set(false)
        restartJob?.cancel()
        runCatching { fileObserver?.stopWatching() }
        runCatching { socks?.stop() }
        socks = null
        runCatching { WifiDirectManager(this).removeGroup() }
        releaseLocks()
        scope.cancel()
        AppState.running.value = false
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
            CHANNEL_ID, "Sacram Proxy", NotificationManager.IMPORTANCE_LOW
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

    private fun updateNotification(ssid: String, pass: String, ip: String, port: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_s)
            .setContentTitle("Sacram UDP Proxy - RUNNING")
            .setContentText("$ssid | $ip:$port | pass: $pass")
            .setStyle(NotificationCompat.BigTextStyle().bigText("SSID: $ssid\nIP: $ip:$port\nPassword: $pass"))
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
