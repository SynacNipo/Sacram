package com.sacram.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SOCKS5 server (RFC 1928) with TCP CONNECT and UDP ASSOCIATE.
 */
class Socks5Server(
    private val port: Int,
    private val advertiseIp: String,
    private val onLog: (String) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var tcpJob: Job? = null
    private var udpJob: Job? = null

    // key "clientIp:port" -> forwarding socket for UDP sessions
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    private class UdpSession(val socket: DatagramSocket) {
        var lastActivity = System.currentTimeMillis()
    }

    fun start() {
        running.set(true)
        tcpJob = scope.launch { runTcpServer() }
        udpJob = scope.launch { runUdpServer() }
        onLog("SOCKS5 listening tcp/udp on port $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        udpSessions.values.forEach { runCatching { it.socket.close() } }
        udpSessions.clear()
        tcpJob?.cancel()
        udpJob?.cancel()
        scope.cancel()
    }

    private suspend fun runTcpServer() {
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
                scope.launch { handleTcpClient(client) }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("TCP server error: $e")
        }
    }

    private suspend fun runUdpServer() {
        try {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress("0.0.0.0", port))
            udpSocket = sock
            val buf = ByteArray(65535)
            while (running.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (e: Exception) {
                    break
                }
                scope.launch { handleUdpPacket(sock, pkt, buf.copyOf(pkt.length)) }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("UDP server error: $e")
        }
    }

    private suspend fun handleTcpClient(client: Socket) {
        try {
            client.soTimeout = 300000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            // greeting
            val version = input.readUnsignedByte()
            if (version != 0x05) {
                client.close(); return
            }
            val nmethods = input.readUnsignedByte()
            if (nmethods <= 0) {
                client.close(); return
            }
            input.skipBytes(nmethods)
            output.writeByte(0x05); output.writeByte(0x00); output.flush()

            // request header
            val ver2 = input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            if (ver2 != 0x05) {
                client.close(); return
            }
            val (target, targetPort) = readTarget(input)

            when (cmd) {
                0x01 -> handleConnect(client, input, output, target, targetPort)
                0x03 -> handleUdpAssociate(client, input, output)
                else -> {
                    replyError(output, 0x07); client.close()
                }
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    private suspend fun handleConnect(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        target: String,
        targetPort: Int
    ) {
        var upstream: Socket? = null
        try {
            val resolved = withContext(Dispatchers.IO) { InetAddress.getByName(target) }
            val up = Socket()
            up.connect(InetSocketAddress(resolved, targetPort), 15000)
            up.tcpNoDelay = true
            upstream = up
            replySuccess(output)
            onLog("TCP $target:$targetPort")
            pump(input, up.getOutputStream())
            pump(up.getInputStream(), output)
        } catch (e: Exception) {
            onLog("TCP fail $target:$targetPort: ${e.message}")
            runCatching { replyError(output, 0x05) }
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private suspend fun handleUdpAssociate(client: Socket, input: DataInputStream, output: DataOutputStream) {
        // Send the UDP relay address: advertiseIp:port
        val relayAddr = InetAddress.getByName(advertiseIp)
        output.writeByte(0x05); output.writeByte(0x00); output.writeByte(0x00)
        output.writeByte(0x01) // IPv4
        output.write(relayAddr.address)
        output.writeShort(port)
        output.flush()
        onLog("UDP associate from ${client.inetAddress.hostAddress}:${client.port}")
        // keep the TCP connection open to signal end-of-session
        try {
            val b = ByteArray(1)
            input.read(b) // blocks until client closes
        } catch (_: Exception) {
        }
        runCatching { client.close() }
    }

    private suspend fun handleUdpPacket(sock: DatagramSocket, pkt: DatagramPacket, data: ByteArray) {
        try {
            if (data.size < 10) return
            val rsv0 = data[0]; val rsv1 = data[1]; val frag = data[2].toInt() and 0xff
            if (rsv0 != 0.toByte() || rsv1 != 0.toByte()) return
            if (frag != 0) return // fragmentation not supported, drop
            val atyp = data[3].toInt() and 0xff
            var idx = 4
            val dstHost: String
            when (atyp) {
                0x01 -> {
                    if (data.size < idx + 4 + 2) return
                    dstHost = "${data[idx].toInt() and 0xff}.${data[idx + 1].toInt() and 0xff}." +
                        "${data[idx + 2].toInt() and 0xff}.${data[idx + 3].toInt() and 0xff}"
                    idx += 4
                }
                0x03 -> {
                    val len = data[idx].toInt() and 0xff
                    idx += 1
                    if (data.size < idx + len + 2) return
                    dstHost = String(data, idx, len)
                    idx += len
                }
                0x04 -> return // IPv6 targets unsupported
                else -> return
            }
            val dstPort = ((data[idx].toInt() and 0xff) shl 8) or (data[idx + 1].toInt() and 0xff)
            val payload = data.copyOfRange(idx + 2, data.size)

            val clientKey = "${pkt.address.hostAddress}:${pkt.port}"
            val session = udpSessions[clientKey]
            if (session == null) {
                val fwd = DatagramSocket()
                fwd.soTimeout = 1000
                val newSession = UdpSession(fwd)
                udpSessions[clientKey] = newSession
                withContext(Dispatchers.IO) { runUdpReplyLoop(newSession, sock, pkt.address, pkt.port) }
            }
            val sessionNow = udpSessions[clientKey] ?: return
            sessionNow.lastActivity = System.currentTimeMillis()
            val dstAddr = InetAddress.getByName(dstHost)
            sessionNow.socket.send(DatagramPacket(payload, payload.size, dstAddr, dstPort))
        } catch (_: Exception) {
        }
    }

    private suspend fun runUdpReplyLoop(
        session: UdpSession,
        relaySocket: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int
    ) {
        try {
            val buf = ByteArray(65535)
            while (running.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    session.socket.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() - session.lastActivity > 300000) break
                    continue
                } catch (e: Exception) {
                    break
                }
                session.lastActivity = System.currentTimeMillis()
                val src = pkt.address
                val srcPort = pkt.port
                val data = buf.copyOf(pkt.length)
                val header = ByteArray(10)
                header[3] = 0x01 // IPv4
                val ipBytes = src.address
                if (ipBytes.size != 4) continue
                System.arraycopy(ipBytes, 0, header, 4, 4)
                header[8] = ((srcPort shr 8) and 0xff).toByte()
                header[9] = (srcPort and 0xff).toByte()
                val response = ByteArray(header.size + data.size)
                System.arraycopy(header, 0, response, 0, header.size)
                System.arraycopy(data, 0, response, header.size, data.size)
                relaySocket.send(DatagramPacket(response, response.size, clientAddr, clientPort))
            }
        } finally {
            runCatching { session.socket.close() }
            udpSessions.entries.removeIf { it.value === session }
        }
    }

    private fun readTarget(input: DataInputStream): Pair<String, Int> {
        val atyp = input.readUnsignedByte()
        return when (atyp) {
            0x01 -> {
                val b = ByteArray(4); input.readFully(b)
                "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}.${b[3].toInt() and 0xff}" to input.readUnsignedShort()
            }
            0x03 -> {
                val len = input.readUnsignedByte()
                val b = ByteArray(len); input.readFully(b)
                String(b) to input.readUnsignedShort()
            }
            0x04 -> {
                val b = ByteArray(16); input.readFully(b)
                InetAddress.getByAddress(b).hostAddress to input.readUnsignedShort()
            }
            else -> throw IllegalStateException("bad atyp")
        }
    }

    private fun replySuccess(output: DataOutputStream) {
        output.writeByte(0x05); output.writeByte(0x00); output.writeByte(0x00)
        output.writeByte(0x01); output.write(byteArrayOf(0, 0, 0, 0)); output.writeShort(0)
        output.flush()
    }

    private fun replyError(output: DataOutputStream, code: Int) {
        runCatching {
            output.writeByte(0x05); output.writeByte(code); output.writeByte(0x00)
            output.writeByte(0x01); output.write(byteArrayOf(0, 0, 0, 0)); output.writeShort(0)
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
