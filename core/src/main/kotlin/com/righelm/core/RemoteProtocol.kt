// RigHelm's LAN remote protocol, client side (see righelm's
// crates/ftdx3000-rig/src/remote.rs for the server).
//
// * Discovery (UDP 4534): servers broadcast a beacon every 2 s and answer
//   `FTDX3000-DISCOVER` probes.
// * Sessions (TCP 4533): the server greets with a nonce; the client proves
//   the password with HMAC-SHA256(password, "ftdx3000:<nonce>:<mode>").
//   - `CAT` sessions carry plain FTDX3000 CAT.
//   - `AUDIO` sessions keep TCP as a heartbeat and exchange 20 ms PCM
//     frames over UDP on the port the server names.

package com.righelm.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RemoteProtocol {
    const val CONTROL_PORT = 4533
    const val DISCOVERY_PORT = 4534
    const val AUDIO_RATE = 24_000
    const val GREETING = "FTDX3000-REMOTE"
    val PROBE: ByteArray = "FTDX3000-DISCOVER".toByteArray(Charsets.US_ASCII)

    /** Samples in one 20 ms frame at [rate]. */
    fun frameLength(rate: Int): Int = rate / 50

    /** Proof of the password for one session: hex HMAC-SHA256. */
    fun proof(password: String, nonce: String, mode: String): String {
        // An open server ignores the proof; an HMAC key can't be empty.
        if (password.isEmpty()) return "-"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("ftdx3000:$nonce:$mode".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".fmt(it) }
    }
}

/** A RigHelm server found on the LAN. */
data class ServerBeacon(
    /** The server's random per-process id (hex). */
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val audio: Boolean,
    /** A password is set (and so transmit can be allowed). */
    val auth: Boolean,
    /** The server plays clients' microphones into the radio. */
    val mic: Boolean,
    /** The server's own radio link: "connected", "not-answering", "disconnected". */
    val radio: String,
    val lastSeen: Long = System.currentTimeMillis(),
) {
    companion object {
        /** `FTDX3000-REMOTE v1;id=...;name=...;port=4533;audio=1;auth=1;mic=1;radio=connected` */
        fun parse(text: String, from: String): ServerBeacon? {
            if (!text.startsWith(RemoteProtocol.GREETING)) return null
            val parts = text.substring(RemoteProtocol.GREETING.length).trim().split(";").toMutableList()
            if (parts.isEmpty()) return null
            parts.removeAt(0) // version
            val f = mutableMapOf<String, String>()
            for (p in parts) {
                val eq = p.indexOf('=')
                if (eq >= 0) f[p.substring(0, eq)] = p.substring(eq + 1)
            }
            val id = f["id"] ?: return null
            if (id.toULongOrNull(16) == null) return null
            val port = f["port"]?.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
            return ServerBeacon(
                id = id, name = f["name"] ?: "?", host = from, port = port,
                audio = f["audio"] == "1", auth = f["auth"] == "1", mic = f["mic"] == "1", radio = f["radio"] ?: "?",
            )
        }
    }
}

/** UDP audio packet: "FX" ver=1 kind session:u64le seq:u32le, then s16le samples. */
object AudioPacket {
    const val HELLO: Byte = 1
    const val RX: Byte = 2
    const val TX: Byte = 3
    const val HEADER_SIZE = 16

    class Decoded(val kind: Byte, val session: Long, val seq: Long, val samples: ShortArray)

    fun encode(kind: Byte, session: Long, seq: Long, samples: ShortArray): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x46).put(0x58).put(1).put(kind) // "FX"
        b.putLong(session)
        b.putInt(seq.toInt())
        for (s in samples) b.putShort(s)
        return b.array()
    }

    fun decode(p: ByteArray, length: Int = p.size): Decoded? {
        if (length < HEADER_SIZE || p[0] != 0x46.toByte() || p[1] != 0x58.toByte() || p[2] != 1.toByte()) return null
        val b = ByteBuffer.wrap(p, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val session = b.getLong(4)
        val seq = b.getInt(12).toLong() and 0xFFFF_FFFFL
        val n = (length - HEADER_SIZE) / 2
        val samples = ShortArray(n) { b.getShort(HEADER_SIZE + 2 * it) }
        return Decoded(p[3], session, seq, samples)
    }
}

sealed class RemoteError(message: String) : Exception(message) {
    class Denied(val why: String) : RemoteError(why)
    class NotRigHelm : RemoteError("that address is not a RigHelm server")
    class Unexpected(line: String) : RemoteError("unexpected reply: $line")

    override fun toString(): String = message ?: "error"

    val isWrongPassword: Boolean get() = this is Denied && why.contains("password")
}

/** An authenticated session, positioned just after the server's `OK`. */
class Handshake(
    val socket: TcpConn,
    val serverName: String,
    val session: Long,
    val udpPort: Int,
    val rate: Int,
    val peerIP: String,
) {
    companion object {
        fun open(host: String, port: Int, password: String, mode: String): Handshake {
            val sock = TcpConn.connect(host, port, 4.0)
            try {
                val greet = sock.readLine(5.0).split(" ").filter { it.isNotEmpty() }
                if (greet.size < 3 || greet[0] != RemoteProtocol.GREETING) throw RemoteError.NotRigHelm()
                val nonce = greet[2]
                val name = greet.drop(3).joinToString(" ")
                sock.write("HELLO $mode ${RemoteProtocol.proof(password, nonce, mode)}\n")
                val line = sock.readLine(5.0)
                val reply = line.split(" ").filter { it.isNotEmpty() }
                when (reply.firstOrNull()) {
                    "OK" -> {
                        val session = reply.getOrNull(1)?.toULongOrNull(16)?.toLong() ?: 0L
                        val udp = reply.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..65535 } ?: port
                        val rate = reply.getOrNull(3)?.toIntOrNull() ?: RemoteProtocol.AUDIO_RATE
                        if (rate !in 8_000..48_000) throw RemoteError.Unexpected("audio rate $rate")
                        return Handshake(sock, name, session, udp, rate, sock.peerIP)
                    }
                    "DENIED" -> throw RemoteError.Denied(line.removePrefix("DENIED").trim())
                    else -> throw RemoteError.Unexpected(line)
                }
            } catch (e: Exception) {
                sock.close()
                throw e
            }
        }
    }
}
