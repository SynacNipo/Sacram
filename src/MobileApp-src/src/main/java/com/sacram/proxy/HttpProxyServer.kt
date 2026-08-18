package com.sacram.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP proxy (RFC 7230 style): handles plain HTTP requests with absolute-form
 * URIs and CONNECT tunnels (HTTPS). No UDP - this is TCP-only by design.
 */
class HttpProxyServer(
    private val port: Int,
    private val context: Context,
    private val goIp: String = "192.168.49.1",
    private val panelEnabled: Boolean = true,
    private val onLog: (String) -> Unit = {},
    private val onRestartRequest: () -> Unit = {},
    private val onStaleDetected: () -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var tcpJob: Job? = null
    private var cellularNetwork: Network? = null
    // Last network we confirmed had cellular+internet. We keep this even after a
    // transient onLost so egress never silently falls back to the WiFi-Direct
    // interface (which has no internet) and kills every connection at once.
    private var lastGoodCellular: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Cached outbound (cellular) network selection, mirroring Socks5Server.
    // NetworkUtils.pickCellular() scans cm.allNetworks + calls
    // getNetworkCapabilities() per network - too expensive to run on every
    // new upstream connection (a busy page opens dozens in parallel). Cache
    // the resolved Network and only re-validate on a short timer.
    private var cachedNet: Network? = null
    private var cachedNetTime = 0L

    private fun pickNet(): Network? {
        val now = System.currentTimeMillis()
        val cached = cachedNet
        if (cached != null && now - cachedNetTime < 8000 && isValidCellular(cached)) {
            return cached
        }
        val n = NetworkUtils.pickCellular(cm, cellularNetwork) ?: lastGoodCellular ?: cached
        cachedNet = n
        cachedNetTime = now
        return n
    }

    private val dnsCache = ConcurrentHashMap<String, Pair<InetAddress, Long>>()
    private val connPool = ConcurrentHashMap<String, MutableList<Pair<Socket, Long>>>()
    // Per-request telemetry sampler: we never log full URLs, only the host
    // (domain) and HTTP status, and we sample successes heavily so a busy
    // browsing session can't flood the capped telemetry store. Failures are
    // always reported so connection breakage is captured.
    private val reqCounter = AtomicInteger(0)
    // Auto-heal: last time we saw a successful vs a failed upstream request.
    // If failures keep happening but nothing succeeds for STALE_TIMEOUT_MS, the
    // egress network (cellular) has almost certainly gone stale and we ask the
    // service to restart the proxy so it re-binds a fresh cellular network.
    private val lastSuccessMs = AtomicLong(0L)
    private val lastFailureMs = AtomicLong(0L)
    private val autoRestartGuard = AtomicBoolean(false)
    private var staleWatchdogJob: Job? = null
    private val STALE_TIMEOUT_MS = 2 * 60_000L
    private val dnsTtlMs = 60_000L
    private val poolMax = 4
    private val poolIdleMs = 60_000L
    private val connectTimeoutMs = 10_000
    private val readTimeoutMs = 20_000
    // Idle timeout for CONNECT tunnels. Per-tunnel and deliberately longer than
    // readTimeoutMs: a stalled upstream (server->client direction) with no
    // soTimeout would block the pumping coroutine forever, leaking the slot.
    // Multiple streams over cellular have naturally longer buffer-starved gaps,
    // so 100s idle (not 20s) keeps live-but-quiet tunnels alive. Triggers a
    // SocketTimeoutException on read, which pump() treats as EOF and closes.
    private val tunnelIdleTimeoutMs = 100_000
    // Live count of open CONNECT tunnels, surfaced via AppState for the panel.
    private val tunnelCount = AtomicInteger(0)

    private fun bindToCellular() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                lastGoodCellular = network
                clearPool()
                clearDns()
                onLog("Bound to cellular network: $network")
            }
            override fun onLost(network: Network) {
                if (cellularNetwork != network) return
                // A blip while we're the WiFi-Direct Group Owner is common. Don't
                // drop egress: try to find another live cellular network first,
                // otherwise keep the reference so callers can still bind to it.
                val alt = cm.allNetworks.firstOrNull { isValidCellular(it) }
                if (alt != null && alt != network) {
                    cellularNetwork = alt
                    lastGoodCellular = alt
                    clearPool()
                    clearDns()
                    onLog("Cellular network switched: $network -> $alt")
                } else {
                    // No replacement yet - drop any sockets bound to the now-dead
                    // network and forget the stale reference so the next request
                    // re-scans cm.allNetworks and binds to a fresh cellular network
                    // the instant Android restores it (instead of clinging to the
                    // dead binding for the whole outage).
                    cellularNetwork = null
                    lastGoodCellular = null
                    clearPool()
                    clearDns()
                    onLog("WARNING: cellular network $network lost; egress will re-scan for a live cellular network")
                }
            }
        }
        netCallback = cb
        cm.requestNetwork(request, cb)
        scope.launch {
            delay(3000)
            if (cellularNetwork == null && lastGoodCellular == null) {
                onLog("WARNING: no cellular network available - outbound sockets use the default route")
            }
        }
    }

    fun start() {
        running.set(true)
        bindToCellular()
        tcpJob = scope.launch { runServer() }
        staleWatchdogJob = scope.launch { runStaleWatchdog() }
        onLog("HTTP proxy listening on port $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        staleWatchdogJob?.cancel()
        clearPool()
        netCallback?.let {
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        tcpJob?.cancel()
        scope.cancel()
    }

    private suspend fun runServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = ss
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                client.tcpNoDelay = true
                scope.launch { handleClient(client) }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("HTTP server error: $e")
        }
    }

    private suspend fun handleClient(client: Socket) {
        val reader = StreamReader(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        try {
            client.soTimeout = 300000
            while (running.get()) {
                val requestLine = readLine(reader) ?: break
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(" ")
                if (parts.size < 3) break
                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]
                val headers = readHeaders(reader) ?: break

                if (method == "CONNECT") {
                    handleConnect(client, reader, output, target)
                    break
                }

                if (panelEnabled && isPanelRequest(method, target, headers)) {
                    servePanel(reader, output, method, target, headers)
                    break
                }

                val (host, port, path) = parseAbsoluteUri(target, headers)
                if (host == null) {
                    writeSimpleResponse(output, 400, "Bad Request - absolute URI required")
                    break
                }
                val keepAlive = headers.any { it.startsWith("Connection:", true) && it.contains("keep-alive", true) }
                forwardPlain(reader, output, method, host, port, path, headers)
                if (!keepAlive) break
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun forwardPlain(
        input: StreamReader,
        output: BufferedOutputStream,
        method: String,
        host: String,
        port: Int,
        path: String,
        headers: List<String>
    ) {
        var upstream: Socket? = null
        try {
            upstream = acquireUpstream(host, port)
            val upOut = BufferedOutputStream(upstream.getOutputStream())

            val sb = StringBuilder()
            sb.append("$method $path HTTP/1.1\r\n")
            for (h in headers) {
                if (h.startsWith("Proxy-", true)) continue
                if (h.startsWith("Connection:", true)) continue
                if (h.startsWith("Host:", true)) continue
                if (h.startsWith("Proxy-Connection:", true)) continue
                sb.append(h).append("\r\n")
            }
            sb.append("Host: $host:$port\r\n")
            sb.append("Connection: keep-alive\r\n\r\n")
            upOut.write(sb.toString().toByteArray())
            upOut.flush()

            // pipe request body if present (Content-Length or chunked)
            val contentLength = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
            if (method == "POST" || method == "PUT" || method == "PATCH") {
                if (contentLength != null) {
                    val body = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(body, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    upOut.write(body, 0, read)
                    upOut.flush()
                } else {
                    pumpChunked(input, upOut)
                }
            }
            onLog("HTTP $method $host:$port$path")

            val upIn = StreamReader(upstream.getInputStream())
            val statusLine = readLine(upIn) ?: throw IOException("no response from upstream")
            val respHeaders = readHeaders(upIn) ?: throw IOException("no response headers")
            val statusCode = statusLine.split(" ")[1]
            reportRequest(host, port, method, statusCode)

            val chunked = respHeaders.any {
                it.startsWith("Transfer-Encoding:", true) && it.contains("chunked", true)
            }
            val respLength = respHeaders.firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toLongOrNull()
            val upstreamClose = respHeaders.any {
                it.startsWith("Connection:", true) && it.contains("close", true)
            }
            val upstreamKeepAlive = !upstreamClose && (chunked || respLength != null)

            writeResponseHeaders(output, statusLine, respHeaders, upstreamKeepAlive)

            when {
                chunked -> forwardChunkedResponse(upIn, output)
                respLength != null -> pumpFixed(upIn, output, respLength)
                else -> pump(upIn, output)
            }
            output.flush()

            if (upstreamKeepAlive) {
                releaseUpstream(host, port, upstream)
                upstream = null
            }
        } catch (e: Exception) {
            onLog("HTTP fail $host:$port: ${e.message}")
            reportRequest(host, port, method, "fail")
            writeSimpleResponse(output, 502, "Bad Gateway - ${e.message}")
        } finally {
            runCatching { upstream?.close() }
        }
    }

    private fun isValidCellular(n: Network?): Boolean {
        if (n == null) return false
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun reportRequest(host: String, port: Int, method: String, status: String) {
        val now = System.currentTimeMillis()
        val code = status.toIntOrNull() ?: -1
        val isFailure = status == "fail" || code >= 400
        if (isFailure) lastFailureMs.set(now) else lastSuccessMs.set(now)
        val n = reqCounter.incrementAndGet()
        // Sample ~1 in 10 successful requests; always report failures.
        if (!isFailure && n % 10 != 0) return
        Telemetry.send(
            context, "http_request",
            mapOf(
                "host" to host,
                "port" to "$port",
                "method" to method,
                "status" to status
            )
        )
    }

    /**
     * Watches for a "dead egress" condition: the proxy is up (panel still
     * reachable) but outbound requests keep failing and none succeed. That means
     * the bound cellular network went stale, so we ask the service to restart the
     * proxy and re-bind a fresh cellular network. Idle sessions (no traffic at
     * all) are left alone - only failure-without-success triggers a restart.
     */
    private suspend fun runStaleWatchdog() {
        while (running.get()) {
            delay(60_000)
            val success = lastSuccessMs.get()
            val fail = lastFailureMs.get()
            if (success == 0L && fail == 0L) continue
            val now = System.currentTimeMillis()
            val noSuccessFor = if (success == 0L) Long.MAX_VALUE else now - success
            val recentFail = fail != 0L && (now - fail) < STALE_TIMEOUT_MS
            if (noSuccessFor > STALE_TIMEOUT_MS && recentFail) {
                if (autoRestartGuard.compareAndSet(false, true)) {
                    onLog("Auto-heal: no successful request for ${noSuccessFor / 1000}s but recent failures - restarting proxy")
                    Telemetry.send(context, "proxy_autoheal", mapOf("idle_s" to "${noSuccessFor / 1000}"))
                    onStaleDetected()
                }
            }
        }
    }

    private fun resolve(host: String, net: Network?): InetAddress {
        val now = System.currentTimeMillis()
        val cached = dnsCache[host]
        if (cached != null && cached.second > now) return cached.first
        // Resolve on the same network the upstream socket is bound to. Using the
        // default resolver would query the WiFi Direct interface (no DNS/internet)
        // when the phone is the group owner, so every request would fail to resolve.
        val addr = net?.getAllByName(host)?.firstOrNull()
            ?: InetAddress.getByName(host)
        dnsCache[host] = addr to (now + dnsTtlMs)
        return addr
    }

    private fun acquireUpstream(host: String, port: Int): Socket {
        val key = "$host:$port"
        val pool = connPool[key]
        var reused: Socket? = null
        if (pool != null) {
            synchronized(pool) {
                val now = System.currentTimeMillis()
                pool.removeAll { it.second + poolIdleMs < now || it.first.isClosed }
                val entry = pool.removeLastOrNull()
                if (entry != null) reused = entry.first
            }
        }
        if (reused != null && !reused.isClosed && reused.isConnected) {
            return reused
        }
        val net = pickNet()
        val addr = resolve(host, net)
        val up = Socket()
        try {
            net?.bindSocket(up)
            up.soTimeout = readTimeoutMs
            up.tcpNoDelay = true
            up.connect(InetSocketAddress(addr, port), connectTimeoutMs)
            return up
        } catch (e: Exception) {
            runCatching { up.close() }
            // The cached cellular network may have gone stale (very common while
            // the phone is the WiFi-Direct Group Owner for a long session). Re-pick
            // a live cellular network once instead of failing on a dead binding.
            val fresh = cm.allNetworks.firstOrNull { isValidCellular(it) }
            if (fresh != null && fresh != net) {
                val up2 = Socket()
                try {
                    fresh.bindSocket(up2)
                    up2.soTimeout = readTimeoutMs
                    up2.tcpNoDelay = true
                    up2.connect(InetSocketAddress(resolve(host, fresh), port), connectTimeoutMs)
                    cellularNetwork = fresh
                    lastGoodCellular = fresh
                    clearPool()
                    clearDns()
                    return up2
                } catch (_: Exception) {
                    runCatching { up2.close() }
                }
            }
            throw e
        }
    }

    private fun releaseUpstream(host: String, port: Int, sock: Socket) {
        if (sock.isClosed) return
        val key = "$host:$port"
        val pool = connPool.getOrPut(key) { mutableListOf() }
        synchronized(pool) {
            val now = System.currentTimeMillis()
            pool.removeAll { it.second + poolIdleMs < now || it.first.isClosed }
            if (pool.size < poolMax) {
                pool.add(sock to now)
            } else {
                runCatching { sock.close() }
            }
        }
    }

    private fun clearPool() {
        // A network switch leaves the cached upstream network stale; drop it so
        // egress doesn't serve a dead binding for up to the 3s cache window.
        cachedNet = null
        cachedNetTime = 0L
        for ((_, list) in connPool) {
            synchronized(list) {
                for ((sock, _) in list) runCatching { sock.close() }
                list.clear()
            }
        }
    }

    private fun clearDns() {
        dnsCache.clear()
    }

    private fun writeResponseHeaders(
        output: OutputStream,
        statusLine: String,
        respHeaders: List<String>,
        keepAlive: Boolean
    ) {
        val sb = StringBuilder()
        sb.append(statusLine).append("\r\n")
        for (h in respHeaders) {
            if (h.startsWith("Proxy-", true)) continue
            if (h.startsWith("Connection:", true)) continue
            if (h.startsWith("Proxy-Connection:", true)) continue
            sb.append(h).append("\r\n")
        }
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** Forward a chunked response verbatim (preserving chunk framing) to the client. */
    private fun forwardChunkedResponse(input: StreamReader, output: OutputStream) {
        while (running.get()) {
            val sizeLine = readLine(input) ?: return
            output.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
            output.write(CRLF)
            val size = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: return
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: return
                    output.write(trailer.toByteArray(Charsets.ISO_8859_1))
                    output.write(CRLF)
                    if (trailer.isEmpty()) break
                }
                return
            }
            val body = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(body, read, size - read)
                if (n <= 0) return
                read += n
            }
            output.write(body, 0, size)
            output.write(CRLF)
            output.flush()
            readLine(input) // consume trailing CRLF after chunk
        }
    }

    private fun pumpFixed(input: InputStream, output: OutputStream, length: Long) {
        val buf = ByteArray(32768)
        var remaining = length
        try {
            while (remaining > 0 && running.get()) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                if (n <= 0) break
                output.write(buf, 0, n)
                remaining -= n
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun handleConnect(
        client: Socket,
        input: StreamReader,
        output: BufferedOutputStream,
        target: String
    ) {
        var upstream: Socket? = null
        val hostPort = target.split(":")
        val host = hostPort[0]
        val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
        tunnelCount.incrementAndGet()
        AppState.tcpTunnels.value = tunnelCount.get()
        try {
            val net = pickNet()
            val resolved = resolve(host, net)
            val up = Socket()
            net?.bindSocket(up)
            up.connect(InetSocketAddress(resolved, port), connectTimeoutMs)
            up.tcpNoDelay = true
            up.soTimeout = tunnelIdleTimeoutMs
            upstream = up
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            onLog("CONNECT $host:$port")
            reportRequest(host, port, "CONNECT", "200")
            // CONNECT tunnels are full-duplex: pump both directions concurrently
            // (sequential pumping stalls HTTPS because the client keeps the
            // request stream open while expecting the response to flow back).
            coroutineScope {
                val toServer = launch { pump(input, up.getOutputStream()) }
                val toClient = launch { pump(StreamReader(up.getInputStream()), output) }
                toServer.join()
                toClient.join()
            }
        } catch (e: Exception) {
            onLog("CONNECT fail $target: ${e.message}")
            reportRequest(host, port, "CONNECT", "fail")
            writeSimpleResponse(output, 502, "Bad Gateway - ${e.message}")
        } finally {
            runCatching { upstream?.close() }
            val left = tunnelCount.decrementAndGet()
            AppState.tcpTunnels.value = if (left < 0) 0 else left
        }
    }

    private fun parseAbsoluteUri(target: String, headers: List<String>): Triple<String?, Int, String> {
        val hostHeader = headers.firstOrNull { it.startsWith("Host:", true) }
            ?.substringAfter(':')?.trim()
        if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            val scheme = if (target.startsWith("https://", true)) "https" else "http"
            val rest = target.substringAfter("://")
            val slashIdx = rest.indexOf('/')
            val hostPart = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
            val path = if (slashIdx >= 0) rest.substring(slashIdx) else "/"
            val defaultPort = if (scheme == "https") 443 else 80
            val (h, p) = splitHostPort(hostPart, defaultPort)
            return Triple(h, p, path)
        }
        if (hostHeader != null) {
            val (h, p) = splitHostPort(hostHeader, 80)
            return Triple(h, p, target)
        }
        return Triple(null, 0, target)
    }

    private fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int> {
        val idx = s.lastIndexOf(':')
        return if (idx > 0) {
            s.substring(0, idx) to (s.substring(idx + 1).toIntOrNull() ?: defaultPort)
        } else {
            s to defaultPort
        }
    }

    private fun readLine(reader: StreamReader): String? = reader.readLine()

    private fun readHeaders(reader: StreamReader): List<String>? = reader.readHeaders()

    private fun pumpChunked(input: StreamReader, dst: OutputStream) {
        try {
            while (running.get()) {
                val sizeLine = readLine(input) ?: return
                val size = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: return
                dst.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
                dst.write(CRLF)
                if (size == 0) {
                    // trailers
                    while (true) {
                        val l = readLine(input) ?: return
                        dst.write(l.toByteArray(Charsets.ISO_8859_1))
                        dst.write(CRLF)
                        if (l.isEmpty()) return
                    }
                }
                val body = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(body, read, size - read)
                    if (n <= 0) return
                    read += n
                }
                dst.write(body, 0, size)
                dst.write(CRLF)
                dst.flush()
                readLine(input) // CRLF after chunk
            }
        } catch (_: Exception) {
        }
    }

    private fun writeSimpleResponse(output: OutputStream, code: Int, text: String) {
        runCatching {
            output.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }
    }

    private suspend fun pump(src: InputStream, dst: OutputStream) {
        val buf = PUMP_BUF.get()
        try {
            while (running.get()) {
                val n = src.read(buf)
                if (n <= 0) break
                dst.write(buf, 0, n)
                if (n < buf.size) dst.flush()
            }
        } catch (_: Exception) {
        }
    }

    // ---- Local control panel (served when a browser hits the proxy directly) ----

    private val selfHosts = setOf(goIp.lowercase(), "127.0.0.1", "localhost", "[::1]")

    /**
     * A request is for the local panel when it is NOT a CONNECT tunnel and either:
     * - origin-form (e.g. `GET /`), which only happens when a browser connects
     *   directly to us, or
     * - absolute-form whose authority is one of our own addresses.
     * Normal proxy traffic to other hosts is forwarded as usual.
     */
    private fun isPanelRequest(method: String, target: String, headers: List<String>): Boolean {
        if (method == "CONNECT") return false
        if (target.startsWith("/")) return true
        val authority = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://([^/?#]+)""")
            .find(target)?.groupValues?.get(1)?.substringBefore('@')?.lowercase() ?: return false
        return authority.substringBefore(':') in selfHosts
    }

    private fun servePanel(
        input: StreamReader,
        output: BufferedOutputStream,
        method: String,
        target: String,
        headers: List<String>
    ) {
        if (method == "POST") {
            if (target == "/restart") {
                onRestartRequest()
                writePanelPage(output, restartRequestedHtml())
                return
            }
            val cl = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
            val body = if (cl != null && cl in 1..1_000_000) readExact(input, cl) else ""
            applyPanelForm(body)
            writePanelPage(output, pendingPageHtml())
            return
        }
        if (target == "/api/status") {
            writeStatusJson(output)
            return
        }
        writePanelPage(output, buildPanelHtml())
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
            append("\"clients\":").append(info.clients).append(',')
            append("\"tcpTunnels\":").append(AppState.tcpTunnels.value).append(',')
            append("\"requireApprovalRestart\":").append(cfg.requireApprovalRestart)
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
        PanelApproval.submit(map)
        onLog("Panel change requested - awaiting in-app approval")
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
                <label>Auto-restore WiFi after (minutes, 0 = off)</label>
                <input type="number" name="wifi_autorestore_min" value="${cfg.wifiAutorestoreMin}" min="0">
                <div class="checkbox"><input type="checkbox" name="telemetry_enabled" value="on" $telChecked><span>Telemetry enabled</span></div>
                <div class="checkbox"><input type="checkbox" name="panel_enabled" value="on" $panelChecked><span>Control panel enabled</span></div>
                <button type="submit">Save settings</button>
                <div class="note">Changes apply live. The panel is reachable by anyone on the WiFi Direct network.</div>
            </div>
        </form>
        <div class="note">SOCKS5: <code>${escapeHtml(info.goIp)}:${cfg.port}</code> &nbsp; HTTP: <code>${escapeHtml(info.goIp)}:${cfg.httpPort}</code></div>
        <script>
        async function sacramRefresh(){
          try{
            var r=await fetch('/api/status',{cache:'no-store'});
            var d=await r.json();
            var set=function(id,v){var e=document.getElementById(id);if(e)e.textContent=v;};
            set('v-status',d.status);set('v-running',d.running);set('v-uptime',d.uptime);
            set('v-mode',d.mode);set('v-ssid',d.ssid);set('v-pass',d.passphrase);
            set('v-goip',d.goIp);set('v-clients',d.clients);set('v-tunnels',d.tcpTunnels);
          }catch(e){}
        }
        sacramRefresh();
        setInterval(sacramRefresh,5000);
        </script>
        </body></html>
        """.trimIndent()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    companion object {
        // Reused across pump() calls on the same worker thread, so dozens of
        // concurrent tunnels don't each allocate a fresh 64KB buffer (GC churn).
        private val PUMP_BUF = ThreadLocal.withInitial { ByteArray(65536) }
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}

/**
 * Buffered reader over an [InputStream] that reads lines in bulk instead of
 * one byte per [InputStream.read] call. Header/status/chunk-size lines used to
 * be parsed one byte at a time (20-40+ read calls per request) which murdered
 * throughput under many small requests. This drains 8KB chunks and scans for
 * the line terminator in-memory, and keeps the line buffer + bulk reads
 * consistent so callers can mix [readLine] with bulk [read] on the same stream.
 */
private class StreamReader(private val src: InputStream) : InputStream() {
    private val buf = ByteArray(8192)
    private var pos = 0
    private var end = 0
    private var lineBuf = ByteArray(256)

    override fun read(): Int {
        if (pos >= end && !fill()) return -1
        return buf[pos++].toInt() and 0xff
    }

    override fun read(out: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var copied = 0
        while (copied < len) {
            if (pos >= end) {
                if (!fill()) break
            }
            val avail = minOf(len - copied, end - pos)
            System.arraycopy(buf, pos, out, off + copied, avail)
            pos += avail
            copied += avail
        }
        return if (copied == 0) -1 else copied
    }

    fun readLine(): String? {
        var len = 0
        while (true) {
            if (pos >= end && !fill()) return null
            while (pos < end) {
                val b = buf[pos++]
                if (b == '\n'.toByte()) {
                    return String(lineBuf, 0, len, Charsets.ISO_8859_1)
                }
                if (b != '\r'.toByte()) {
                    if (len >= lineBuf.size) growLine(len)
                    lineBuf[len++] = b
                }
            }
        }
    }

    fun readHeaders(): List<String>? {
        val headers = mutableListOf<String>()
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) return headers
            headers.add(line)
        }
    }

    private fun growLine(len: Int) {
        val newBuf = ByteArray(maxOf(lineBuf.size * 2, len + 1))
        System.arraycopy(lineBuf, 0, newBuf, 0, len)
        lineBuf = newBuf
    }

    private fun fill(): Boolean {
        end = src.read(buf, 0, buf.size)
        pos = 0
        return end > 0
    }
}