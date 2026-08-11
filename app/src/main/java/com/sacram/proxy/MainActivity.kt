package com.sacram.proxy

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "SacramMain"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvSaved: TextView
    private lateinit var btnToggle: Button
    private lateinit var etSsid: EditText
    private lateinit var etPass: EditText
    private lateinit var etPort: EditText

    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosaveRunnable = Runnable { autosave() }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        Log.i(TAG, "permission results: $it")
        if (it.values.all { granted -> granted }) {
            startProxy()
        } else {
            val denied = it.filterValues { !it }.keys.joinToString(",")
            Telemetry.send(this, "permissions_denied", mapOf("missing" to denied))
            Log.e(TAG, "permissions denied: $denied")
            Toast.makeText(this, "Permissions denied - proxy may not start", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)
        tvSaved = findViewById(R.id.tvSaved)
        btnToggle = findViewById(R.id.btnToggle)
        etSsid = findViewById(R.id.etSsid)
        etPass = findViewById(R.id.etPass)
        etPort = findViewById(R.id.etPort)

        val config = ConfigManager.ensureConfig(this)
        Log.i(TAG, "onCreate - loaded config ssid=${config.ssid} passLen=${config.password.length} port=${config.port} tel=${config.telemetryEnabled}")
        Telemetry.send(this, "app_launched", mapOf("config_file" to "true"))
        etSsid.setText(config.ssid)
        etPass.setText(config.password)
        etPort.setText(config.port.toString())
        findViewById<TextView>(R.id.tvConfigPath).text =
            "config.txt: ${ConfigManager.externalConfigFile(this).absolutePath}"

        setupTabs()
        setupAutosave()

        // Force setting a WiFi password on launch before anything else
        if (config.password.length !in 8..63) {
            showPasswordPrompt()
        } else {
            maybeShowTelemetryPrompt()
        }

        btnToggle.setOnClickListener {
            if (AppState.running.value) {
                stopService(Intent(this, ProxyService::class.java))
            } else {
                onStartClicked()
            }
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener { openVivoAutostart() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AppState.status.collect { tvStatus.text = it } }
                launch { AppState.apInfo.collect { renderInfo(it) } }
                launch { AppState.running.collect { renderRunning(it) } }
                launch {
                    // Heartbeat: every 30 min while the app is visible.
                    // Cancelled automatically when the app leaves the foreground.
                    while (true) {
                        delay(30 * 60 * 1000)
                        val cfg = ConfigManager.load(this@MainActivity)
                        Telemetry.send(
                            this@MainActivity,
                            "heartbeat",
                            mapOf(
                                "running" to "${AppState.running.value}",
                                "status" to AppState.status.value,
                                "clients" to "${AppState.apInfo.value.clients}",
                                "telemetry_enabled" to "${cfg.telemetryEnabled}"
                            )
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart - passwordLength=${etPass.text.length}, running=${AppState.running.value}")
    }

    override fun onDestroy() {
        saveHandler.removeCallbacks(autosaveRunnable)
        super.onDestroy()
    }

    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val tabProxy = findViewById<LinearLayout>(R.id.tabProxy)
        val tabKeepalive = findViewById<LinearLayout>(R.id.tabKeepalive)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabProxy.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                tabKeepalive.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupAutosave() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                saveHandler.removeCallbacks(autosaveRunnable)
                saveHandler.postDelayed(autosaveRunnable, 1200)
            }

            override fun afterTextChanged(s: Editable?) {}
        }
        etSsid.addTextChangedListener(watcher)
        etPass.addTextChangedListener(watcher)
        etPort.addTextChangedListener(watcher)
    }

    private fun autosave() {
        val pass = etPass.text.toString()
        val ssid = etSsid.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull()
        if (pass.length !in 8..63) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Password must be 8-63 characters - not saved yet"
            return
        }
        if (port == null || port < 1 || port > 65535) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Invalid port - not saved yet"
            return
        }
        ConfigManager.saveSettings(
            this,
            ssid.ifEmpty { ConfigManager.defaultConfig.ssid },
            pass,
            port
        )
        tvSaved.setTextColor(0xFF2E7D32.toInt())
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvSaved.text = "Saved to config.txt \u2713 $time"
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

    private fun onStartClicked() {
        val pass = etPass.text.toString()
        Log.i(TAG, "START clicked - passLen=${pass.length}, running=${AppState.running.value}")
        if (pass.length < 8 || pass.length > 63) {
            Log.w(TAG, "password invalid -> showing prompt")
            showPasswordPrompt()
        } else {
            autosave()
            checkPermissionsAndStart()
        }
    }

    private fun showPasswordPrompt() {
        val view = layoutInflater.inflate(R.layout.dialog_password, null)
        val etDialogSsid = view.findViewById<EditText>(R.id.etDialogSsid)
        val etDialogPass = view.findViewById<EditText>(R.id.etDialogPass)
        etDialogSsid.setText(etSsid.text.toString())
        etDialogPass.setText("")

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Set WiFi password")
            .setMessage("Password must be 8-63 characters. This cannot be skipped - clients need it to join the hotspot.")
            .setView(view)
            .setPositiveButton("Save & Start", null)
            .create()

        // Cannot be ignored: no back button, no outside tap, no cancel
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = etDialogPass.text.toString()
                val s = etDialogSsid.text.toString()
                if (p.length < 8 || p.length > 63) {
                    Toast.makeText(this, "Password must be 8-63 characters", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val port = etPort.text.toString().toIntOrNull() ?: 1080
                ConfigManager.saveSettings(this, s, p, port)
                etSsid.setText(s)
                etPass.setText(p)
                dialog.dismiss()
                maybeShowTelemetryPrompt()
                if (AppState.running.value) {
                    Toast.makeText(this, "Password saved. Restart the proxy to apply.", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun maybeShowTelemetryPrompt() {
        val cfg = ConfigManager.load(this)
        if (cfg.telemetryPrompted) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Help improve Sacram?")
            .setMessage(
                "Send anonymous app usage data (device model, Android version, proxy errors) " +
                    "so the app can be improved? No SSID, password or personal data is ever sent. " +
                    "You can opt out anytime by editing config.txt."
            )
            .setNegativeButton("No thanks") { d, _ ->
                ConfigManager.save(this, cfg.copy(telemetryPrompted = true, telemetryEnabled = false))
                d.dismiss()
            }
            .setPositiveButton("Yes, share") { d, _ ->
                ConfigManager.save(this, cfg.copy(telemetryPrompted = true, telemetryEnabled = true))
                d.dismiss()
                Telemetry.send(this, "telemetry_opted_in")
            }
            .create()
            .show()
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
        Log.i(TAG, "permissions needed=$needed missing=$missing")
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
