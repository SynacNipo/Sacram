package com.sacram.proxy

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated control-panel HTTP server, fully independent of the SOCKS5 / HTTP
 * proxy traffic.
 *
 * Why a separate server + own thread pool: the panel used to be served inline
 * by [HttpProxyServer] on the SAME port and the SAME 256-thread worker pool as
 * the heavy CONNECT tunnels. When a tab hammered the proxy with dozens of
 * concurrent fetches, every worker could be busy pumping upstream sockets, so a
 * panel request queued behind them and the panel felt dead until the page
 * finished. This server runs on its own port with its own small pool, so the
 * panel always responds instantly regardless of how saturated the proxy is.
 *
 * It only ever serves local content (status JSON, the HTML page, restart +
 * settings forms) and never opens an upstream/egress socket, so it has zero
 * dependence on the cellular network being alive.
 */
class PanelServer(
    private val port: Int,
    private val context: Context,
    private val enabled: Boolean = true,
    private val onLog: (String) -> Unit = {},
    private val onRestartRequest: () -> Unit = {}
) {
    // Small dedicated pool. The panel is low-traffic (a few requests + a 5s
    // polling loop from one browser); it must never compete with the proxy's
    // worker pool, which is exactly why it gets its own executor here.
    private val workerExecutor = Executors.newFixedThreadPool(16)
    private val scope = CoroutineScope(SupervisorJob() + workerExecutor.asCoroutineDispatcher())
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var tcpJob: Job? = null

    fun start() {
        if (!enabled) {
            onLog("Control panel disabled")
            return
        }
        running.set(true)
        tcpJob = scope.launch { runServer() }
        onLog("Control panel listening on port $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        tcpJob?.cancel()
        scope.cancel()
        runCatching { workerExecutor.shutdownNow() }
    }

    private fun tuneSocket(sock: Socket) {
        runCatching { sock.setReceiveBufferSize(64 * 1024) }
        runCatching { sock.setSendBufferSize(64 * 1024) }
    }

    private suspend fun runServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            runCatching { ss.setReceiveBufferSize(64 * 1024) }
            ss.bind(InetSocketAddress("0.0.0.0", port), 64)
            serverSocket = ss
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                client.tcpNoDelay = true
                tuneSocket(client)
                scope.launch { handleClient(client) }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("Control panel server error: $e")
        }
    }

    private fun handleClient(client: Socket) {
        // Single buffered stream for both headers and body. IMPORTANT: the POST
        // body is read from this same stream, so we must NOT mix a separate
        // BufferedReader (which would swallow bytes ahead of the body read).
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
        try {
            val requestLine = readLine(input) ?: return
            if (requestLine.isEmpty()) return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            val headers = mutableListOf<String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }

            if (method == "POST") {
                if (target == "/restart") {
                    // Respond BEFORE restarting: restartProxy() stops this very
                    // PanelServer (closing the client socket), so if we triggered it
                    // first the browser would hang on "loading" forever with no reply.
                    writePanelPage(output, restartRequestedHtml())
                    onRestartRequest()
                    return
                }
                val cl = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()
                val body = if (cl != null && cl in 1..1_000_000) readExact(input, cl) else ""
                applyPanelForm(body)
                val cfg = ConfigManager.load(context)
                writePanelPage(output, if (cfg.requireApprovalRestart) pendingPageHtml() else savedPageHtml())
                return
            }
            if (target == "/api/status") {
                writeStatusJson(output)
                return
            }
            writePanelPage(output, buildPanelHtml())
        } catch (_: Exception) {
        } finally {
            runCatching { output.flush() }
            runCatching { client.close() }
        }
    }

    private fun writeStatusJson(output: BufferedOutputStream) {
        val cfg = ConfigManager.load(context)
        val info = AppState.apInfo.value
        val uptime = if (AppState.serviceStartedAt > 0)
            (System.currentTimeMillis() - AppState.serviceStartedAt) / 1000 else 0
        val uptimeStr = "${uptime / 3600}h ${(uptime % 3600) / 60}m ${uptime % 60}s"
        val mode = when {
            AppState.httpMode.value && info.clients >= 0 && cfg.effectiveMode() == "http" -> "HTTP"
            cfg.isHybrid() -> "Hybrid"
            cfg.effectiveMode() == "http" -> "HTTP"
            else -> "SOCKS5"
        }
        val json = buildString {
            append('{')
            append("\"status\":\"").append(escapeJson(AppState.status.value)).append("\",")
            append("\"running\":").append(AppState.running.value).append(',')
            append("\"uptime\":\"").append(uptimeStr).append("\",")
            append("\"mode\":\"").append(mode).append("\",")
            append("\"ssid\":\"").append(escapeJson(info.ssid)).append("\",")
            append("\"passphrase\":\"").append(escapeJson(info.passphrase)).append("\",")
            append("\"goIp\":\"").append(escapeJson(info.goIp)).append("\",")
            append("\"panelPort\":").append(port).append(',')
            append("\"clients\":").append(info.clients).append(',')
            append("\"tcpTunnels\":").append(AppState.tcpTunnels.value).append(',')
            append("\"requireApprovalRestart\":").append(cfg.requireApprovalRestart).append(',')
            append("\"version\":\"").append(BuildConfig.VERSION_NAME).append("\",")
            append("\"startedAt\":").append(AppState.serviceStartedAt).append(',')
            append("\"serverNow\":").append(System.currentTimeMillis())
            append('}')
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun restartRequestedHtml(): String {
        val cfg = ConfigManager.load(context)
        val msg = if (cfg.requireApprovalRestart)
            "Restart is waiting for the phone owner to approve it <b>inside the Sacram app</b> (10 second window)."
        else
            "Restarting the proxy + hotspot now. The panel will come back online in a few seconds."
        return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Panel</title>
        <style>body{font-family:system-ui,sans-serif;background:#0d1117;color:#e6edf3;padding:24px}
        a{color:#58a6ff}</style></head><body>
        <h1>Restart requested</h1>
        <p>$msg</p>
        <p><a href="/">Back to panel</a></p>
        </body></html>
        """.trimIndent()
    }

    private fun writePanelPage(output: BufferedOutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun pendingPageHtml(): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Panel</title>
        <style>body{font-family:system-ui,sans-serif;background:#0d1117;color:#e6edf3;padding:24px}
        a{color:#58a6ff}</style></head><body>
        <h1>Change requested</h1>
        <p>The requested settings change is waiting for the phone owner to approve it <b>inside the Sacram app</b> (10 second window).</p>
        <p>If the owner ignores or denies it, <b>nothing changes</b>.</p>
        <p><a href="/">Back to panel</a></p>
        </body></html>
        """.trimIndent()

    private fun savedPageHtml(): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Panel</title>
        <style>body{font-family:system-ui,sans-serif;background:#0d1117;color:#e6edf3;padding:24px}
        a{color:#58a6ff}</style></head><body>
        <h1>Settings saved</h1>
        <p>The changes were applied immediately (owner approval not required).</p>
        <p><a href="/">Back to panel</a></p>
        </body></html>
        """.trimIndent()

    private fun readExact(input: InputStream, n: Int): String {
        val buf = ByteArray(n)
        var r = 0
        while (r < n) {
            val m = input.read(buf, r, n - r)
            if (m <= 0) break
            r += m
        }
        return String(buf, 0, r, Charsets.UTF_8)
    }

    /**
     * Reads a single CRLF/LF-terminated header line from [ins]. Reads one byte at
     * a time so it never overtakes the [BufferedInputStream] used for the body.
     */
    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = ins.read()
            if (b == -1) {
                if (sb.isEmpty()) return null
                break
            }
            if (b == '\n'.code) {
                if (prev == '\r'.code) sb.setLength(sb.length - 1)
                break
            }
            sb.append(b.toChar())
            prev = b
        }
        return sb.toString()
    }

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun applyPanelForm(body: String) {
        val map = mutableMapOf<String, String>()
        body.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) urlDecode(pair.substring(0, idx)) else urlDecode(pair)
            val v = if (idx >= 0) urlDecode(pair.substring(idx + 1)) else ""
            map[k] = v
        }
        val cfg = ConfigManager.load(context)
        if (cfg.requireApprovalRestart) {
            PanelApproval.submit(map)
            onLog("Panel change requested - awaiting in-app approval")
        } else {
            // Owner disabled approval: apply settings immediately, no prompt.
            PanelApproval.applyFields(context, map)
            onLog("Panel settings applied (no approval required)")
        }
    }

    private fun buildPanelHtml(): String {
        val cfg = ConfigManager.load(context)
        val info = AppState.apInfo.value
        val uptime = if (AppState.serviceStartedAt > 0)
            (System.currentTimeMillis() - AppState.serviceStartedAt) / 1000 else 0
        val uptimeStr = "${uptime / 3600}h ${(uptime % 3600) / 60}m ${uptime % 60}s"
        val mode = when {
            AppState.httpMode.value && info.clients >= 0 && cfg.effectiveMode() == "http" -> "HTTP"
            cfg.isHybrid() -> "Hybrid"
            cfg.effectiveMode() == "http" -> "HTTP"
            else -> "SOCKS5"
        }
        val telChecked = if (cfg.telemetryEnabled) "checked" else ""
        val panelChecked = if (cfg.panelEnabled) "checked" else ""
        val restartNote = if (cfg.requireApprovalRestart)
            "Restarts the proxy + hotspot. Requires in-app owner approval (10s window)."
        else
            "Restarts the proxy + hotspot immediately (no approval). Enable \"Require approval for panel restart\" in the app's Keep-Alive tab to gate it."
        return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Panel</title>
        <style>
        body{font-family:system-ui,sans-serif;margin:0;background:#0d1117;color:#e6edf3;padding:16px}
        h1{font-size:20px;margin:0 0 12px}.card{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:14px;margin-bottom:14px}
        .row{display:flex;justify-content:space-between;padding:4px 0;border-bottom:1px solid #21262d}
        .row:last-child{border-bottom:0}.k{color:#8b949e}.v{font-weight:600;word-break:break-all;text-align:right;max-width:60%}
        label{display:block;margin:10px 0 4px;color:#8b949e;font-size:13px}
        input[type=text],input[type=number]{width:100%;box-sizing:border-box;padding:9px;border-radius:8px;border:1px solid #30363d;background:#0d1117;color:#e6edf3;font-size:14px}
        .checkbox{display:flex;align-items:center;gap:8px;margin:10px 0}
        button{width:100%;padding:12px;border:0;border-radius:8px;background:#238636;color:#fff;font-size:15px;font-weight:600;margin-top:6px}
        button.restart{background:#1f6feb}
        .note{font-size:12px;color:#8b949e;margin-top:8px}
        code{background:#21262d;padding:1px 5px;border-radius:4px}
        </style></head><body>
        <h1>Sacram Control Panel</h1>
        <div class="card">
            <div class="row"><span class="k">Status</span><span class="v" id="v-status">${escapeHtml(AppState.status.value)}</span></div>
            <div class="row"><span class="k">Running</span><span class="v" id="v-running">${AppState.running.value}</span></div>
            <div class="row"><span class="k">Uptime</span><span class="v" id="v-uptime">$uptimeStr</span></div>
            <div class="row"><span class="k">Mode</span><span class="v" id="v-mode">$mode</span></div>
            <div class="row"><span class="k">SSID</span><span class="v" id="v-ssid">${escapeHtml(info.ssid)}</span></div>
            <div class="row"><span class="k">Password</span><span class="v" id="v-pass">${escapeHtml(info.passphrase)}</span></div>
            <div class="row"><span class="k">Group IP</span><span class="v" id="v-goip">${escapeHtml(info.goIp)}</span></div>
            <div class="row"><span class="k">Clients</span><span class="v" id="v-clients">${info.clients}</span></div>
            <div class="row"><span class="k">TCP tunnels open</span><span class="v" id="v-tunnels">${AppState.tcpTunnels.value}</span></div>
            <div class="row"><span class="k">Panel port</span><span class="v" id="v-panelport">$port</span></div>
            <div class="row"><span class="k">Version</span><span class="v" id="v-ver">${BuildConfig.VERSION_NAME}</span></div>
        </div>
        <form method="post" action="/restart">
            <div class="card">
                <button type="submit" class="restart">Restart proxy</button>
                <div class="note">$restartNote</div>
            </div>
        </form>
        <form method="post" action="/">
            <div class="card">
                <label>Keep-alive URL</label>
                <input type="text" name="keepalive_url" value="${escapeHtml(cfg.keepaliveUrl)}">
                <label>Keep-alive interval (seconds, min 15)</label>
                <input type="number" name="keepalive_interval" value="${cfg.keepaliveIntervalMs / 1000}" min="15">
                <div class="checkbox"><input type="checkbox" name="telemetry_enabled" value="on" $telChecked><span>Telemetry enabled</span></div>
                <div class="checkbox"><input type="checkbox" name="panel_enabled" value="on" $panelChecked><span>Control panel enabled</span></div>
                <label>WiFi Band</label>
                <select name="band" style="width:100%;padding:9px;border-radius:8px;border:1px solid #30363d;background:#0d1117;color:#e6edf3;font-size:14px">
                    <option value="2.4"${if (cfg.band == "2.4") " selected" else ""}>2.4 GHz (default)</option>
                    <option value="5"${if (cfg.band == "5") " selected" else ""}>5 GHz</option>
                    <option value="auto"${if (cfg.band == "auto") " selected" else ""}>Auto</option>
                </select>
                <button type="submit">Save settings</button>
                <div class="note">Changes apply live. The panel runs on its own port ($port) and is reachable by anyone on the WiFi Direct network. If your browser sends all traffic through the proxy, add <code>${escapeHtml(info.goIp)}</code> to its proxy bypass list to reach this panel directly.</div>
            </div>
        </form>
        <div class="note">SOCKS5: <code>${escapeHtml(info.goIp)}:${cfg.port}</code> &nbsp; HTTP: <code>${escapeHtml(info.goIp)}:${cfg.httpPort}</code></div>
        <script>
        var sacramOffset=0, sacramStarted=0;
        function sacramFmtUptime(sec){
          if(!isFinite(sec)||sec<0)sec=0;
          var h=Math.floor(sec/3600), m=Math.floor((sec%3600)/60), s=Math.floor(sec%60);
          return h+'h '+m+'m '+s+'s';
        }
        function sacramTick(){
          if(sacramStarted>0){
            var e=document.getElementById('v-uptime');
            if(e) e.textContent=sacramFmtUptime((Date.now()+sacramOffset-sacramStarted)/1000);
          }
        }
        async function sacramRefresh(){
          try{
            var r=await fetch('/api/status',{cache:'no-store'});
            var d=await r.json();
            var set=function(id,v){var e=document.getElementById(id);if(e)e.textContent=v;};
            if(d.startedAt>0){sacramStarted=d.startedAt;sacramOffset=d.serverNow-Date.now();}
            set('v-status',d.status);set('v-running',d.running);set('v-uptime',d.uptime);
            set('v-mode',d.mode);set('v-ssid',d.ssid);set('v-pass',d.passphrase);
            set('v-goip',d.goIp);set('v-clients',d.clients);set('v-tunnels',d.tcpTunnels);
            set('v-panelport',d.panelPort);set('v-ver',d.version);
            sacramTick();
          }catch(e){}
        }
        sacramRefresh();
        setInterval(sacramRefresh,5000);
        setInterval(sacramTick,1000);
        </script>
        </body></html>
        """.trimIndent()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
