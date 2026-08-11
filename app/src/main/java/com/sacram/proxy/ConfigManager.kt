package com.sacram.proxy

import android.content.Context
import java.io.File
import java.util.Properties

data class AppConfig(
    val ssid: String,
    val password: String,
    val port: Int
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
                port = p.getProperty("port", defaultConfig.port.toString()).toIntOrNull() ?: defaultConfig.port
            )
        } catch (_: Exception) {
            defaultConfig
        }
    }

    fun save(context: Context, config: AppConfig) {
        val file = internalConfigFile(context)
        val lines = listOf(
            "# Sacram UDP Bridge config",
            "# Edit and restart the proxy to apply.",
            "ssid=${config.ssid}",
            "password=${config.password}",
            "port=${config.port}"
        )
        file.writeText(lines.joinToString("\n") + "\n")
        mirrorToExternal(context)
    }
}
