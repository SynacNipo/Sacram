package com.sacram.proxy

import android.content.Context
import java.io.File
import java.util.Properties

data class AppConfig(
    val ssid: String,
    val password: String,
    val port: Int,
    val proxyMode: String = "socks5",
    val proxyType: Int = 0, // 0=unset (use proxyMode), 1=UDP/SOCKS5, 2=HTTP, 3=Hybrid (SOCKS5+HTTP)
    val httpPort: Int = 8282,
    val telemetryPrompted: Boolean = false,
    val telemetryEnabled: Boolean = false,
    val collectorUrl: String = "https://sacram-telemetry.synacnipo.workers.dev",
    val collectorToken: String = "",
    val keepaliveUrl: String = "https://sacram-telemetry.synacnipo.workers.dev/keepalive",
    val keepaliveIntervalMs: Long = 60_000L,
    val wifiAutorestoreMin: Int = 5,
    val panelEnabled: Boolean = true
) {
    fun effectiveMode(): String {
        return when (proxyType) {
            1 -> "socks5"
            2 -> "http"
            3 -> "hybrid"
            else -> proxyMode
        }
    }

    fun isHybrid(): Boolean = proxyType == 3
}

object ConfigManager {

    private const val DIR_NAME = "Sacram"
    private const val FILE_NAME = "config.txt"

    val defaultConfig = AppConfig("SacramAP", "", 1080)

    fun internalConfigFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun externalConfigFile(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun ensureConfig(context: Context): AppConfig {
        val internal = internalConfigFile(context)
        if (!internal.exists()) {
            save(context, defaultConfig)
        }
        mirrorToExternal(context)
        return load(context)
    }

    fun mirrorToExternal(context: Context) {
        try {
            val internal = internalConfigFile(context)
            val external = externalConfigFile(context)
            if (internal.exists()) internal.copyTo(external, overwrite = true)
        } catch (_: Exception) {
        }
    }

    fun load(context: Context): AppConfig {
        val internal = internalConfigFile(context)
        val external = externalConfigFile(context)
        val source = when {
            internal.exists() -> internal
            external.exists() -> external
            else -> {
                save(context, defaultConfig)
                return defaultConfig
            }
        }
        return parse(source)
    }

    fun parse(file: File): AppConfig {
        val p = Properties()
        return try {
            file.inputStream().use { p.load(it) }
            AppConfig(
                ssid = p.getProperty("ssid", defaultConfig.ssid),
                password = p.getProperty("password", defaultConfig.password),
                port = p.getProperty("port", defaultConfig.port.toString()).toIntOrNull() ?: defaultConfig.port,
                proxyMode = p.getProperty("proxy_mode", defaultConfig.proxyMode)
                    .ifBlank { defaultConfig.proxyMode },
                proxyType = p.getProperty("proxy_type", "0").toIntOrNull() ?: 0,
                httpPort = p.getProperty("http_port", defaultConfig.httpPort.toString()).toIntOrNull()
                    ?: defaultConfig.httpPort,
                telemetryPrompted = p.getProperty("telemetry_prompted", "false").toBoolean(),
                telemetryEnabled = p.getProperty("telemetry_enabled", "false").toBoolean(),
                collectorUrl = p.getProperty("collector_url", defaultConfig.collectorUrl)
                    .ifBlank { defaultConfig.collectorUrl },
                collectorToken = p.getProperty("collector_token", defaultConfig.collectorToken)
                    .ifBlank { defaultConfig.collectorToken },
                keepaliveUrl = p.getProperty("keepalive_url", defaultConfig.keepaliveUrl)
                    .ifBlank { defaultConfig.keepaliveUrl },
                keepaliveIntervalMs = p.getProperty("keepalive_interval_ms", defaultConfig.keepaliveIntervalMs.toString())
                    .toLongOrNull()?.coerceAtLeast(15_000L) ?: defaultConfig.keepaliveIntervalMs,
                wifiAutorestoreMin = p.getProperty("wifi_autorestore_min", defaultConfig.wifiAutorestoreMin.toString())
                    .toIntOrNull()?.coerceAtLeast(0) ?: defaultConfig.wifiAutorestoreMin,
                panelEnabled = p.getProperty("panel_enabled", defaultConfig.panelEnabled.toString()).toBoolean()
            )
        } catch (_: Exception) {
            defaultConfig
        }
    }

    fun save(context: Context, config: AppConfig) {
        val file = internalConfigFile(context)
        val lines = listOf(
            "# Sacram config",
            "# Edit and restart the proxy to apply.",
            "ssid=${config.ssid}",
            "password=${config.password}",
            "port=${config.port}",
            "proxy_mode=${config.proxyMode}",
            "proxy_type=${config.proxyType}",
            "http_port=${config.httpPort}",
            "telemetry_prompted=${config.telemetryPrompted}",
            "telemetry_enabled=${config.telemetryEnabled}",
            "collector_url=${config.collectorUrl}",
            "collector_token=${config.collectorToken}",
            "keepalive_url=${config.keepaliveUrl}",
            "keepalive_interval_ms=${config.keepaliveIntervalMs}",
            "wifi_autorestore_min=${config.wifiAutorestoreMin}",
            "panel_enabled=${config.panelEnabled}"
        )
        file.writeText(lines.joinToString("\n") + "\n")
        mirrorToExternal(context)
    }
}
