// A typed model of the FTDX3000, updated from CAT answers and
// auto-information (a port of righelm's ftdx3000-cat state and types).

package com.righelm.core

enum class Mode(val code: Char, val label: String) {
    LSB('1', "LSB"), USB('2', "USB"), CW('3', "CW"), FM('4', "FM"), AM('5', "AM"),
    RTTY_LSB('6', "RTTY-L"), CW_R('7', "CW-R"), DATA_LSB('8', "DATA-L"), RTTY_USB('9', "RTTY-U"),
    DATA_FM('A', "DATA-FM"), FM_N('B', "FM-N"), DATA_USB('C', "DATA-U"), AM_N('D', "AM-N");

    val setCommand: String get() = "MD0$code;"

    val isCW: Boolean get() = this == CW || this == CW_R

    /** Phone modes, whose transmit audio is a microphone. */
    val isVoice: Boolean get() = this in setOf(LSB, USB, AM, AM_N, FM, FM_N)

    val widthClass: WidthClass
        get() = when (this) {
            LSB, USB, DATA_LSB, DATA_USB -> WidthClass.SSB
            CW, CW_R -> WidthClass.CW
            RTTY_LSB, RTTY_USB -> WidthClass.RTTY
            AM, AM_N, FM, FM_N, DATA_FM -> WidthClass.FIXED
        }

    companion object {
        /** Front-panel order. */
        val ordered = listOf(LSB, USB, CW, CW_R, AM, AM_N, FM, FM_N, RTTY_LSB, RTTY_USB, DATA_LSB, DATA_USB, DATA_FM)

        fun of(code: Char): Mode? = entries.firstOrNull { it.code == code.uppercaseChar() }
    }
}

enum class WidthClass { SSB, CW, RTTY, FIXED }

private val ssbN = intArrayOf(1500, 200, 400, 600, 850, 1100, 1350, 1500, 1650, 1800)
private val ssbW = intArrayOf(2400, 0, 0, 0, 0, 0, 0, 0, 0, 1800, 1950, 2100, 2200, 2300, 2400, 2500, 2600, 2700, 2800, 2900, 3000, 3200, 3400, 3600, 3800, 4000)
private val cwN = intArrayOf(500, 50, 100, 150, 200, 250, 300, 350, 400, 450, 500)
private val cwW = intArrayOf(2400, 0, 0, 0, 0, 0, 0, 0, 0, 0, 500, 800, 1200, 1400, 1700, 2000, 2400)

/** Table 5: `SH` index -> bandwidth in Hz. Index 0 is the default width. */
fun widthHz(cls: WidthClass, narrow: Boolean, index: Int): Int? {
    val table = when (cls) {
        WidthClass.SSB -> if (narrow) ssbN else ssbW
        WidthClass.CW, WidthClass.RTTY -> if (narrow) cwN else cwW
        WidthClass.FIXED -> return null
    }
    if (index !in table.indices || table[index] == 0) return null
    return table[index]
}

/** Valid `SH` indexes (skipping 0, the "default" alias) with their widths. */
fun widthChoices(cls: WidthClass, narrow: Boolean): List<Pair<Int, Int>> =
    (1..25).mapNotNull { i -> widthHz(cls, narrow, i)?.let { i to it } }

data class Band(val code: Int, val label: String, val lo: Int, val hi: Int) {
    val setCommand: String get() = "BS%02d;".fmt(code)

    companion object {
        val all = listOf(
            Band(0, "160", 1_800_000, 2_000_000),
            Band(1, "80", 3_500_000, 4_000_000),
            Band(2, "60", 5_250_000, 5_450_000),
            Band(3, "40", 7_000_000, 7_300_000),
            Band(4, "30", 10_100_000, 10_150_000),
            Band(5, "20", 14_000_000, 14_350_000),
            Band(6, "17", 18_068_000, 18_168_000),
            Band(7, "15", 21_000_000, 21_450_000),
            Band(8, "12", 24_890_000, 24_990_000),
            Band(9, "10", 28_000_000, 29_700_000),
            Band(10, "6", 50_000_000, 54_000_000),
        )

        fun of(hz: Int): Band? = all.firstOrNull { hz in it.lo..it.hi }
    }
}

object Frequency {
    const val MIN = 30_000
    const val MAX = 60_000_000

    fun setCommand(vfoB: Boolean, hz: Int): String =
        "F%s%08d;".fmt(if (vfoB) "B" else "A", hz.coerceIn(MIN, MAX))

    /** 14074000 -> "14.074.000" */
    fun format(hz: Int): String = "%d.%03d.%03d".fmt(hz / 1_000_000, hz / 1000 % 1000, hz % 1000)
}

object AGC {
    private val labels = listOf("OFF", "FAST", "MID", "SLOW", "AUTO-F", "AUTO-M", "AUTO-S")

    fun label(code: Int): String = labels.getOrNull(code) ?: "?"

    val choices = listOf(0 to "OFF", 1 to "FAST", 2 to "MID", 3 to "SLOW", 4 to "AUTO")
}

object Roofing {
    fun label(code: Char): String? = when (code.uppercaseChar()) {
        '1' -> "15 kHz"
        '2' -> "6 kHz"
        '3' -> "3 kHz"
        '4' -> "AUTO 15 kHz"
        '5' -> "AUTO 6 kHz"
        '6' -> "AUTO 3 kHz"
        '7' -> "600 Hz"
        '8' -> "300 Hz"
        '9' -> "AUTO 600 Hz"
        'A' -> "AUTO 300 Hz"
        else -> null
    }

    val choices = listOf('0' to "AUTO", '1' to "15 kHz", '2' to "6 kHz", '3' to "3 kHz", '4' to "600 Hz", '5' to "300 Hz")
}

// MARK: - Meters

enum class Meter(val code: Char) { S('1'), COMP('3'), ALC('4'), PO('5'), SWR('6'), ID('7'), VDD('8') }

object MeterCal {
    val sMeter = listOf(
        0 to -54.0, 12 to -48.0, 27 to -42.0, 40 to -36.0, 55 to -30.0, 65 to -24.0, 80 to -18.0, 95 to -12.0,
        112 to -6.0, 130 to 0.0, 150 to 10.0, 172 to 20.0, 190 to 30.0, 220 to 40.0, 240 to 50.0, 255 to 60.0,
    )
    val po = listOf(
        0 to 0.0, 55 to 10.0, 75 to 20.0, 101 to 40.0, 125 to 60.0, 144 to 80.0, 161 to 100.0, 177 to 120.0,
        190 to 140.0, 202 to 160.0, 215 to 180.0, 225 to 200.0, 237 to 220.0, 242 to 240.0, 255 to 250.0,
    )
    val swr = listOf(0 to 1.0, 26 to 1.2, 52 to 1.5, 89 to 2.0, 126 to 3.0, 173 to 4.0, 236 to 5.0, 255 to 10.0)
    val id = listOf(0 to 0.0, 255 to 25.0)
    val vdd = listOf(0 to 0.0, 255 to 15.0)

    fun interpolate(table: List<Pair<Int, Double>>, raw: Int): Double {
        val first = table.firstOrNull() ?: return raw.toDouble()
        if (raw <= first.first) return first.second
        for (i in 1 until table.size) {
            val (r0, v0) = table[i - 1]
            val (r1, v1) = table[i]
            if (raw <= r1) return v0 + (raw - r0).toDouble() / maxOf(r1 - r0, 1) * (v1 - v0)
        }
        return table.last().second
    }

    /** "S7", "S9+20" */
    fun sText(raw: Int): String {
        val db = interpolate(sMeter, raw)
        val over = (db / 10).roundHalfAway() * 10
        // Just over S9 rounds to +0: still plain S9.
        if (db <= 0 || over == 0) return "S${((db + 54) / 6).roundHalfAway().coerceIn(0, 9)}"
        return "S9+$over"
    }

    /** Swift's `rounded()`: halves away from zero. */
    private fun Double.roundHalfAway(): Int = if (this >= 0) (this + 0.5).toInt() else -((-this + 0.5).toInt())
}

// MARK: - State

/** The information block of `IF`, `OI` and `MR` answers. */
data class ChannelInfo(
    val channel: Int = 0,
    val freq: Int = 0,
    val clarOffset: Int = 0,
    val rxClar: Boolean = false,
    val txClar: Boolean = false,
    val mode: Mode? = null,
    /** 0 VFO, 1 memory, 2 memory tune, 3 QMB, 4 QMB-MT. */
    val memMode: Int = 0,
) {
    companion object {
        fun parse(p: String): ChannelInfo? {
            if (p.length < 24) return null
            val ch = p.substring(0, 3).toIntOrNull() ?: return null
            val f = p.substring(3, 11).toIntOrNull() ?: return null
            val off = p.substring(12, 16).toIntOrNull() ?: return null
            return ChannelInfo(
                channel = ch,
                freq = f,
                clarOffset = if (p[11] == '-') -off else off,
                rxClar = p[16] == '1',
                txClar = p[17] == '1',
                mode = Mode.of(p[18]),
                memMode = p.substring(19, 20).toIntOrNull() ?: 0,
            )
        }
    }
}

/**
 * The radio as last heard. The CAT client changes its own copy and hands
 * out snapshots made with [copy]; the collections are never changed in
 * place, so snapshots don't share mutable state.
 */
data class RadioState(
    /** Answer keys seen at least once ("FA", "AG0", "EX053"...). */
    var seen: Set<String> = emptySet(),

    var id: Int? = null,
    var powerOn: Boolean? = null,
    var freqA: Int = 0,
    var freqB: Int = 0,
    var modeA: Mode? = null,
    var modeB: Mode? = null,
    var infoA: ChannelInfo = ChannelInfo(),
    var infoB: ChannelInfo = ChannelInfo(),
    /** VS: 0 VFO-A, 1 VFO-B. */
    var vfoSelect: Int = 0,
    /** FR: 0 A rx, 1 A mute, 4 B rx, 5 B mute. */
    var rxFunction: Int = 0,
    /** FT answer: 0 VFO-A transmitter, 1 VFO-B transmitter. */
    var txVFO: Int = 0,
    /** TX answer: 0 off, 1 CAT TX, 2 radio TX (PTT/MOX/VOX). */
    var tx: Int = 0,
    var mox: Boolean = false,
    var busy: Boolean = false,

    var afGain: Int = 0,
    var rfGain: Int = 0,
    var squelch: Int = 0,
    var power: Int = 0,
    var micGain: Int = 0,
    var procLevel: Int = 0,
    var processor: Boolean = false,
    var micEQ: Boolean = false,
    var vox: Boolean = false,
    var voxGain: Int = 0,
    var voxDelay: Int = 0,
    var monitor: Boolean = false,
    var monitorLevel: Int = 0,

    /** NB: 0 off, 1 on, 2 wide. */
    var nb: Int = 0,
    var nbLevel: Int = 0,
    var nr: Boolean = false,
    var nrLevel: Int = 0,
    var autoNotch: Boolean = false,
    var manualNotch: Boolean = false,
    /** Manual notch frequency in 10 Hz units (1-400). */
    var notchFreq: Int = 0,
    /** CO00: 0 off, 1 contour, 2 APF. */
    var contourMode: Int = 0,
    /** CO01: 1-40 (x100 Hz). */
    var contourFreq: Int = 0,
    /** CO02: 0-50 (APF -250..+250 Hz, 25 = centre). */
    var apfFreq: Int = 25,
    var widthIndex: Int = 0,
    var narrow: Boolean = false,
    var ifShift: Int = 0,
    var roofing: Char = '0',
    /** RA: 0 off, 1 6 dB, 2 12 dB, 3 18 dB. */
    var att: Int = 0,
    /** PA: 0 IPO, 1 AMP1, 2 AMP2. */
    var preamp: Int = 0,
    var agc: Int = 0,

    var antenna: Int = 1,
    /** AC P3: 0 off, 1 on, 2 tuning. */
    var tuner: Int = 0,

    var keyer: Boolean = false,
    var keySpeed: Int = 0,
    /** KP 0-75 -> 300 + 10*n Hz. */
    var keyPitch: Int = 0,
    var breakIn: Boolean = false,
    var bkDelay: Int = 0,

    var rxClar: Boolean = false,
    var txClar: Boolean = false,
    var lock: Int = 0,
    var fast: Int = 0,
    var meterSelect: Int = 0,

    /** Raw meter readings 0-255 by RM code. */
    var meters: Map<Char, Int> = emptyMap(),
    var sMeter: Int = 0,
    /** RI flags by P1 code (0 = HI-SWR). */
    var radioInfo: Map<Int, Boolean> = emptyMap(),
    /** EX menu raw parameter text by item number. */
    var menu: Map<Int, String> = emptyMap(),
) {
    val transmitting: Boolean get() = tx != 0 || mox
    val rxOnB: Boolean get() = rxFunction >= 4
    val split: Boolean get() = (txVFO == 1) != rxOnB
    val rxFreq: Int get() = if (rxOnB) freqB else freqA
    val rxMode: Mode? get() = if (rxOnB) modeB else modeA
    val txMode: Mode? get() = if (txVFO == 1) modeB else modeA
    val txFreq: Int get() = if (txVFO == 1) freqB else freqA
    val highSWR: Boolean get() = radioInfo[0] == true

    fun meter(m: Meter): Int? = if (m == Meter.S) sMeter else meters[m.code]

    fun widthClass(mode: Mode): WidthClass = when (mode) {
        // DATA modes use the RTTY/PSK widths unless menu 067 DATA MODE is OTHER.
        Mode.DATA_LSB, Mode.DATA_USB -> if (menu[67] == "1") WidthClass.SSB else WidthClass.RTTY
        else -> mode.widthClass
    }

    val widthHz: Int?
        get() {
            val m = modeA ?: return null
            return widthHz(widthClass(m), narrow, widthIndex)
        }

    /** The MIC SEL code of a menu (53 AM, 85 FM, 103 SSB): 0 front, 1 data, 2 USB. */
    fun micSel(menuItem: Int): Int? = menu[menuItem]?.toIntOrNull()

    /** Apply one frame from the radio. Returns false if it was not understood. */
    fun apply(frame: String): Boolean {
        val body = frame.removeSuffix(";")
        if (body.length < 2) return false
        val code = body.substring(0, 2).uppercase()
        if (!code.all { it.isLetter() }) return false
        if (!applyInner(code, body.substring(2))) return false
        CatCommand.answerKey(frame)?.let { if (it !in seen) seen = seen + it }
        return true
    }

    private fun applyInner(code: String, p: String): Boolean {
        fun at(i: Int): String = if (i < p.length) p[i].toString() else ""
        fun from(i: Int): String = if (i < p.length) p.substring(i) else ""
        fun n(s: String): Int? = s.removePrefix("+").toIntOrNull()
        fun flag(s: String) = s == "1"
        when (code) {
            "AC" -> tuner = n(at(2)) ?: return false
            "AG" -> afGain = n(from(1)) ?: return false
            "AI" -> {}
            "AN" -> antenna = n(at(1)) ?: return false
            "BC" -> autoNotch = flag(at(1))
            "BI" -> breakIn = flag(p)
            "BP" -> when (at(1)) {
                "0" -> manualNotch = (n(from(2)) ?: return false) != 0
                "1" -> notchFreq = n(from(2)) ?: return false
                else -> return false
            }
            "BY" -> busy = flag(at(0))
            "CO" -> {
                val v = n(from(2)) ?: return false
                when (at(1)) {
                    "0" -> contourMode = v
                    "1" -> contourFreq = v
                    "2" -> apfFreq = v
                    else -> return false
                }
            }
            "EX" -> {
                if (p.length < 3) return false
                val num = p.substring(0, 3).toIntOrNull() ?: return false
                menu = menu + (num to p.substring(3))
            }
            "FA" -> {
                val v = n(p) ?: return false
                freqA = v
                infoA = infoA.copy(freq = v)
            }
            "FB" -> {
                val v = n(p) ?: return false
                freqB = v
                infoB = infoB.copy(freq = v)
            }
            "FR" -> rxFunction = n(p) ?: return false
            "FS" -> fast = n(p) ?: return false
            "FT" -> txVFO = n(p) ?: return false
            "GT" -> agc = n(at(1)) ?: return false
            "ID" -> id = n(p) ?: return false
            "IF" -> {
                val info = ChannelInfo.parse(p) ?: return false
                freqA = info.freq
                modeA = info.mode ?: modeA
                rxClar = info.rxClar
                txClar = info.txClar
                infoA = info
            }
            "IS" -> ifShift = n(from(1)) ?: return false
            "KP" -> keyPitch = n(p) ?: return false
            "KR" -> keyer = flag(p)
            "KS" -> keySpeed = n(p) ?: return false
            "LK" -> lock = n(p) ?: return false
            "MD" -> {
                val m = p.getOrNull(1)?.let { Mode.of(it) } ?: return false
                modeA = m
                infoA = infoA.copy(mode = m)
            }
            "MG" -> micGain = n(p) ?: return false
            "ML" -> {
                val v = n(from(1)) ?: return false
                when (at(0)) {
                    "0" -> monitor = v != 0
                    "1" -> monitorLevel = v
                    else -> return false
                }
            }
            "MS" -> meterSelect = n(p) ?: return false
            "MX" -> mox = flag(p)
            "NA" -> narrow = flag(at(1))
            "NB" -> nb = n(at(1)) ?: return false
            "NL" -> nbLevel = n(from(1)) ?: return false
            "NR" -> nr = flag(at(1))
            "OI" -> {
                val info = ChannelInfo.parse(p) ?: return false
                freqB = info.freq
                modeB = info.mode ?: modeB
                infoB = info
            }
            "PA" -> preamp = n(at(1)) ?: return false
            "PC" -> power = n(p) ?: return false
            "PL" -> procLevel = n(p) ?: return false
            "PR" -> when (at(0)) {
                "0" -> processor = at(1) == "1"
                "1" -> micEQ = at(1) == "1"
                else -> return false
            }
            "PS" -> powerOn = flag(p)
            "RA" -> att = n(at(1)) ?: return false
            "RF" -> {
                if (p.length < 2) return false
                roofing = p[1]
            }
            "RG" -> rfGain = n(from(1)) ?: return false
            "RI" -> {
                val k = n(at(0)) ?: return false
                radioInfo = radioInfo + (k to flag(at(1)))
            }
            "RL" -> nrLevel = n(from(1)) ?: return false
            "RM" -> {
                val c = p.firstOrNull() ?: return false
                val v = n(from(1)) ?: return false
                if (c == '1') sMeter = v
                meters = meters + (c to v)
            }
            "RT" -> rxClar = flag(p)
            "SD" -> bkDelay = n(p) ?: return false
            "SH" -> widthIndex = n(from(1)) ?: return false
            "SM" -> sMeter = n(from(1)) ?: return false
            "SQ" -> squelch = n(from(1)) ?: return false
            "TX" -> tx = n(p) ?: return false
            "VD" -> voxDelay = n(p) ?: return false
            "VG" -> voxGain = n(p) ?: return false
            "VS" -> vfoSelect = n(p) ?: return false
            "VX" -> vox = flag(p)
            "XT" -> txClar = flag(p)
            else -> return false
        }
        return true
    }
}
