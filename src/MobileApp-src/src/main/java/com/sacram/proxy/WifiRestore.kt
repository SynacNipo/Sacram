package com.sacram.proxy

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WiFi radio auto-restore. While the proxy is meant to run we watch the WiFi
 * radio; if it is OFF we wait [AppConfig.wifiAutorestoreMin] minutes and then
 * re-enable it and rebuild the hotspot.
 *
 * There is deliberately NO "user turned it off" exemption: the feature exists to
 * keep the hotspot up. To stop auto-restoring, set wifi_autorestore_min=0 in
 * config.txt (or stop the proxy). It only ever fires while the proxy should run,
 * so stopping the proxy disables it too.
 */
object WifiRestore {
    private const val TAG = "SacramWifiRestore"
    private const val CHECK_INTERVAL_MS = 10_000L

    fun launch(scope: CoroutineScope, context: Context, onRestore: () -> Unit): Job {
        return scope.launch(Dispatchers.IO) {
            var disabledSince = 0L
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                if (!ProxyState.shouldRun(context)) {
                    disabledSince = 0
                    continue
                }
                val cfg = runCatching { ConfigManager.load(context) }.getOrDefault(ConfigManager.defaultConfig)
                val minutes = cfg.wifiAutorestoreMin
                if (minutes <= 0) {
                    disabledSince = 0
                    continue
                }
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (wifi.isWifiEnabled) {
                    disabledSince = 0
                    continue
                }
                val now = System.currentTimeMillis()
                if (disabledSince == 0L) {
                    disabledSince = now
                    Log.i(TAG, "WiFi radio OFF - will auto-restore after ${minutes}m")
                    continue
                }
                if (now - disabledSince >= minutes * 60_000L) {
                    Log.i(TAG, "WiFi radio OFF for ${minutes}m - restoring + rebuilding hotspot")
                    Telemetry.send(context, "wifi_autorestore", mapOf("after_min" to "$minutes"))
                    runCatching { WifiDirectManager(context).ensureWifiOn() }
                    disabledSince = 0
                    onRestore()
                }
            }
        }
    }
}
