// rhcheck: exercise the core against a real RigHelm server from the
// command line. Read-only: transmit stays locked and no Set is ever sent.
//
//     ./gradlew :core:rhcheck --args="discover"
//     RIGHELM_PASSWORD=... ./gradlew :core:rhcheck --args="listen HOST[:PORT] [SECONDS]"

package com.righelm.rhcheck

import com.righelm.core.AudioLink
import com.righelm.core.CatClient
import com.righelm.core.Discovery
import com.righelm.core.Frequency
import com.righelm.core.fmt
import com.righelm.core.MeterCal
import com.righelm.core.RemoteProtocol
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val queue = Executors.newSingleThreadExecutor()

private fun discover() {
    val d = Discovery()
    d.callback = { queue.execute(it) }
    d.start()
    Thread.sleep(5000)
    for (s in d.servers) {
        println("${s.name}  ${s.host}:${s.port}  radio=${s.radio} audio=${s.audio} password=${s.auth} mic=${s.mic}")
    }
    if (d.servers.isEmpty()) println("no RigHelm servers found")
    d.stop()
}

private fun listen(target: String, seconds: Double) {
    val password = System.getenv("RIGHELM_PASSWORD") ?: ""
    val parts = target.split(":")
    val host = parts[0]
    val port = parts.getOrNull(1)?.toIntOrNull() ?: RemoteProtocol.CONTROL_PORT
    val cat = CatClient(host, port, password)
    cat.callback = { queue.execute(it) }
    cat.isTransmitAllowed = { false }
    cat.onStatus = { println("cat: $it") }
    cat.onNote = { println("note: $it") }
    val audio = AudioLink(host, port, password)
    audio.callback = { queue.execute(it) }
    var samples = 0L
    audio.onAudio = { s, _ -> queue.execute { samples += s.size } }
    cat.start()
    audio.start()
    val t0 = System.currentTimeMillis()
    while (System.currentTimeMillis() - t0 < seconds * 1000) {
        Thread.sleep(1000)
        val s = cat.state
        val a = audio.currentStats
        println(
            "%s %s  B %s  S=%s  AF=%d PWR=%dW  tx=%d  width=%s  MICSEL ssb=%s | audio %s pkts=%d lost=%d level=%.0f dBFS".fmt(
                Frequency.format(s.freqA), s.modeA?.label ?: "?", Frequency.format(s.freqB), MeterCal.sText(s.sMeter),
                s.afGain, s.power, s.tx, s.widthHz?.toString() ?: "-", s.micSel(103)?.toString() ?: "?",
                if (a.connected) "on" else "off", a.packets, a.lost, a.rxLevelDb,
            ),
        )
    }
    val total = queue.submit<Long> { samples }.get()
    println("keyed by us: ${cat.keyedByUs}, seen ${cat.state.seen.size} keys, $total samples")
    cat.stop()
    audio.stop()
    Thread.sleep(500)
}

fun main(args: Array<String>) {
    when {
        args.firstOrNull() == "discover" -> discover()
        args.firstOrNull() == "listen" && args.size >= 2 -> listen(args[1], args.getOrNull(2)?.toDoubleOrNull() ?: 5.0)
        else -> println("usage: rhcheck discover | rhcheck listen HOST[:PORT] [SECONDS]")
    }
    queue.shutdown()
    queue.awaitTermination(1, TimeUnit.SECONDS)
}
