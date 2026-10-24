// Controls a remote radio over a RigHelm `CAT` session: keeps a copy of
// the radio's state up to date (auto-information plus polling, like
// righelm's own engine) and sends Set commands.
//
// Transmit safety: a Set that would transmit is dropped unless
// `isTransmitAllowed()` says yes, and the session un-keys before it closes.
// The server also un-keys a client that vanishes.

package com.righelm.core

class CatClient(
    val host: String,
    val port: Int = RemoteProtocol.CONTROL_PORT,
    private val password: String,
) {
    sealed class Status {
        data object Connecting : Status()
        data class Connected(val server: String) : Status()
        /** The last attempt failed; retrying. */
        data class Failed(val why: String) : Status()
        data object Stopped : Status()
    }

    /** Runs callbacks on the caller's thread of choice (the app's main thread). */
    var callback: (Runnable) -> Unit = { it.run() }
    var onStatus: ((Status) -> Unit)? = null
    var onState: ((RadioState) -> Unit)? = null
    var onNote: ((String) -> Unit)? = null
    /** Asked before any transmit command goes out. */
    var isTransmitAllowed: () -> Boolean = { false }

    private val lock = Any()
    private val outbox = mutableListOf<String>()
    private var stopRequested = false
    private var started = false
    private var keyed = false
    private var keyedAt = 0L
    private var latest = RadioState()

    /**
     * This client keyed the transmitter (and has not let go). Microphone
     * audio is only sent while this is true.
     */
    val keyedByUs: Boolean get() = synchronized(lock) { keyed }

    val state: RadioState get() = synchronized(lock) { latest }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        Thread(::run, "righelm-cat").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
        }.start()
    }

    /** Un-keys if this client keyed, then closes. */
    fun stop() {
        synchronized(lock) { stopRequested = true }
    }

    /** Queue Set (or Read) commands, e.g. "FA14074000;" or "AB;SV;". */
    fun send(commands: String) {
        for (f in CatCommand.frames(commands)) {
            if (CatCommand.isTransmit(f) && !isTransmitAllowed()) {
                note("not sent: $f would transmit, and transmit is locked")
                continue
            }
            synchronized(lock) {
                outbox += f
                if (CatCommand.isTransmit(f) && (f.startsWith("TX") || f.startsWith("MX"))) {
                    keyed = true
                    keyedAt = System.currentTimeMillis()
                } else if (CatCommand.isUnkey(f)) {
                    keyed = false
                }
            }
        }
    }

    private val shouldStop: Boolean get() = synchronized(lock) { stopRequested }

    private fun run() {
        while (!shouldStop) {
            report(Status.Connecting)
            try {
                session()
            } catch (e: Exception) {
                report(Status.Failed(e.toString()))
            }
            // Wait before retrying; a wrong password won't get better by itself.
            val deadline = System.currentTimeMillis() + 3000
            while (!shouldStop && System.currentTimeMillis() < deadline) Thread.sleep(100)
        }
        report(Status.Stopped)
    }

    private class Poll(
        val cmd: String,
        val every: Long,
        var next: Long,
        /** null = always; true = only transmitting; false = only receiving. */
        val whenTX: Boolean?,
    )

    private fun session() {
        val hs = Handshake.open(host, port, password, "CAT")
        val sock = hs.socket
        try {
            report(Status.Connected(hs.serverName))
            note("connected to \"${hs.serverName}\" at $host")

            val state = RadioState()
            val framer = Framer()
            val start = System.currentTimeMillis()
            val polls = mutableListOf(
                Poll("TX;", 250, start, null),
                Poll("SM0;", 150, start, false),
            )
            for (m in listOf("RM5;", "RM6;", "RM4;", "RM3;")) polls += Poll(m, 150, start, true)
            polls += Poll("RM8;", 2000, start, true)
            for (v in CatCommand.vfoPoll) polls += Poll(v, 3000, start + 3000, null)
            CatCommand.miscPoll.forEachIndexed { i, m -> polls += Poll(m, 15_000, start + 1000 + i * 250L, null) }
            // Read everything once, then turn on auto-information so changes
            // made at the station arrive straight away.
            val once = ArrayDeque(listOf("ID;", "PS;") + CatCommand.vfoPoll + CatCommand.miscPoll)
            val later = mutableListOf<Pair<Long, String>>()
            sock.write("AI1;")

            var lastHeard = System.currentTimeMillis()
            var lastPublish = 0L
            var dirty = true
            while (true) {
                if (shouldStop) {
                    val unkey = synchronized(lock) { keyed.also { keyed = false } }
                    // Anything queued (such as a final TX0;) goes out first.
                    val rest = synchronized(lock) { outbox.joinToString("").also { outbox.clear() } }
                    try {
                        sock.write(rest + if (unkey) "TX0;" else "")
                    } catch (_: Exception) {}
                    Thread.sleep(50)
                    return
                }
                val bytes = sock.read(0.03)
                if (bytes.isNotEmpty()) {
                    lastHeard = System.currentTimeMillis()
                    for (f in framer.push(bytes)) if (state.apply(f)) dirty = true
                }
                // The server answers polls several times a second.
                if (System.currentTimeMillis() - lastHeard > 6000) {
                    throw SocketError("the server stopped answering")
                }
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    // Let go of "keyed by us" once the radio is back on receive.
                    if (keyed && !state.transmitting && now - keyedAt > 2000) keyed = false
                }

                val out = StringBuilder()
                val sets = synchronized(lock) { outbox.toList().also { outbox.clear() } }
                for (f in sets) {
                    out.append(f)
                    if (f.length > 3 || !f.endsWith(";")) {
                        // Show the change right away; the radio's answer confirms it.
                        if (!CatCommand.isTransmit(f) && state.apply(f)) dirty = true
                        CatCommand.readback(f)?.let { later += (now + 300) to it }
                        if (CatCommand.movesVFO(f)) {
                            later += CatCommand.vfoKeys.map { (now + 400) to it }
                        }
                    }
                }
                val tx = state.transmitting
                for (p in polls) {
                    if (p.next > now) continue
                    if (p.whenTX != null && p.whenTX != tx) continue
                    out.append(p.cmd)
                    p.next = now + p.every
                }
                repeat(minOf(8, once.size)) { out.append(once.removeFirst()) }
                val due = later.filter { it.first <= now }.map { it.second }
                later.removeAll { it.first <= now }
                for (r in due.toSet()) out.append(r)
                if (out.isNotEmpty()) sock.write(out.toString())

                if (dirty && now - lastPublish > 60) {
                    dirty = false
                    lastPublish = now
                    val snapshot = state.copy()
                    synchronized(lock) { latest = snapshot }
                    callback(Runnable { onState?.invoke(snapshot) })
                }
            }
        } finally {
            sock.close()
        }
    }

    private fun report(s: Status) {
        callback(Runnable { onStatus?.invoke(s) })
    }

    private fun note(text: String) {
        callback(Runnable { onNote?.invoke(text) })
    }
}
