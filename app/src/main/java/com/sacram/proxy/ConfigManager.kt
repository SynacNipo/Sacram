package com.sacram.proxy

import android.content.Context
import java.io.File
import java.util.Properties

data class AppConfig(
    val ssid: String,
    val password: String,
    val port: Int,
    val proxyMode: String = "socks5",
    val httpPort: Int = 8282,
    val telemetryPrompted: Boolean = false,
    val telemetryEnabled: Boolean = false,
    val collectorUrl: String = "https://sacram-telemetry.synacnipo.workers.dev"
)

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
                httpPort = p.getProperty("http_port", defaultConfig.httpPort.toString()).toIntOrNull()
                    ?: defaultConfig.httpPort,
                telemetryPrompted = p.getProperty("telemetry_prompted", "false").toBoolean(),
                telemetryEnabled = p.getProperty("telemetry_enabled", "false").toBoolean(),
                collectorUrl = p.getProperty("collector_url", defaultConfig.collectorUrl)
                    .ifBlank { defaultConfig.collectorUrl }
            )
        } catch (_: Exception) {
            defaultConfig
        }
    }

    /** Save ssid/password/port while keeping mode and telemetry fields from the previous config. */
    fun saveSettings(context: Context, ssid: String, password: String, port: Int): AppConfig {
        val prev = load(context)
        val next = AppConfig(
            ssid = ssid,
            password = password,
            port = port,
            proxyMode = prev.proxyMode,
            httpPort = prev.httpPort,
            telemetryPrompted = prev.telemetryPrompted,
            telemetryEnabled = prev.telemetryEnabled,
            collectorUrl = prev.collectorUrl
        )
        save(context, next)
        return next
    }

    fun save(context: Context, config: AppConfig) {
        val file = internalConfigFile(context)
        val lines = listOf(
            "# Sacram UDP Bridge config",
            "# Edit and restart the proxy to apply.",
            "ssid=${config.ssid}",
            "password=${config.password}",
            "port=${config.port}",
            "proxy_mode=${config.proxyMode}",
            "http_port=${config.httpPort}",
            "telemetry_prompted=${config.telemetryPrompted}",
            "telemetry_enabled=${config.telemetryEnabled}",
            "collector_url=${config.collectorUrl}"
        )
        file.writeText(lines.joinToString("\n") + "\n")
        mirrorToExternal(context)
    }
}