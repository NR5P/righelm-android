package com.righelm.android.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import com.righelm.core.RemoteProtocol
import com.righelm.core.levelDbfs
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Playout buffer for radio audio, which Wi-Fi often delivers in clumps:
 * several 20 ms packets at once, 100 ms or more apart. It waits until it
 * holds [target] before playing, and when it runs dry anyway it fades out
 * (no click), deepens itself, and fades back in.
 *
 * The station's sound card and the phone never run at quite the same rate
 * (a ThinkPad station measured 0.4 to 0.9 % slow). Without allowing for it
 * a slow station drains any buffer, however deep, and drops out every half
 * minute. So playback speed is the measured rate difference (samples
 * received against samples played over the last half minute or so: long
 * enough for clumps to average out, short enough to follow a station whose
 * USB audio loses more at some times than others) plus a gentle pull
 * toward [target], never more than 3 % off.
 */
class JitterBuffer {
    private var ring = FloatArray(19_200)
    private var readIndex = 0
    private var count = 0
    private var playing = false
    /** Depth to build up before (re)starting, and to hold while playing. */
    private var target = 2_400
    private var minTarget = 2_400
    private var maxTarget = 9_600
    private var rate = 0
    /** Depth smoothed over about 2 s, so a clump doesn't swing the speed. */
    private var smoothed = 0.0
    private var smoothing = 1.0 / 48_000
    /**
     * Measured rate difference between the station and the phone, and the
     * counts behind it: samples arriving and samples played, both only
     * while playing (prebuffering would count as a fast station), both
     * fading by 3 % a second. The difference is kept across restarts.
     */
    private var drift = 0.0
    private var received = 0.0
    private var ticks = 0.0
    private var sinceFade = 0
    /** Playback speed now, and the position between the next two samples. */
    private var speed = 1.0
    private var frac = 0.0
    /** Samples played since the last dropout, to give back depth that a long clean spell shows isn't needed. */
    private var sinceDry = 0
    private var last = 0f
    private var gain = 0f
    private var gainStep = 1f / 240
    /** Fade length around gaps, and samples of the next write still to fade in. */
    private var fadeLength = 240
    private var fadeIn = 0
    /** Times playback ran dry since the stream started. */
    private var dryCount = 0

    val dropouts: Int
        @Synchronized get() = dryCount

    @Synchronized
    fun configure(rate: Int) {
        // An audio restart (route change, interruption) keeps the depth and
        // drift already learned: starting over means the same dropouts
        // again until it relearns.
        if (rate != this.rate) {
            this.rate = rate
            target = rate / 10 // 100 ms to start
            drift = 0.0
            dryCount = 0
        }
        // Start from the last difference, worth 10 s of evidence.
        ticks = rate * 10.0
        received = ticks * (1 + drift)
        sinceFade = 0
        minTarget = rate / 10
        maxTarget = rate * 2 / 5 // may grow to 400 ms
        ring = FloatArray(maxTarget * 2)
        smoothing = 1 / (2.0 * rate)
        fadeLength = maxOf(rate / 100, 1) // 10 ms
        gainStep = 1f / fadeLength
        readIndex = 0
        count = 0
        playing = false
        smoothed = target.toDouble()
        speed = 1.0
        frac = 0.0
        sinceDry = 0
        last = 0f
        gain = 0f
        fadeIn = 0
    }

    @Synchronized
    fun write(samples: ShortArray, gain: Float) {
        val scale = gain / 32768f
        for (s in samples) {
            var v = s * scale
            if (fadeIn > 0) {
                v *= (fadeLength - fadeIn).toFloat() / fadeLength
                fadeIn -= 1
            }
            append(v)
        }
    }

    /**
     * Stand in for lost packets: silence that keeps the timing, with the
     * audio either side faded so the gap doesn't click. With [n] 0 the two
     * sides are just faded together, for losses too long to fill.
     */
    @Synchronized
    fun writeGap(n: Int) {
        val cap = ring.size
        val k = minOf(fadeLength, count)
        for (i in 0 until k) {
            ring[(readIndex + count - k + i) % cap] *= (k - i).toFloat() / (k + 1)
        }
        repeat(n) { append(0f) }
        fadeIn = fadeLength
    }

    @Synchronized
    fun read(out: FloatArray, n: Int) {
        for (i in 0 until n) out[i] = next()
    }

    // All below run with the lock held.

    /** Queue one sample; past capacity the oldest is dropped. */
    private fun append(v: Float) {
        if (playing) received += 1
        val cap = ring.size
        if (count == cap) {
            readIndex = (readIndex + 1) % cap
            count -= 1
        }
        ring[(readIndex + count) % cap] = v
        count += 1
    }

    /** The next sample to play. */
    private fun next(): Float {
        if (!playing && count >= target) {
            playing = true
            frac = 0.0
        }
        if (playing) {
            // Two samples to interpolate between.
            if (count >= 2) {
                val a = ring[readIndex]
                val b = ring[(readIndex + 1) % ring.size]
                val v = a + (b - a) * frac.toFloat()
                steer()
                frac += speed
                while (frac >= 1 && count >= 2) {
                    readIndex = (readIndex + 1) % ring.size
                    count -= 1
                    frac -= 1
                }
                gain = minOf(gain + gainStep, 1f)
                last = v
                return v * gain
            }
            // Ran dry: rebuild a deeper buffer before playing again.
            playing = false
            dryCount += 1
            sinceDry = 0
            target = minOf(target + target / 2, maxTarget)
        }
        gain = maxOf(gain - gainStep, 0f)
        return last * gain
    }

    /** Set the playback speed: the clocks' measured difference, plus up to 1 % more to bring the depth back to [target]. */
    private fun steer() {
        ticks += 1
        sinceFade += 1
        if (sinceFade >= rate) {
            sinceFade = 0
            received *= 0.97
            ticks *= 0.97
        }
        smoothed += (count - smoothed) * smoothing
        drift = ((received - ticks) / ticks).coerceIn(-MAX_SKEW, MAX_SKEW)
        // Depth error in units of 100 ms, so it behaves the same at any target.
        val err = (smoothed - target) / (rate / 10)
        val pull = (err * 0.005).coerceIn(-0.01, 0.01)
        speed = 1 + (drift + pull).coerceIn(-MAX_SKEW, MAX_SKEW)
        // Five minutes without a dropout: try 10 % less latency. Sooner
        // trims it below what the next Wi-Fi gap needs.
        sinceDry += 1
        if (sinceDry >= rate * 300) {
            sinceDry = 0
            target = maxOf(target - target / 10, minTarget)
        }
    }

    private companion object {
        /** Most the playback speed may move from 1. */
        const val MAX_SKEW = 0.03
    }
}

/**
 * A CW sidetone mixed into playback while the phone key is down: over a
 * network the radio's own sidetone comes back too late to send by.
 */
class Sidetone {
    private var enabled = false
    private var down = false
    private var hz = 700f
    private var volume = 0.3f
    private var phase = 0f
    private var amp = 0f

    @Synchronized
    fun setEnabled(on: Boolean) {
        enabled = on
    }

    /** From the keyer thread. */
    @Synchronized
    fun key(down: Boolean) {
        this.down = down
    }

    /** On the playback thread: add the tone to [out]. */
    @Synchronized
    fun mix(out: FloatArray, n: Int, rate: Float) {
        val target = if (enabled && down) 1f else 0f
        if (target == 0f && amp == 0f) return
        val step = 2 * PI.toFloat() * hz / rate
        val ramp = 1 / (rate * 0.004f) // 4 ms rise and fall: no clicks
        for (i in 0 until n) {
            amp = if (amp < target) minOf(amp + ramp, target) else maxOf(amp - ramp, target)
            phase += step
            if (phase > 2 * PI.toFloat()) phase -= 2 * PI.toFloat()
            out[i] += sin(phase) * amp * volume
        }
    }
}

/**
 * Plays the radio's audio and, when asked, captures the phone's
 * microphone as 20 ms frames at the stream's sample rate.
 */
class AudioIO {
    val buffer = JitterBuffer()
    val sidetone = Sidetone()
    var rate = RemoteProtocol.AUDIO_RATE
        private set
    @Volatile var running = false
        private set
    @Volatile var micRunning = false
        private set

    /** Called on the recording thread with each microphone frame. */
    @Volatile var onMicFrame: ((ShortArray) -> Unit)? = null
    @Volatile var volume = 1f
    /** Set to 0 while another app (a phone call) has the audio. */
    @Volatile var duck = 1f
    @Volatile var micGain = 1f
    @Volatile var micLevelDb = -100f
        private set

    private var track: AudioTrack? = null
    private var record: Pair<AudioRecord, Int>? = null
    private var threads = listOf<Thread>()
    @Volatile private var generation = 0

    fun start(rate: Int, mic: Boolean) {
        stop()
        this.rate = rate
        buffer.configure(rate)
        val gen = ++generation
        val t = makeTrack(rate)
        track = t
        t.play()
        val play = Thread({ playLoop(t, gen) }, "righelm-play")
        var started = listOf(play)
        if (mic) {
            val r = try {
                makeRecord(rate)
            } catch (e: Exception) {
                stop()
                throw e
            }
            record = r
            r.first.startRecording()
            started = started + Thread({ recordLoop(r.first, r.second, gen) }, "righelm-mic")
            micRunning = true
        }
        threads = started
        running = true
        started.forEach { it.start() }
    }

    fun stop() {
        generation += 1
        running = false
        micRunning = false
        for (th in threads) th.join(300)
        threads = emptyList()
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
        record?.let {
            try { it.first.stop() } catch (_: Exception) {}
            it.first.release()
        }
        record = null
        micLevelDb = -100f
    }

    private fun makeTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            // About 40 ms: the jitter buffer does the real buffering.
            .setBufferSizeInBytes(maxOf(min, rate / 25 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun playLoop(t: AudioTrack, gen: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = FloatArray(rate / 100) // 10 ms
        while (generation == gen) {
            buffer.read(chunk, chunk.size)
            sidetone.mix(chunk, chunk.size, rate.toFloat())
            val g = volume * duck
            for (i in chunk.indices) chunk[i] = (chunk[i] * g).coerceIn(-1f, 1f)
            if (t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) < 0) break
        }
    }

    /** The recorder and the rate it really runs at (some phones can't record at 24 kHz). */
    @SuppressLint("MissingPermission") // asked for before the phone mic is switched on
    private fun makeRecord(rate: Int): Pair<AudioRecord, Int> {
        for (r in listOf(rate, 48_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, r, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, r / 10 * 2),
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to r
            rec.release()
        }
        throw IllegalStateException("no microphone available")
    }

    private fun recordLoop(rec: AudioRecord, srcRate: Int, gen: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frameLen = RemoteProtocol.frameLength(rate)
        val input = ShortArray(srcRate / 100)
        val asFloat = FloatArray(input.size)
        val resampler = if (srcRate != rate) Resampler(srcRate, rate) else null
        val pending = ShortArray(frameLen)
        var filled = 0
        while (generation == gen) {
            val n = rec.read(input, 0, input.size)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            val gain = micGain
            fun emit(v: Float) {
                pending[filled++] = (v * gain * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                if (filled == frameLen) {
                    val frame = pending.copyOf()
                    filled = 0
                    micLevelDb = levelDbfs(frame)
                    onMicFrame?.invoke(frame)
                }
            }
            for (i in 0 until n) asFloat[i] = input[i] / 32768f
            if (resampler == null) {
                for (i in 0 until n) emit(asFloat[i])
            } else {
                resampler.process(asFloat, n, ::emit)
            }
        }
    }
}

/** Linear-interpolation sample-rate conversion, carried across blocks. */
private class Resampler(from: Int, to: Int) {
    private val step = from.toDouble() / to
    /** Position of the next output in the coming block; -1 is the previous block's last sample. */
    private var pos = 0.0
    private var prev = 0f

    fun process(input: FloatArray, n: Int, out: (Float) -> Unit) {
        var t = pos
        while (t <= n - 1) {
            val i = floor(t).toInt()
            val f = (t - i).toFloat()
            val a = if (i < 0) prev else input[i]
            val b = if (i + 1 < n) input[i + 1] else a
            out(a + (b - a) * f)
            t += step
        }
        pos = t - n
        prev = input[n - 1]
    }
}
