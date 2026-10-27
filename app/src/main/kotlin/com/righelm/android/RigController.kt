package com.righelm.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.righelm.android.audio.AudioIO
import com.righelm.android.support.Passwords
import com.righelm.android.support.SavedServer
import com.righelm.android.support.SavedServers
import com.righelm.core.AudioLink
import com.righelm.core.AudioLinkStats
import com.righelm.core.CatClient
import com.righelm.core.CwKeyer
import com.righelm.core.CwStatus
import com.righelm.core.KeyerInput
import com.righelm.core.KeyerMode
import com.righelm.core.RemoteKeyLine
import com.righelm.core.Discovery
import com.righelm.core.Frequency
import com.righelm.core.fmt
import com.righelm.core.Mode
import com.righelm.core.RadioState
import com.righelm.core.ServerBeacon
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Everything the app knows and does: finding stations, the CAT and audio
 * sessions to one, the phone mic, the phone CW key, and transmit safety. Used on the main
 * thread only; one for the whole process, so it outlives the activity and
 * keeps the station connected (with [RigService]) in the background.
 */
class RigController(private val context: Context) {
    sealed class Phase {
        data object Idle : Phase()
        data object Connecting : Phase()
        data object Connected : Phase()
        data class Failed(val why: String) : Phase()
    }

    private val prefs = context.getSharedPreferences("righelm", Context.MODE_PRIVATE)
    private val passwords = Passwords(prefs)
    private val main = Handler(Looper.getMainLooper())
    private val onMain: (Runnable) -> Unit = { main.post(it) }

    // MARK: Stations

    var discovered by mutableStateOf(listOf<ServerBeacon>())
        private set
    var saved by mutableStateOf(SavedServers.load(prefs))
        private set
    /** The station being controlled (null: show the station list). */
    var target by mutableStateOf<SavedServer?>(null)
        private set
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    var serverName by mutableStateOf("")
        private set
    /** Why the last connection ended, shown on the station list. */
    var lastError by mutableStateOf<String?>(null)

    // MARK: Radio

    var state by mutableStateOf(RadioState())
        private set
    var audioStats by mutableStateOf(AudioLinkStats())
        private set
    /** Times the phone's playout ran dry, for the Settings stream line. */
    var audioDropouts by mutableStateOf(0)
        private set
    var notes by mutableStateOf(listOf<String>())
        private set

    // MARK: Settings

    private var _receiveAudio by mutableStateOf(prefs.getBoolean("receiveAudio", true))
    var receiveAudio: Boolean
        get() = _receiveAudio
        set(v) {
            _receiveAudio = v
            prefs.edit().putBoolean("receiveAudio", v).apply()
            listening.set(v)
            restartAudio()
        }

    private var _volume by mutableStateOf(prefs.getFloat("volume", 1f))
    var volume: Float
        get() = _volume
        set(v) {
            _volume = v
            prefs.edit().putFloat("volume", v).apply()
            audio.volume = v
        }

    private var _micGain by mutableStateOf(prefs.getFloat("micGain", 1f))
    var micGain: Float
        get() = _micGain
        set(v) {
            _micGain = v
            prefs.edit().putFloat("micGain", v).apply()
            audio.micGain = v
        }

    /**
     * Off until the operator turns it on in Settings. While off, nothing
     * that transmits leaves the phone. Saved, so it stays on once allowed.
     */
    private var _transmitAllowed by mutableStateOf(prefs.getBoolean("transmitAllowed", false))
    var transmitAllowed: Boolean
        get() = _transmitAllowed
        set(v) {
            _transmitAllowed = v
            prefs.edit().putBoolean("transmitAllowed", v).apply()
            txAllowedFlag.set(v)
            if (!v) {
                unkey()
                cwDisarm("transmit was locked")
            }
        }

    /** Longest transmission before the phone lets go by itself. */
    private var _txTimeoutSeconds by mutableStateOf(prefs.getInt("txTimeout", 180))
    var txTimeoutSeconds: Int
        get() = _txTimeoutSeconds
        set(v) {
            _txTimeoutSeconds = v
            prefs.edit().putInt("txTimeout", v).apply()
        }

    private var _keepAwake by mutableStateOf(prefs.getBoolean("keepAwake", false))
    var keepAwake: Boolean
        get() = _keepAwake
        set(v) {
            _keepAwake = v
            prefs.edit().putBoolean("keepAwake", v).apply()
        }

    private var _reconnectOnLaunch by mutableStateOf(prefs.getBoolean("reconnectOnLaunch", true))
    var reconnectOnLaunch: Boolean
        get() = _reconnectOnLaunch
        set(v) {
            _reconnectOnLaunch = v
            prefs.edit().putBoolean("reconnectOnLaunch", v).apply()
        }

    private var _tuneStep by mutableStateOf(prefs.getInt("tuneStep", 100))
    var tuneStep: Int
        get() = _tuneStep
        set(v) {
            _tuneStep = v
            prefs.edit().putInt("tuneStep", v).apply()
        }

    // MARK: Transmit

    /**
     * Talk through the phone: the radio's MIC SEL menus are set to USB and
     * the phone's microphone is sent while this phone transmits.
     */
    var phoneMic by mutableStateOf(false)
        private set
    var micLevelDb by mutableStateOf(-100f)
        private set
    /** Microphone audio is going to the radio right now. */
    var sendingMic by mutableStateOf(false)
        private set
    var latched by mutableStateOf(false)
        private set
    /** When this phone keyed the radio (milliseconds), or null. */
    var txStartedAt by mutableStateOf<Long?>(null)
        private set
    // MARK: CW key

    /**
     * The phone key: CW keyed from this phone over a `KEY` session, like
     * RigHelm's COMPUTER KEY. Never on at launch.
     */
    var cwArmed by mutableStateOf(false)
        private set
    var cwStatus by mutableStateOf(CwStatus())
        private set
    /** Why the phone key went back to the radio's key by itself. */
    var cwDisarmedReason by mutableStateOf<String?>(null)
        private set

    private var _keyerMode by mutableStateOf(
        KeyerMode.entries.firstOrNull { it.name == prefs.getString("keyerMode", null) } ?: KeyerMode.IAMBIC_B,
    )
    var keyerMode: KeyerMode
        get() = _keyerMode
        set(v) {
            val old = _keyerMode
            _keyerMode = v
            prefs.edit().putString("keyerMode", v.name).apply()
            if (cwArmed && v != old) setPhoneKey(true)
        }

    /** Play a sidetone on the phone while keying. */
    private var _cwSidetone by mutableStateOf(prefs.getBoolean("cwSidetone", false))
    var cwSidetone: Boolean
        get() = _cwSidetone
        set(v) {
            _cwSidetone = v
            prefs.edit().putBoolean("cwSidetone", v).apply()
            audio.sidetone.setEnabled(v && cwArmed)
            if (cwArmed) restartAudioIfNeeded()
        }

    /** A transmit button was pressed while transmit is locked. */
    var showLockedHint by mutableStateOf(false)
    /** The UI should ask for the microphone permission. */
    var micPermissionWanted by mutableStateOf(false)
    var micPermissionDenied by mutableStateOf(false)

    // MARK: Internals

    private var cat: CatClient? = null
    private var link: AudioLink? = null
    private val audio = AudioIO()
    private val discovery = Discovery()
    private val txAllowedFlag = AtomicBoolean(false)
    private val listening = AtomicBoolean(receiveAudio)
    private val micGate = AtomicBoolean(false)
    private var keyer: CwKeyer? = null
    /** Last paddle state given to the keyer. */
    private var sentPaddles = false to false
    /** The tuned frequency shown while the knob turns, ahead of the radio. */
    private var pendingFreq by mutableStateOf<PendingFreq?>(null)
    private var lastFreqSend = 0L
    private var freqSendScheduled = false
    /** MIC SEL values from before switching to the phone mic, by menu. */
    private val savedMicSel = mutableMapOf<Int, Int>()
    private var launched = false
    private var inForeground = false

    private data class PendingFreq(val hz: Int, val vfoB: Boolean, val until: Long)

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifi.createMulticastLock("righelm-discovery").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION")
    private val wifiLock = wifi.createWifiLock(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "righelm-stream",
    ).apply { setReferenceCounted(false) }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .setOnAudioFocusChangeListener({ focusChanged(it) }, main)
        .build()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 100)
        }
    }

    init {
        txAllowedFlag.set(transmitAllowed)
        audio.micGain = micGain
        audio.volume = volume
        discovery.callback = onMain
        discovery.onUpdate = { discoveryUpdated(it) }
        startDiscovery()
        main.post(ticker)
    }

    /** The activity has opened for the first time in this process. */
    fun launched() {
        if (launched) return
        launched = true
        val last = prefs.getString("lastServer", null)
        if (reconnectOnLaunch && target == null && last != null) {
            saved.firstOrNull { it.id == last }?.let { connect(it, passwords.get(it.id) ?: "") }
        }
    }

    // MARK: - Stations

    private fun startDiscovery() {
        multicastLock.acquire()
        discovery.start()
    }

    private fun stopDiscovery() {
        discovery.stop()
        if (multicastLock.isHeld) multicastLock.release()
    }

    private fun discoveryUpdated(list: List<ServerBeacon>) {
        discovered = list
        // Keep saved names current.
        var changed = false
        val updated = saved.map { s ->
            val b = list.firstOrNull { it.host == s.host && it.port == s.port }
            if (b != null && b.name != s.name) {
                changed = true
                s.copy(name = b.name)
            } else {
                s
            }
        }
        if (changed) storeSaved(updated)
    }

    private fun storeSaved(list: List<SavedServer>) {
        saved = list
        SavedServers.save(prefs, list)
    }

    fun refreshDiscovery() = discovery.probeNow()

    fun savedPassword(server: SavedServer): String? = passwords.get(server.id)

    fun forget(server: SavedServer) {
        storeSaved(saved.filter { it.id != server.id })
        passwords.set(server.id, "")
    }

    /** The beacon of the connected station, if it is being heard. */
    val targetBeacon: ServerBeacon?
        get() {
            val t = target ?: return null
            return discovered.firstOrNull { it.host == t.host && it.port == t.port }
        }

    // MARK: - Connection

    fun connect(server: SavedServer, password: String) {
        disconnect()
        passwords.set(server.id, password)
        storeSaved(listOf(server) + saved.filter { it.id != server.id })
        prefs.edit().putString("lastServer", server.id).apply()
        target = server
        phase = Phase.Connecting
        serverName = server.name
        lastError = null
        state = RadioState()
        note("connecting to ${server.name} at ${server.host}")

        val cat = CatClient(server.host, server.port, password)
        val allowed = txAllowedFlag
        cat.isTransmitAllowed = { allowed.get() }
        cat.callback = onMain
        cat.onStatus = { catStatus(it) }
        cat.onState = { stateChanged(it) }
        cat.onNote = { note(it) }
        this.cat = cat
        cat.start()
        startLink(password)
        wifiLock.acquire()
        RigService.start(context)
    }

    /** Lets go of the transmitter, puts the radio's mic back, and closes. */
    fun disconnect() {
        if (target == null) return
        unkey()
        setPhoneKey(false)
        cwDisarmedReason = null
        if (phoneMic) switchPhoneMic(false)
        cat?.stop()
        cat = null
        link?.stop()
        link = null
        audio.stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
        target = null
        phase = Phase.Idle
        state = RadioState()
        audioStats = AudioLinkStats()
        micGate.set(false)
        sendingMic = false
        pendingFreq = null
        if (wifiLock.isHeld) wifiLock.release()
        prefs.edit().remove("lastServer").apply()
        RigService.stop(context)
        if (!inForeground) stopDiscovery()
    }

    private fun catStatus(s: CatClient.Status) {
        when (s) {
            is CatClient.Status.Connecting -> if (phase != Phase.Connected) phase = Phase.Connecting
            is CatClient.Status.Connected -> {
                phase = Phase.Connected
                serverName = s.server
                val t = target
                if (t != null && saved.any { it.id == t.id && it.name != s.server }) {
                    storeSaved(saved.map { if (it.id == t.id) it.copy(name = s.server) else it })
                }
            }
            is CatClient.Status.Failed -> {
                note(s.why)
                // A wrong password won't get better by retrying, and the
                // station locks out an address after five.
                if (s.why.contains("password") || s.why.contains("not a RigHelm")) {
                    disconnect()
                    lastError = s.why
                } else {
                    phase = Phase.Failed(s.why)
                }
            }
            is CatClient.Status.Stopped -> {}
        }
        if (target != null) RigService.refresh(context)
    }

    private fun stateChanged(s: RadioState) {
        if (cat == null) return
        state = s
        pendingFreq?.let { if (System.currentTimeMillis() > it.until) pendingFreq = null }
        updateMicGate()
    }

    private fun startLink(password: String) {
        val server = target ?: return
        val link = AudioLink(server.host, server.port, password)
        link.callback = onMain
        val buffer = audio.buffer
        val listening = this.listening
        link.onAudio = { samples, lost ->
            // Short losses become silence; longer ones are only faded across.
            if (lost > 0) buffer.writeGap(if (lost <= 5) lost * samples.size else 0)
            buffer.write(samples, if (listening.get()) 1f else 0f)
        }
        link.onStats = { s ->
            if (this.link === link) {
                val rateChanged = s.connected && s.rate != audio.rate
                audioStats = s
                audioDropouts = buffer.dropouts
                if (rateChanged || (s.connected && !audio.running)) restartAudio()
            }
        }
        val gate = micGate
        audio.onMicFrame = { frame -> if (gate.get()) link.sendMic(frame) }
        this.link = link
        link.start()
    }

    /** (Re)start the phone's audio for the current settings. */
    fun restartAudio() {
        val t = target ?: return
        // Neither listening, talking nor a sidetone: no audio at all.
        if (!needsAudio) {
            link?.stop()
            link = null
            audio.stop()
            audioStats = AudioLinkStats()
            audioManager.abandonAudioFocusRequest(focusRequest)
            return
        }
        if (link == null) {
            startLink(passwords.get(t.id) ?: "")
            return
        }
        if (receiveAudio) {
            audio.duck = if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else 0f
        }
        try {
            audio.start(audioStats.rate, phoneMic && inForeground)
            audio.volume = volume
        } catch (e: Exception) {
            note("audio: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val needsAudio: Boolean get() = receiveAudio || phoneMic || (cwArmed && cwSidetone)

    /** Start or stop the audio when the sidetone alone needs it. */
    private fun restartAudioIfNeeded() {
        if (needsAudio != audio.running || (link == null && needsAudio)) restartAudio()
    }

    /** A phone call or another player took the speaker: go quiet, and never stay keyed. */
    private fun focusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> audio.duck = 1f
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                audio.duck = 0f
                unkey()
            }
        }
    }

    // MARK: - Commands

    fun send(command: String) {
        cat?.send(command)
    }

    val isConnected: Boolean get() = phase == Phase.Connected

    // MARK: Tuning

    /** The receive frequency to show: the knob's while it turns. */
    val displayRxFreq: Int
        get() {
            val p = pendingFreq
            if (p != null && p.vfoB == state.rxOnB) return p.hz
            return state.rxFreq
        }

    fun tune(steps: Int) {
        if (steps == 0 || state.rxFreq <= 0) return
        val base = displayRxFreq
        val step = tuneStep
        // Snap to the step grid first, as a radio's dial does.
        var hz = (base / step) * step + steps * step
        if (steps < 0 && base % step != 0) hz += step
        setRxFrequency(hz)
    }

    fun setRxFrequency(hz: Int) {
        val f = hz.coerceIn(Frequency.MIN, Frequency.MAX)
        pendingFreq = PendingFreq(f, state.rxOnB, System.currentTimeMillis() + 800)
        // At most every 60 ms while turning; the last value always goes.
        if (System.currentTimeMillis() - lastFreqSend > 60) {
            flushFrequency()
        } else if (!freqSendScheduled) {
            freqSendScheduled = true
            main.postDelayed({
                freqSendScheduled = false
                flushFrequency()
            }, 60)
        }
    }

    private fun flushFrequency() {
        val p = pendingFreq ?: return
        lastFreqSend = System.currentTimeMillis()
        pendingFreq = p.copy(until = System.currentTimeMillis() + 800)
        send(Frequency.setCommand(p.vfoB, p.hz))
    }

    fun setMode(m: Mode) = send(m.setCommand)

    fun toggleSplit() {
        val txB = if (state.split) state.rxOnB else !state.rxOnB
        send(if (txB) "FT3;" else "FT2;")
    }

    // MARK: Transmit

    val canTransmit: Boolean get() = transmitAllowed && isConnected

    /** Why transmit can't happen right now, for the operator. */
    val transmitBlockReason: String?
        get() = when {
            !transmitAllowed -> "Turn on “Allow transmit from this phone” in Settings."
            !isConnected -> "Not connected to the station."
            targetBeacon?.auth == false -> "This station has no password, so it is receive-only."
            else -> null
        }

    fun pttDown() {
        // With MOX latched, the button lets go.
        if (latched) {
            unkey()
            return
        }
        if (!canTransmit) {
            showLockedHint = true
            return
        }
        if (!state.transmitting) {
            send("TX1;")
            txStartedAt = System.currentTimeMillis()
            updateMicGate()
        }
    }

    fun pttUp() {
        if (!latched) unkey()
    }

    /** MOX: stay keyed after letting go of the button. */
    fun toggleLatch() {
        if (latched || state.transmitting && cat?.keyedByUs == true) {
            unkey()
        } else {
            if (!canTransmit) {
                showLockedHint = true
                return
            }
            latched = true
            pttDown()
        }
    }

    fun unkey() {
        val keyed = cat?.keyedByUs == true || latched || txStartedAt != null
        latched = false
        txStartedAt = null
        micGate.set(false)
        sendingMic = false
        if (keyed) cat?.send("TX0;")
    }

    /** Start the antenna tuner (it transmits a carrier). */
    fun startTune() {
        if (!canTransmit) {
            showLockedHint = true
            return
        }
        send("AC002;")
    }

    fun switchPhoneMic(on: Boolean) {
        if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionWanted = true
            return
        }
        if (on == phoneMic) return
        unkey()
        phoneMic = on
        applyMicSel()
        restartAudio()
    }

    /** The answer to the microphone permission request the UI made. */
    fun micPermissionResult(granted: Boolean) {
        micPermissionWanted = false
        if (granted) switchPhoneMic(true) else micPermissionDenied = true
    }

    /**
     * Point the radio's AM, FM and SSB MIC SEL menus at USB (phone mic) or
     * back to what they were (as RigHelm's COMPUTER MIC switch does).
     */
    private fun applyMicSel() {
        for (menu in listOf(53, 85, 103)) {
            val now = state.micSel(menu)
            val want = if (phoneMic) {
                if (now != null && now != 2) savedMicSel[menu] = now
                2
            } else {
                savedMicSel[menu]?.takeIf { it != 2 } ?: 0
            }
            if (now != want) send("EX%03d%d;".fmt(menu, want))
        }
        if (!phoneMic) savedMicSel.clear()
    }

    private fun updateMicGate() {
        // Open as soon as this phone keys, not when the radio's TX state comes
        // back, so the first words aren't cut off. The server checks again.
        val keying = state.transmitting || txStartedAt != null
        val on = phoneMic && transmitAllowed && cat?.keyedByUs == true && keying && state.txMode?.isVoice == true
        micGate.set(on)
        if (sendingMic != on) sendingMic = on
    }

    // MARK: CW key

    /**
     * Switch between the radio's own key (default) and keying from this
     * phone. Arming puts the radio in CW with break-in, so key-down
     * transmits, and opens a `KEY` session to the station.
     */
    fun setPhoneKey(on: Boolean) {
        keyer?.stop()
        keyer = null
        sentPaddles = false to false
        audio.sidetone.key(false)
        if (!on) {
            if (cwArmed) {
                cwArmed = false
                cwStatus = CwStatus()
                audio.sidetone.setEnabled(false)
                restartAudioIfNeeded()
            }
            return
        }
        val server = target
        if (!canTransmit || server == null) {
            cwArmed = false
            showLockedHint = true
            return
        }
        cwDisarmedReason = null
        if (state.txMode?.isCW != true) send("MD03;")
        if (!state.breakIn) send("BI1;")
        val password = passwords.get(server.id) ?: ""
        val tone = audio.sidetone
        keyer = CwKeyer(keyerMode, if (state.keySpeed >= 4) state.keySpeed else 20, { tone.key(it) }) {
            RemoteKeyLine(server.host, server.port, password)
        }
        cwArmed = true
        cwStatus = CwStatus()
        tone.setEnabled(cwSidetone)
        restartAudioIfNeeded()
        note("CW key armed (${keyerMode.label})")
    }

    private fun cwDisarm(why: String) {
        if (!cwArmed) return
        setPhoneKey(false)
        cwDisarmedReason = why
        note("CW key back to the radio's key: $why")
    }

    /** Paddle (or straight key) state from the key pad. */
    fun cwPaddles(dit: Boolean, dah: Boolean) {
        val k = keyer ?: return
        if ((dit to dah) == sentPaddles) return
        sentPaddles = dit to dah
        k.input(KeyerInput.Paddles(dit, dah))
    }

    fun cwSendText(text: String) {
        val k = keyer ?: return
        if (text.isBlank()) return
        k.input(KeyerInput.Text(text))
    }

    fun cwStop() {
        keyer?.input(KeyerInput.Stop)
    }

    /**
     * Keep the phone key's status fresh and let go when it should no
     * longer be live (the same rules as RigHelm's computer key).
     */
    private fun cwTick() {
        val k = keyer ?: return
        val st = k.status
        if (st != cwStatus) cwStatus = st
        if (state.keySpeed >= 4) k.setWPM(state.keySpeed)
        val settled = System.nanoTime() / 1e9 - k.armedAt > 3
        val error = st.error
        when {
            error != null -> cwDisarm(error)
            !isConnected -> cwDisarm("the station disconnected")
            settled && state.txMode?.isCW != true -> cwDisarm("the radio left CW mode")
            k.idleFor > CwKeyer.IDLE_DISARM -> cwDisarm("not used for 10 minutes")
        }
    }

    // MARK: - Housekeeping

    private fun tick() {
        val level = audio.micLevelDb
        if (abs(level - micLevelDb) > 0.5f) micLevelDb = level
        updateMicGate()
        cwTick()
        val started = txStartedAt
        val now = System.currentTimeMillis()
        if (started != null && now - started > txTimeoutSeconds * 1000L) {
            unkey()
            note("transmit time-out: let go after ${txTimeoutSeconds / 60} min")
        }
        // Keyed elsewhere (or the radio let go): forget our own TX timer.
        val t = txStartedAt
        if (t != null && !state.transmitting && now - t > 3000 && cat?.keyedByUs != true) {
            txStartedAt = null
            latched = false
        }
    }

    /**
     * The app has gone to the background: never leave the radio keyed by
     * a phone in a pocket.
     */
    fun enteredBackground() {
        inForeground = false
        unkey()
        cwDisarm("the app went to the background")
        if (target == null) {
            stopDiscovery()
        } else if (phoneMic) {
            // Android silences a background microphone anyway; keep playing.
            restartAudio()
        }
    }

    fun enteredForeground() {
        inForeground = true
        startDiscovery()
        discovery.probeNow()
        if (target != null && (!audio.running || phoneMic != audio.micRunning)) restartAudio()
    }

    fun note(text: String) {
        val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        notes = (notes + "$stamp  $text").takeLast(200)
    }
}
