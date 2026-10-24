// FTDX3000 CAT framing and command classification.

package com.righelm.core

/**
 * Splits a CAT byte stream into `;`-terminated frames. Control characters
 * are dropped, as the radio does.
 */
class Framer {
    private val buf = StringBuilder()

    companion object {
        const val MAX_FRAME = 128
    }

    fun push(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        for (byte in bytes) {
            val b = byte.toInt() and 0xff
            if (b == ';'.code) {
                out += "$buf;"
                buf.setLength(0)
            } else if (b in 0x20 until 0x7f) {
                if (buf.length >= MAX_FRAME) buf.setLength(0)
                if (!(b == 0x20 && buf.isEmpty())) buf.append(b.toChar())
            }
        }
        return out
    }
}

object CatCommand {
    /** Readable state polled in the background, slowest group (from righelm's engine). */
    val miscPoll = listOf(
        "AC;", "AG0;", "AN0;", "BC0;", "BI;", "BP00;", "BP01;", "BY;", "CO00;", "CO01;", "CO02;",
        "FR;", "FS;", "FT;", "GT0;", "IS0;", "KP;", "KR;", "KS;", "LK;", "MG;",
        "ML0;", "ML1;", "MS;", "MX;", "NA0;", "NB0;", "NL0;", "NR0;", "PA0;", "PC;", "PL;", "PR0;",
        "PR1;", "RA0;", "RF0;", "RG0;", "RI0;", "RL0;", "RT;", "SD;", "SH0;", "SQ0;",
        "VD;", "VG;", "VS;", "VX;", "XT;",
        // MIC SEL (AM, FM, SSB): where transmit audio comes from.
        "EX053;", "EX085;", "EX103;", "EX067;",
    )

    val vfoPoll = listOf("FA;", "FB;", "MD0;", "IF;", "OI;")

    /** Keys that change as a side effect of VFO, band or mode operations. */
    val vfoKeys = listOf("FA;", "FB;", "IF;", "OI;", "MD0;", "SH0;", "NA0;", "RF0;", "FT;", "FR;", "VS;", "PA0;", "RA0;", "AN0;", "GT0;")

    /** Commands whose Set moves the VFOs or the mode. */
    private val vfoMovers = setOf("MD", "BS", "BU", "BD", "AB", "BA", "SV", "FR", "FT", "VS", "QS", "MC", "MA", "AM", "UP", "DN")

    private val readable: Set<String> =
        (miscPoll + vfoPoll + listOf("TX;", "SM0;", "RM3;", "RM4;", "RM5;", "RM6;", "RM8;", "ID;", "PS;")).toSet()

    /** Split text such as "FA14074000;MD02;" into frames. */
    fun frames(text: String): List<String> =
        text.split(";").map { it.trim { c -> c == ' ' || c == '\t' } }.filter { it.length >= 2 }.map { "$it;" }

    /**
     * Does this Set command make the radio transmit, or arm it to? PTT/MOX
     * on, tuner start (sends a carrier), keyer/voice/encoder playback, VOX on.
     */
    fun isTransmit(frame: String): Boolean {
        val body = frame.removeSuffix(";")
        if (body.length < 2) return false
        val code = body.substring(0, 2).uppercase()
        val p = body.substring(2)
        if (p.isEmpty()) return false // a Read
        return when (code) {
            "TX", "MX" -> p != "0"
            "AC" -> p.endsWith("2")
            "KY", "EN" -> true
            "PB" -> p.length >= 2 && p[1] != '0'
            "VX" -> p == "1"
            else -> false
        }
    }

    fun isUnkey(frame: String): Boolean {
        val f = frame.uppercase()
        return f == "TX0;" || f == "MX0;"
    }

    /** The Read that fetches what a Set changes ("AG0128;" -> "AG0;"). */
    fun readback(set: String): String? {
        val body = set.uppercase().dropLast(1)
        var best: String? = null
        for (r in readable) {
            val rb = r.dropLast(1)
            if (body.length > rb.length && body.startsWith(rb) && (best == null || rb.length > best.length - 1)) {
                best = r
            }
        }
        return best
    }

    /** Key identifying what an answer reports ("AG0128;" -> "AG0", "EX0532;" -> "EX053"). */
    fun answerKey(frame: String): String? {
        val up = frame.uppercase()
        if (up.startsWith("EX") && up.length >= 6) return up.substring(0, 5)
        if (up.startsWith("RM") && up.length >= 4) return up.substring(0, 3)
        return readback(up)?.dropLast(1) ?: if (up.length >= 2) up.substring(0, 2) else null
    }

    /** Whether a Set moves the VFOs, band or mode (so related keys are re-read). */
    fun movesVFO(frame: String): Boolean =
        frame.uppercase().take(2) in vfoMovers && frame.length > 3
}
