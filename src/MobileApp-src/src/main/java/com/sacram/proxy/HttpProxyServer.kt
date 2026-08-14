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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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

/**
 * HTTP proxy (RFC 7230 style): handles plain HTTP requests with absolute-form
 * URIs and CONNECT tunnels (HTTPS). No UDP - this is TCP-only by design.
 */
class HttpProxyServer(
    private val port: Int,
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var tcpJob: Job? = null
    private var cellularNetwork: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val dnsCache = ConcurrentHashMap<String, Pair<InetAddress, Long>>()
    private val connPool = ConcurrentHashMap<String, MutableList<Pair<Socket, Long>>>()
    private val dnsTtlMs = 60_000L
    private val poolMax = 4
    private val poolIdleMs = 60_000L
    private val connectTimeoutMs = 10_000
    private val readTimeoutMs = 20_000

    private fun bindToCellular() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                clearPool()
                onLog("Bound to cellular network: $network")
            }
            override fun onLost(network: Network) {
                if (cellularNetwork == network) cellularNetwork = null
                clearPool()
            }
        }
        netCallback = cb
        cm.requestNetwork(request, cb)
        scope.launch {
            delay(3000)
            if (cellularNetwork == null) {
                onLog("WARNING: no cellular network available - outbound sockets use the default route")
            }
        }
    }

    fun start() {
        running.set(true)
        bindToCellular()
        tcpJob = scope.launch { runServer() }
        onLog("HTTP proxy listening on port $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
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
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        try {
            client.soTimeout = 300000
            while (running.get()) {
                val requestLine = readLine(input) ?: break
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(" ")
                if (parts.size < 3) break
                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]
                val headers = readHeaders(input) ?: break

                if (method == "CONNECT") {
                    handleConnect(client, input, output, target)
                    break
                }

                val (host, port, path) = parseAbsoluteUri(target, headers)
                if (host == null) {
                    writeSimpleResponse(output, 400, "Bad Request - absolute URI required")
                    break
                }
                val keepAlive = headers.any { it.startsWith("Connection:", true) && it.contains("keep-alive", true) }
                forwardPlain(input, output, method, host, port, path, headers)
                if (!keepAlive) break
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun forwardPlain(
        input: BufferedInputStream,
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

            val upIn = upstream.getInputStream()
            val statusLine = readLine(upIn) ?: throw IOException("no response from upstream")
            val respHeaders = readHeaders(upIn) ?: throw IOException("no response headers")

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
            writeSimpleResponse(output, 502, "Bad Gateway - ${e.message}")
        } finally {
            runCatching { upstream?.close() }
        }
    }

    private fun resolve(host: String): InetAddress {
        val now = System.currentTimeMillis()
        val cached = dnsCache[host]
        if (cached != null && cached.second > now) return cached.first
        // Resolve on the same network the upstream socket is bound to. Using the
        // default resolver would query the WiFi Direct interface (no DNS/internet)
        // when the phone is the group owner, so every request would fail to resolve.
        val addr = cellularNetwork?.getAllByName(host)?.firstOrNull()
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
        if (reused != null && !reused.isClosed) {
            return reused
        }
        val addr = resolve(host)
        val up = Socket()
        cellularNetwork?.bindSocket(up)
        up.soTimeout = readTimeoutMs
        up.tcpNoDelay = true
        up.connect(InetSocketAddress(addr, port), connectTimeoutMs)
        return up
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
        for ((_, list) in connPool) {
            synchronized(list) {
                for ((sock, _) in list) runCatching { sock.close() }
                list.clear()
            }
        }
    }

    private fun writeResponseHeaders(
        output: OutputStream,
        statusLine: String,
        respHeaders: List<String>,
        keepAlive: Boolean
    ) {
        output.write("$statusLine\r\n".toByteArray())
        for (h in respHeaders) {
            if (h.startsWith("Proxy-", true)) continue
            if (h.startsWith("Connection:", true)) continue
            if (h.startsWith("Proxy-Connection:", true)) continue
            output.write("$h\r\n".toByteArray())
        }
        output.write("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n".toByteArray())
        output.flush()
    }

    /** Forward a chunked response verbatim (preserving chunk framing) to the client. */
    private fun forwardChunkedResponse(input: InputStream, output: OutputStream) {
        while (running.get()) {
            val sizeLine = readLine(input) ?: return
            output.write("$sizeLine\r\n".toByteArray())
            val size = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: return
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: return
                    output.write("$trailer\r\n".toByteArray())
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
            output.write("\r\n".toByteArray())
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
                output.flush()
                remaining -= n
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun handleConnect(
        client: Socket,
        input: InputStream,
        output: BufferedOutputStream,
        target: String
    ) {
        var upstream: Socket? = null
        try {
            val hostPort = target.split(":")
            val host = hostPort[0]
            val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
            val resolved = resolve(host)
            val up = Socket()
            cellularNetwork?.bindSocket(up)
            up.connect(InetSocketAddress(resolved, port), connectTimeoutMs)
            up.tcpNoDelay = true
            upstream = up
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            onLog("CONNECT $host:$port")
            // CONNECT tunnels are full-duplex: pump both directions concurrently
            // (sequential pumping stalls HTTPS because the client keeps the
            // request stream open while expecting the response to flow back).
            coroutineScope {
                val toServer = launch { pump(input, up.getOutputStream()) }
                val toClient = launch { pump(up.getInputStream(), output) }
                toServer.join()
                toClient.join()
            }
        } catch (e: Exception) {
            onLog("CONNECT fail $target: ${e.message}")
            writeSimpleResponse(output, 502, "Bad Gateway - ${e.message}")
        } finally {
            runCatching { upstream?.close() }
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

    private fun readLine(input: InputStream): String? {
        val bos = ByteArrayOutputStream()
        var b: Int
        while (true) {
            b = try {
                input.read()
            } catch (e: Exception) {
                return null
            }
            if (b < 0) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) bos.write(b)
        }
        return bos.toString("ISO-8859-1")
    }

    private fun readHeaders(input: InputStream): List<String>? {
        val headers = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) return headers
            headers.add(line)
        }
    }

    private fun pumpChunked(input: InputStream, dst: OutputStream) {
        try {
            while (running.get()) {
                val sizeLine = readLine(input) ?: return
                val size = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: return
                dst.write("$sizeLine\r\n".toByteArray())
                if (size == 0) {
                    // trailers
                    while (true) {
                        val l = readLine(input) ?: return
                        dst.write("$l\r\n".toByteArray())
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
                dst.write("\r\n".toByteArray())
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
        val buf = ByteArray(65536)
        try {
            while (running.get()) {
                val n = src.read(buf)
                if (n <= 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
        }
    }
}