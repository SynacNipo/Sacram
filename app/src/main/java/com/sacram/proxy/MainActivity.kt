package com.sacram.proxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var btnToggle: Button
    private lateinit var etSsid: EditText
    private lateinit var etPass: EditText
    private lateinit var etPort: EditText

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it.values.all { granted -> granted }) {
            startProxy()
        } else {
            Toast.makeText(this, "Permissions denied - proxy may not start", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)
        btnToggle = findViewById(R.id.btnToggle)
        etSsid = findViewById(R.id.etSsid)
        etPass = findViewById(R.id.etPass)
        etPort = findViewById(R.id.etPort)

        val config = ConfigManager.ensureConfig(this)
        etSsid.setText(config.ssid)
        etPass.setText(config.password)
        etPort.setText(config.port.toString())

        btnToggle.setOnClickListener {
            if (AppState.running.value) {
                stopService(Intent(this, ProxyService::class.java))
            } else {
                checkPermissionsAndStart()
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveConfig() }

        findViewById<Button>(R.id.btnConfigPath).setOnClickListener {
            val internal = ConfigManager.internalConfigFile(this)
            val external = ConfigManager.externalConfigFile(this)
            ConfigManager.mirrorToExternal(this)
            Toast.makeText(
                this,
                "Config file:\n$internal\n\nUser-accessible copy:\n$external",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }

        findViewById<Button>(R.id.btnAutostart).setOnClickListener { openVivoAutostart() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AppState.status.collect { tvStatus.text = it } }
                launch { AppState.apInfo.collect { renderInfo(it) } }
                launch { AppState.running.collect { renderRunning(it) } }
            }
        }
    }

    private fun renderRunning(running: Boolean) {
        btnToggle.text = if (running) "STOP PROXY" else "START PROXY"
    }

    private fun renderInfo(info: ApInfo) {
        if (info.ssid.isEmpty()) {
            tvInfo.text = "--"
            return
        }
        tvInfo.text = """
            SSID:      ${info.ssid}
            Password:  ${info.passphrase}
            SOCKS5:    ${info.goIp}:${etPort.text.ifEmpty { "1080" }}
            Clients:   ${info.clients}
        """.trimIndent()
    }

    private fun saveConfig() {
        val pass = etPass.text.toString()
        if (pass.length < 8 || pass.length > 63) {
            Toast.makeText(this, "Password must be 8-63 characters", Toast.LENGTH_LONG).show()
            return
        }
        val port = etPort.text.toString().toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            Toast.makeText(this, "Invalid port", Toast.LENGTH_LONG).show()
            return
        }
        ConfigManager.save(
            this,
            AppConfig(
                ssid = etSsid.text.toString(),
                password = pass,
                port = port
            )
        )
        Toast.makeText(this, "Saved to config.txt", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startProxy()
        } else {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startProxy() {
        val intent = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        AppState.running.value = true
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already exempt from battery optimization", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openVivoAutostart() {
        val intents = listOf(
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Open Settings > Apps > Sacram and enable Autostart", Toast.LENGTH_LONG).show()
    }
}
