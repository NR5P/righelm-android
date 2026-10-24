// Small blocking socket wrappers, used from the client's own threads.

package com.righelm.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

class SocketError(message: String) : IOException(message) {
    override fun toString(): String = message ?: "socket error"
}

/** 0xC0A80A05 -> "192.168.10.5" */
fun ipString(ip: Long): String =
    "${ip shr 24 and 0xff}.${ip shr 16 and 0xff}.${ip shr 8 and 0xff}.${ip and 0xff}"

/** Host-order IPv4 address of an [Inet4Address], as an unsigned value. */
fun ipValue(a: InetAddress): Long {
    val b = a.address
    return (b[0].toLong() and 0xff shl 24) or (b[1].toLong() and 0xff shl 16) or
        (b[2].toLong() and 0xff shl 8) or (b[3].toLong() and 0xff)
}

fun ipAddress(ip: Long): InetAddress =
    InetAddress.getByAddress(byteArrayOf((ip shr 24).toByte(), (ip shr 16).toByte(), (ip shr 8).toByte(), ip.toByte()))

/** Resolve [host] to an IPv4 address. */
fun resolveIPv4(host: String): InetAddress {
    val all = try {
        InetAddress.getAllByName(host)
    } catch (e: Exception) {
        throw SocketError("cannot find $host")
    }
    return all.firstOrNull { it is Inet4Address } ?: throw SocketError("cannot find $host: no IPv4 address")
}

// MARK: - TCP

class TcpConn private constructor(private val socket: Socket, val peerIP: String) {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val lock = Any()
    @Volatile private var closed = false

    /** Received but not yet consumed (after a [readLine]). */
    private var pending = ByteArray(0)

    companion object {
        fun connect(host: String, port: Int, timeoutSeconds: Double): TcpConn {
            val ip = resolveIPv4(host)
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, port), (timeoutSeconds * 1000).toInt())
            } catch (e: SocketTimeoutException) {
                s.close()
                throw SocketError("no answer from $host:$port")
            } catch (e: IOException) {
                s.close()
                // Android's own messages repeat both addresses and the errno.
                val why = when (e) {
                    is java.net.ConnectException -> if (e.message?.contains("ETIMEDOUT") == true) "no answer" else "connection refused"
                    is java.net.NoRouteToHostException -> "no route to it"
                    else -> e.message ?: e.javaClass.simpleName
                }
                throw SocketError("cannot connect to $host:$port: $why")
            }
            return TcpConn(s, ip.hostAddress ?: host)
        }
    }

    /**
     * Bytes that arrive within [timeoutSeconds]; empty if none did. Throws
     * when the connection is closed or fails.
     */
    fun read(timeoutSeconds: Double): ByteArray {
        if (pending.isNotEmpty()) {
            val p = pending
            pending = ByteArray(0)
            return p
        }
        return readRaw(timeoutSeconds)
    }

    private fun readRaw(timeoutSeconds: Double): ByteArray {
        // A zero time-out would mean "wait forever" to Java.
        socket.soTimeout = maxOf(1, (timeoutSeconds * 1000).toInt())
        val buf = ByteArray(8192)
        val n = try {
            input.read(buf)
        } catch (e: SocketTimeoutException) {
            return ByteArray(0)
        } catch (e: IOException) {
            throw SocketError("receive: ${e.message ?: e.javaClass.simpleName}")
        }
        if (n < 0) throw SocketError("the server closed the connection")
        return buf.copyOf(n)
    }

    /** One `\n`-terminated line, without the terminator. */
    fun readLine(timeoutSeconds: Double): String {
        val deadline = System.nanoTime() + (timeoutSeconds * 1e9).toLong()
        while (true) {
            val i = pending.indexOf(10)
            if (i >= 0) {
                val line = String(pending, 0, i, Charsets.UTF_8)
                pending = pending.copyOfRange(i + 1, pending.size)
                return line.trim()
            }
            if (pending.size >= 1024) throw RemoteError.NotRigHelm()
            val left = (deadline - System.nanoTime()) / 1e9
            if (left <= 0) throw SocketError("the server did not answer")
            pending += readRaw(left)
        }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun write(bytes: ByteArray) {
        try {
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            throw SocketError("send: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        try { socket.shutdownInput() } catch (_: Exception) {}
        try { socket.shutdownOutput() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }
}

// MARK: - UDP

class Datagram(val bytes: ByteArray, val from: InetAddress, val port: Int)

class UdpSocket(port: Int = 0, reusePort: Boolean = false, broadcast: Boolean = false) {
    private val socket: DatagramSocket
    val localPort: Int

    init {
        val s = DatagramSocket(null)
        try {
            // Lets several programs on one machine share a discovery port.
            if (reusePort) s.reuseAddress = true
            if (broadcast) s.broadcast = true
            // Room for Wi-Fi delivering audio in clumps.
            s.receiveBufferSize = 256 * 1024
            s.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            s.close()
            throw SocketError("bind UDP port $port: ${e.message ?: e.javaClass.simpleName}")
        }
        socket = s
        localPort = s.localPort
    }

    fun send(bytes: ByteArray, to: InetAddress, port: Int): Boolean = try {
        socket.send(DatagramPacket(bytes, bytes.size, to, port))
        true
    } catch (e: Exception) {
        false
    }

    fun send(bytes: ByteArray, to: Long, port: Int): Boolean = send(bytes, ipAddress(to), port)

    /** One datagram arriving within [timeoutSeconds], with the sender's address. */
    fun receive(timeoutSeconds: Double): Datagram? {
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        return try {
            socket.soTimeout = maxOf(1, (timeoutSeconds * 1000).toInt())
            socket.receive(p)
            Datagram(buf.copyOf(p.length), p.address, p.port)
        } catch (e: Exception) {
            null
        }
    }

    fun close() = socket.close()
}

// MARK: - Interfaces

data class NetInterface(
    val name: String,
    /** Host-order IPv4 address, netmask and broadcast address. */
    val address: Long,
    val netmask: Long,
    val broadcast: Long,
)

/**
 * This device's LAN interfaces: up, IPv4, broadcast-capable (so Wi-Fi and
 * Ethernet, not cellular or VPN tunnels).
 */
fun lanInterfaces(): List<NetInterface> {
    val out = mutableListOf<NetInterface>()
    val all = try {
        NetworkInterface.getNetworkInterfaces()?.toList() ?: return out
    } catch (e: Exception) {
        return out
    }
    for (i in all) {
        try {
            if (!i.isUp || i.isLoopback || i.isPointToPoint) continue
        } catch (e: Exception) {
            continue
        }
        for (a in i.interfaceAddresses) {
            val addr = a.address as? Inet4Address ?: continue
            if (a.broadcast == null) continue
            val ip = ipValue(addr)
            if (ip shr 16 == 0xA9FEL) continue // link-local 169.254/16
            val prefix = a.networkPrefixLength.toInt().coerceIn(0, 32)
            val mask = if (prefix == 0) 0L else (0xFFFF_FFFFL shl (32 - prefix)) and 0xFFFF_FFFFL
            val iface = NetInterface(i.name, ip, mask, (ip or mask.inv()) and 0xFFFF_FFFFL)
            if (iface !in out) out += iface
        }
    }
    return out
}

internal fun ByteArray.indexOf(b: Int): Int {
    for (i in indices) if (this[i].toInt() == b) return i
    return -1
}
