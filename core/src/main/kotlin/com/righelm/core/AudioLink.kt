// The network side of a RigHelm `AUDIO` session: receives the radio's audio
// as 20 ms PCM frames over UDP and sends microphone frames back. The TCP
// connection only carries a heartbeat; the session lives while it does.

package com.righelm.core

import java.net.InetAddress
import kotlin.math.log10
import kotlin.math.sqrt

data class AudioLinkStats(
    val connected: Boolean = false,
    val server: String = "",
    val rate: Int = RemoteProtocol.AUDIO_RATE,
    val packets: Long = 0,
    val lost: Long = 0,
    val rxLevelDb: Float = -100f,
    val micPackets: Long = 0,
    val error: String? = null,
)

class AudioLink(
    val host: String,
    val port: Int = RemoteProtocol.CONTROL_PORT,
    private val password: String,
) {
    /**
     * Radio audio, called on the network thread: samples and the number of
     * frames lost just before them.
     */
    var onAudio: ((ShortArray, Int) -> Unit)? = null
    /** Runs callbacks on the caller's thread of choice (the app's main thread). */
    var callback: (Runnable) -> Unit = { it.run() }
    /** About four times a second. */
    var onStats: ((AudioLinkStats) -> Unit)? = null

    private val lock = Any()
    private var stopRequested = false
    private var started = false
    private var udp: UdpSocket? = null
    private var session = 0L
    private var serverIP: InetAddress? = null
    private var serverPort = 0
    private var txSeq = 0L
    private var stats = AudioLinkStats()

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        Thread(::run, "righelm-audio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }.start()
    }

    fun stop() {
        synchronized(lock) { stopRequested = true }
    }

    val currentStats: AudioLinkStats get() = synchronized(lock) { stats }

    /**
     * Send one microphone frame to the radio. The caller decides when
     * (only while this operator transmits in a phone mode); the server
     * checks again before playing it into the radio.
     */
    fun sendMic(samples: ShortArray) {
        val sock: UdpSocket
        val ip: InetAddress
        val packet: ByteArray
        val port: Int
        synchronized(lock) {
            sock = udp ?: return
            ip = serverIP ?: return
            if (session == 0L) return
            txSeq = (txSeq + 1) and 0xFFFF_FFFFL
            packet = AudioPacket.encode(AudioPacket.TX, session, txSeq, samples)
            port = serverPort
            stats = stats.copy(micPackets = stats.micPackets + 1)
        }
        sock.send(packet, ip, port)
    }

    private val shouldStop: Boolean get() = synchronized(lock) { stopRequested }

    private fun run() {
        while (!shouldStop) {
            try {
                runSession()
            } catch (e: Exception) {
                synchronized(lock) { stats = stats.copy(connected = false, error = e.toString()) }
                publish()
            }
            val deadline = System.currentTimeMillis() + 3000
            while (!shouldStop && System.currentTimeMillis() < deadline) Thread.sleep(100)
        }
        synchronized(lock) {
            udp = null
            session = 0
            stats = stats.copy(connected = false)
        }
        publish()
    }

    private fun runSession() {
        val hs = Handshake.open(host, port, password, "AUDIO")
        val control = hs.socket
        var sock: UdpSocket? = null
        try {
            sock = UdpSocket()
            val ip = resolveIPv4(hs.peerIP)
            synchronized(lock) {
                udp = sock
                session = hs.session
                serverIP = ip
                serverPort = hs.udpPort
                stats = AudioLinkStats(connected = true, server = hs.serverName, rate = hs.rate)
            }
            publish()
            val hello = AudioPacket.encode(AudioPacket.HELLO, hs.session, 0, ShortArray(0))
            var lastHello = 0L
            var lastPublish = System.currentTimeMillis()
            var lastSeq: Long? = null
            while (!shouldStop) {
                if (System.currentTimeMillis() - lastHello > 1000) {
                    sock.send(hello, ip, hs.udpPort)
                    control.write("\n")
                    // The server never writes on this connection; anything read
                    // here means it closed (read throws) or misbehaves.
                    control.read(0.0)
                    lastHello = System.currentTimeMillis()
                }
                if (System.currentTimeMillis() - lastPublish > 250) {
                    lastPublish = System.currentTimeMillis()
                    publish()
                }
                val d = sock.receive(0.1) ?: continue
                if (d.from != ip) continue
                val pkt = AudioPacket.decode(d.bytes) ?: continue
                if (pkt.kind != AudioPacket.RX || pkt.session != hs.session) continue
                var lost = 0
                val l = lastSeq
                if (l != null) {
                    // A late duplicate or reordered packet: too late to play.
                    if (pkt.seq <= l && ((l - pkt.seq) and 0xFFFF_FFFFL) < 1000) continue
                    val gap = (pkt.seq - l - 1) and 0xFFFF_FFFFL
                    lost = if (gap > 1000) 0 else gap.toInt()
                }
                lastSeq = pkt.seq
                val level = levelDbfs(pkt.samples)
                synchronized(lock) {
                    stats = stats.copy(packets = stats.packets + 1, lost = stats.lost + lost, rxLevelDb = level)
                }
                onAudio?.invoke(pkt.samples, lost)
            }
        } finally {
            synchronized(lock) {
                udp = null
                session = 0
            }
            sock?.close()
            control.close()
        }
    }

    private fun publish() {
        val s = currentStats
        callback(Runnable { onStats?.invoke(s) })
    }
}

/** RMS level of a frame in dBFS (-100 for silence). */
fun levelDbfs(frame: ShortArray): Float {
    if (frame.isEmpty()) return -100f
    var sum = 0.0
    for (s in frame) sum += s.toDouble() * s.toDouble()
    val rms = sqrt(sum / frame.size) / 32768
    return if (rms <= 1e-5) -100f else (20 * log10(rms)).toFloat()
}
