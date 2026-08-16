package com.sacram.proxy

import android.Manifest
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
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.DialogInterface
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
        val PROXY_TYPE_LABELS = listOf(
            "Auto (default mode)",
            "UDP / SOCKS5 [Experimental]",
            "HTTP",
            "Hybrid (SOCKS5 + HTTP) [Experimental]"
        )
        val EXPERIMENTAL_TYPES = setOf(1, 3)
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvSaved: TextView
    private lateinit var btnToggle: Button
    private lateinit var etSsid: EditText
    private lateinit var etPass: EditText
    private lateinit var etPort: EditText
    private lateinit var etProxyType: AutoCompleteTextView
    private lateinit var etHttpPort: EditText
    private lateinit var etKeepaliveUrl: EditText
    private lateinit var etKeepaliveInterval: EditText
    private lateinit var etWifiRestore: EditText
    private lateinit var tilPort: com.google.android.material.textfield.TextInputLayout
    private lateinit var tilHttpPort: com.google.android.material.textfield.TextInputLayout

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
            Toast.makeText(this, "Some permissions denied - starting anyway, WiFi Direct may fail", Toast.LENGTH_LONG).show()
            startProxy()
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
        etProxyType = findViewById(R.id.etProxyType)
        etHttpPort = findViewById(R.id.etHttpPort)

        val config = ConfigManager.ensureConfig(this)
        etSsid.setText(config.ssid)
        etPass.setText(config.password)
        etPort.setText(config.port.toString())
        etHttpPort.setText(config.httpPort.toString())
        etKeepaliveUrl.setText(config.keepaliveUrl)
        etKeepaliveInterval.setText((config.keepaliveIntervalMs / 1000).toString())
        etWifiRestore.setText(config.wifiAutorestoreMin.toString())
        tilPort = findViewById(R.id.tilPort)
        tilHttpPort = findViewById(R.id.tilHttpPort)
        setupProxyTypeDropdown(config.proxyType)
        updatePortVisibility(config.proxyType)
        findViewById<TextView>(R.id.tvConfigPath).text =
            "config.txt: ${ConfigManager.externalConfigFile(this).absolutePath}"

        setupTabs()
        setupAutosave()
        setupEasterEgg()
        observePanelApproval()

        // Notify if no valid password is set, but don't block anything
        if (config.password.length !in 8..63) {
            Log.w(TAG, "no valid password set yet - will error on start until set")
            tvStatus.text = "Stopped - set a WiFi password (8-63 chars) first"
        }
        maybeShowTelemetryPrompt()

        btnToggle.setOnClickListener {
            if (AppState.running.value) {
                ProxyState.setShouldRun(this, false)
                stopService(Intent(this, ProxyService::class.java))
            } else {
                startSelectedProxy()
            }
        }

        findViewById<Button>(R.id.btnWiki).setOnClickListener { openWiki() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener { openAutostartSettings() }

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
                            ) + Telemetry.batteryInfo(this@MainActivity)
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
        etHttpPort.addTextChangedListener(watcher)
        etKeepaliveUrl.addTextChangedListener(watcher)
        etKeepaliveInterval.addTextChangedListener(watcher)
        etWifiRestore.addTextChangedListener(watcher)
    }

    private fun setupProxyTypeDropdown(selected: Int) {
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            PROXY_TYPE_LABELS
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val tv = v.findViewById<TextView>(android.R.id.text1)
                tv.setTextColor(
                    if (position in EXPERIMENTAL_TYPES) 0xFFC62828.toInt()
                    else ContextCompat.getColor(this@MainActivity, R.color.text_primary)
                )
                return v
            }
        }
        etProxyType.setAdapter(adapter)
        etProxyType.setText(PROXY_TYPE_LABELS.getOrElse(selected) { PROXY_TYPE_LABELS[0] }, false)
        applyProxyTypeColor(selected)
        etProxyType.setOnItemClickListener { _, _, position, _ ->
            etProxyType.setText(PROXY_TYPE_LABELS[position], false)
            applyProxyTypeColor(position)
            updatePortVisibility(position)
            autosave()
        }
    }

    private fun applyProxyTypeColor(position: Int) {
        etProxyType.setTextColor(
            if (position in EXPERIMENTAL_TYPES) 0xFFC62828.toInt()
            else ContextCompat.getColor(this, R.color.text_primary)
        )
    }

    /**
     * Show only the relevant port field(s) for the chosen proxy type so the
     * form doesn't waste vertical space. Auto (0) and SOCKS5 (1) -> SOCKS5 port;
     * HTTP (2) -> HTTP port; Hybrid (3) -> both side-by-side in the row.
     * A single visible port expands to full width.
     */
    private fun updatePortVisibility(proxyType: Int) {
        val showSocks = proxyType != 2
        val showHttp = proxyType == 2 || proxyType == 3
        tilPort.visibility = if (showSocks) View.VISIBLE else View.GONE
        tilHttpPort.visibility = if (showHttp) View.VISIBLE else View.GONE
        (tilPort.layoutParams as LinearLayout.LayoutParams).weight = if (showSocks && !showHttp) 2f else 1f
        (tilHttpPort.layoutParams as LinearLayout.LayoutParams).weight = if (showHttp && !showSocks) 2f else 1f
    }

    private var eggTaps = 0
    private var approvalDialog: androidx.appcompat.app.AlertDialog? = null


    private fun setupEasterEgg() {
        tvStatus.setOnClickListener {
            if (++eggTaps >= 7) {
                eggTaps = 0
                Toast.makeText(
                    this,
                    "\uD83D\uDEF0 You found the Sacram easter egg - stay proxy, my friend.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Show an in-app approve/deny prompt whenever a device on the network submits
     * a panel change. The request is dropped if the owner ignores it for 10s.
     */
    private fun observePanelApproval() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PanelApproval.pending.collect { req ->
                    if (req == null) {
                        approvalDialog?.dismiss()
                        approvalDialog = null
                        return@collect
                    }
                    showApprovalDialog(req)
                }
            }
        }
    }

    private fun showApprovalDialog(req: PanelApproval.Request) {
        approvalDialog?.takeIf { it.isShowing }?.dismiss()
        val summary = req.fields.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Approve panel change?")
            .setMessage(
                "A device on the WiFi requested these setting changes:\n\n$summary\n\n" +
                    "Approve within 10 seconds, otherwise the request is dropped."
            )
            .setCancelable(false)
            .setPositiveButton("Approve") { _, _ -> PanelApproval.approve(this) }
            .setNegativeButton("Deny") { _, _ -> PanelApproval.deny() }
            .create()
        dialog.setOnDismissListener {
            if (PanelApproval.current()?.id == req.id) PanelApproval.deny()
            if (approvalDialog === dialog) approvalDialog = null
        }
        approvalDialog = dialog
        dialog.show()
        lifecycleScope.launch {
            delay(PanelApproval.APPROVE_WINDOW_MS)
            if (PanelApproval.current()?.id == req.id) {
                PanelApproval.deny()
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    private fun autosave() {
        val pass = etPass.text.toString()
        val ssid = etSsid.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull()
        val httpPort = etHttpPort.text.toString().toIntOrNull()
        val proxyType = PROXY_TYPE_LABELS.indexOf(etProxyType.text.toString()).let {
            if (it < 0) 0 else it
        }
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
        if (httpPort == null || httpPort < 1 || httpPort > 65535) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Invalid HTTP port - not saved yet"
            return
        }
        val keepaliveUrl = etKeepaliveUrl.text.toString().trim()
        val intervalSec = etKeepaliveInterval.text.toString().toLongOrNull()
        if (intervalSec != null && intervalSec < 15) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Keep-alive interval must be >= 15s - not saved yet"
            return
        }
        val wifiRestoreMin = etWifiRestore.text.toString().toIntOrNull()
        if (wifiRestoreMin != null && wifiRestoreMin < 0) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "WiFi auto-restore minutes must be >= 0 - not saved yet"
            return
        }
        if (proxyType !in 0..3) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Proxy type must be 0, 1, 2, or 3 - not saved yet"
            return
        }
        val prev = ConfigManager.load(this)
        ConfigManager.save(
            this,
            prev.copy(
                ssid = ssid.ifEmpty { ConfigManager.defaultConfig.ssid },
                password = pass,
                port = port,
                proxyType = proxyType,
                httpPort = httpPort,
                keepaliveUrl = keepaliveUrl,
                keepaliveIntervalMs = (intervalSec ?: (prev.keepaliveIntervalMs / 1000)) * 1000L,
                wifiAutorestoreMin = wifiRestoreMin ?: prev.wifiAutorestoreMin
            )
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
            HTTP:      ${info.goIp}:${etHttpPort.text.ifEmpty { "8282" }}
            Clients:   ${info.clients}
        """.trimIndent()
    }

    private fun startSelectedProxy() {
        val pass = etPass.text.toString()
        Log.i(TAG, "START clicked - passLen=${pass.length}")
        if (pass.length < 8 || pass.length > 63) {
            Log.w(TAG, "password invalid -> refusing to start")
            tvStatus.text = "ERROR: set a WiFi password (8-63 chars) first"
            Toast.makeText(this, "Set a WiFi password (8-63 chars) before starting", Toast.LENGTH_LONG).show()
            return
        }
        val idx = PROXY_TYPE_LABELS.indexOf(etProxyType.text.toString()).let { if (it < 0) 0 else it }
        etProxyType.setText(PROXY_TYPE_LABELS[idx], false)
        autosave()
        checkPermissionsAndStart()
    }

    private fun maybeShowTelemetryPrompt() {
        val cfg = ConfigManager.load(this)
        if (cfg.telemetryPrompted) return

        val message = "Sacram can send anonymous usage data to help improve the app. When enabled it collects:\n\n" +
            "• Device model, Android version, app version\n" +
            "• Proxy events, errors and heartbeats\n" +
            "• The domain names (hosts) of sites you access through the proxy and their HTTP status codes — used to debug connection drops. No full URLs, search queries, SSID, password or IP addresses are ever collected.\n\n" +
            "You can disable it anytime by setting telemetry_enabled=false in config.txt."

        val checkBox = CheckBox(this).apply {
            text = "I understand and agree to share this data"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 14f
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
            val tv = TextView(this@MainActivity).apply {
                text = message
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 14f
                setLineSpacing(2f, 1.1f)
            }
            addView(tv)
            addView(checkBox)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Help improve Sacram?")
            .setView(body)
            .setCancelable(false)
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

        dialog.setOnShowListener {
            val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            val negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
            // Theme colorPrimary is near-black, which made these buttons
            // invisible on the dark dialog - force readable colors.
            positive.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
            negative.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            positive.isEnabled = false
            checkBox.setOnCheckedChangeListener { _, checked -> positive.isEnabled = checked }
        }
        dialog.show()
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
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

    private fun openWiki() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SynacNipo/Sacram/wiki")))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAutostartSettings() {
        val intents = listOf(
            Intent().setClassName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
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
