// CW from the phone: a straight key or iambic paddles and typed text, keyed
// in real time over a RigHelm `KEY` session (a port of righelm's
// crates/ftdx3000-rig/src/cw.rs).
//
// The timing is made here and the station keys its radio's port as told:
// `D` = down, `U` = up, `.` = still here. While held, `D` is repeated; the
// station lets go if it stops hearing from us (1.5 s) or the key stays down
// longer than 6 s.

package com.righelm.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class KeyerMode(val label: String) {
    /** Key down while held. */
    STRAIGHT("Straight key"),
    /** Iambic paddles without element memory past the squeeze. */
    IAMBIC_A("Iambic A"),
    /** Iambic paddles: a squeeze released mid-element adds the opposite one. */
    IAMBIC_B("Iambic B"),
}

/** The keying output. */
interface KeyLine {
    fun set(down: Boolean)
    /** Called a few times a second, so a remote end knows we're still here. */
    fun keepalive(down: Boolean)
    /** Let go and close. */
    fun close()
}

// MARK: - Remote

/**
 * Keys a RigHelm station's radio over a `KEY` session. Opening it arms the
 * station's keying port, then the station answers `READY` (or `ERR why`).
 */
class RemoteKeyLine(host: String, port: Int = RemoteProtocol.CONTROL_PORT, password: String) : KeyLine {
    private val socket: TcpConn

    init {
        val hs = Handshake.open(host, port, password, "KEY")
        socket = hs.socket
        try {
            val reply = socket.readLine(6.0)
            if (reply != "READY") {
                if (reply.startsWith("ERR ")) throw RemoteError.Denied(reply.removePrefix("ERR "))
                throw RemoteError.Unexpected(reply)
            }
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    override fun set(down: Boolean) = socket.write(if (down) "D" else "U")

    override fun keepalive(down: Boolean) {
        socket.write(if (down) "D" else ".")
        // The station never writes after READY; a read here only notices
        // that it closed (it throws).
        socket.read(0.0)
    }

    override fun close() {
        try { socket.write("U") } catch (_: Exception) {}
        socket.close()
    }
}

// MARK: - Keyer

sealed class KeyerInput {
    /** Straight key (or either paddle in straight mode) pressed/released. */
    data class Straight(val down: Boolean) : KeyerInput()
    /** Paddle state. */
    data class Paddles(val dit: Boolean, val dah: Boolean) : KeyerInput()
    /** Send this text. */
    data class Text(val text: String) : KeyerInput()
    /** Stop sending and let go of the key. */
    data object Stop : KeyerInput()
}

data class CwStatus(
    /** The key line is open and the keyer takes input. */
    val ready: Boolean = false,
    val down: Boolean = false,
    /** Typed text is being sent. */
    val sendingText: Boolean = false,
    val error: String? = null,
    val note: String? = null,
)

/**
 * The armed keyer, on its own thread. [stop] lets go of the key and closes
 * the line; the thread keeps the keyer alive until then. [open] runs on the
 * keyer thread (it may take a moment). [onKey] is called on the keyer
 * thread whenever the key goes down or up (for a sidetone).
 */
class CwKeyer(
    mode: KeyerMode,
    wpm: Int,
    private val onKey: ((Boolean) -> Unit)? = null,
    open: () -> KeyLine,
) {
    companion object {
        /** Longest continuous key-down before the keyer lets go by itself. */
        const val MAX_KEY_DOWN = 6.0
        /** Disarm after this long without keying. */
        const val IDLE_DISARM = 600.0
    }

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val queue = ArrayDeque<KeyerInput>()
    private var stopped = false
    private var wpmValue = wpm.coerceIn(4, 60)
    private var statusValue = CwStatus()
    private var lastActivity = now()
    val armedAt = now()

    init {
        Thread({ thread(mode, open) }, "righelm-cw-keyer").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }.start()
    }

    fun input(i: KeyerInput) = lock.withLock {
        queue.addLast(i)
        cond.signal()
    }

    fun setWPM(wpm: Int) = lock.withLock { wpmValue = wpm.coerceIn(4, 60) }

    val status: CwStatus get() = lock.withLock { statusValue }

    /** Seconds since the operator last keyed. */
    val idleFor: Double get() = now() - lock.withLock { lastActivity }

    fun stop() = lock.withLock {
        stopped = true
        queue.addLast(KeyerInput.Stop)
        cond.signal()
    }

    private fun update(change: (CwStatus) -> CwStatus) = lock.withLock { statusValue = change(statusValue) }

    private fun thread(mode: KeyerMode, open: () -> KeyLine) {
        val line = try {
            open()
        } catch (e: Exception) {
            update { it.copy(error = e.toString()) }
            return
        }
        // Stopped while the line was opening.
        if (lock.withLock { stopped }) {
            line.close()
            return
        }
        update { it.copy(ready = true) }
        val k = Keyer(line, ::update, onKey)
        try {
            run(k, mode)
        } catch (e: Exception) {
            update { it.copy(error = "keying stopped: $e") }
        }
        try { k.key(false) } catch (_: Exception) {}
        line.close()
        onKey?.invoke(false)
        update { it.copy(ready = false, down = false) }
    }

    /** Next input, or null once [deadline] passes; and whether to stop. */
    private fun next(deadline: Double): Pair<KeyerInput?, Boolean> = lock.withLock {
        while (queue.isEmpty() && !stopped) {
            val left = deadline - now()
            if (left <= 0) break
            cond.awaitNanos((left * 1e9).toLong())
        }
        val input = queue.removeFirstOrNull()
        if (input != null) lastActivity = now()
        input to stopped
    }

    private fun run(k: Keyer, mode: KeyerMode) {
        // Timed key states still to play: (down, length in dits).
        val plan = ArrayDeque<Pair<Boolean, Int>>()
        var textPlan = false // `plan` holds typed text
        var until: Double? = null
        var ditHeld = false
        var dahHeld = false
        var ditMem = false
        var dahMem = false
        var lastDah = false
        var lastKeepalive = now()
        while (true) {
            val (input, stop) = next(until ?: (now() + 0.1))
            if (stop) break
            when (input) {
                is KeyerInput.Straight -> if (mode == KeyerMode.STRAIGHT) {
                    plan.clear()
                    until = null
                    k.key(input.down)
                }
                is KeyerInput.Paddles -> if (mode == KeyerMode.STRAIGHT) {
                    plan.clear()
                    until = null
                    k.key(input.dit || input.dah)
                } else {
                    if (textPlan && (input.dit || input.dah)) {
                        // Touching the paddles interrupts typed text.
                        plan.clear()
                        textPlan = false
                        until = null
                        k.key(false)
                    }
                    ditMem = ditMem || (input.dit && !ditHeld)
                    dahMem = dahMem || (input.dah && !dahHeld)
                    ditHeld = input.dit
                    dahHeld = input.dah
                }
                is KeyerInput.Text -> if (plan.isEmpty() || textPlan) {
                    plan += morsePlan(input.text)
                    textPlan = true
                }
                KeyerInput.Stop -> {
                    plan.clear()
                    until = null
                    ditMem = false
                    dahMem = false
                    ditHeld = false
                    dahHeld = false
                    k.key(false)
                }
                null -> {}
            }
            val t = now()
            if (t - lastKeepalive > 0.4) {
                k.line.keepalive(k.down)
                lastKeepalive = t
            }
            val u = until
            if (u != null && t < u) continue
            until = null
            if (plan.isEmpty()) {
                textPlan = false
                if (mode != KeyerMode.STRAIGHT && !k.down) {
                    // Mode A forgets a paddle let go during the last element.
                    if (mode == KeyerMode.IAMBIC_A) {
                        ditMem = false
                        dahMem = false
                    }
                    val dit = ditMem || ditHeld
                    val dah = dahMem || dahHeld
                    val nextDah = when {
                        dit && dah -> !lastDah
                        dit -> false
                        dah -> true
                        else -> null
                    }
                    if (nextDah != null) {
                        if (nextDah) dahMem = false else ditMem = false
                        lastDah = nextDah
                        plan += true to (if (nextDah) 3 else 1)
                        plan += false to 1
                        lock.withLock { lastActivity = t }
                    }
                }
            }
            if (plan.isNotEmpty()) {
                val (down, dits) = plan.removeFirst()
                k.key(down)
                val dit = 1.2 / lock.withLock { wpmValue }
                until = t + dit * dits
            }
            if (k.down && t - k.downSince > MAX_KEY_DOWN) {
                plan.clear()
                until = null
                k.key(false)
                update { it.copy(note = "key held too long: released") }
            }
            val sending = textPlan
            update { if (it.sendingText != sending) it.copy(sendingText = sending) else it }
        }
    }
}

private class Keyer(
    val line: KeyLine,
    private val status: ((CwStatus) -> CwStatus) -> Unit,
    private val onKey: ((Boolean) -> Unit)?,
) {
    var down = false
        private set
    var downSince = now()
        private set

    fun key(d: Boolean) {
        if (d == down) return
        line.set(d)
        down = d
        if (d) downSince = now()
        onKey?.invoke(d)
        status { it.copy(down = d) }
    }
}

/** Monotonic seconds. */
private fun now(): Double = System.nanoTime() / 1e9

// MARK: - Morse

private val morseTable = mapOf(
    'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
    'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
    'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
    'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
    'Y' to "-.--", 'Z' to "--..",
    '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
    '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
    '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '/' to "-..-.", '=' to "-...-",
    '+' to ".-.-.", '-' to "-....-", '(' to "-.--.", ')' to "-.--.-", '\'' to ".----.",
    '"' to ".-..-.", ':' to "---...", '@' to ".--.-.", '!' to "-.-.--",
)

/** Morse for one character, `.` and `-`. */
fun morse(c: Char): String? = morseTable[c.uppercaseChar()]

/** Key states for [text]: (down, dits). Element gap 1, letter gap 3, word gap 7. */
fun morsePlan(text: String): List<Pair<Boolean, Int>> {
    val out = mutableListOf<Pair<Boolean, Int>>()
    var gap = 0
    for (c in text) {
        if (c.isWhitespace()) {
            gap = 7
            continue
        }
        val code = morse(c) ?: continue
        if (out.isNotEmpty()) out += false to maxOf(gap, 3)
        code.forEachIndexed { i, e ->
            if (i > 0) out += false to 1
            out += true to (if (e == '-') 3 else 1)
        }
        gap = 3
    }
    if (out.isNotEmpty()) out += false to 3
    return out
}
