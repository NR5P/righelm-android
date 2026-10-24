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
import kotlin.math.floor

/**
 * Holds incoming radio audio long enough to ride out Wi-Fi delivering
 * packets in clumps, and keeps latency bounded when they arrive faster
 * than they play.
 */
class JitterBuffer {
    private val ring = FloatArray(48_000 * 2)
    private var readIndex = 0
    private var count = 0
    private var priming = true
    /** Start (and restart after running dry) once this much is waiting. */
    private var target = 4_800
    /** More than this waiting: skip ahead to [target]. */
    private var ceiling = 14_400

    @Synchronized
    fun configure(rate: Int) {
        target = rate / 5 // 200 ms
        ceiling = rate * 3 / 5 // 600 ms
        readIndex = 0
        count = 0
        priming = true
    }

    @Synchronized
    fun write(samples: ShortArray, gain: Float) {
        val scale = gain / 32768f
        val cap = ring.size
        for (s in samples) {
            ring[(readIndex + count) % cap] = s * scale
            if (count < cap) count += 1 else readIndex = (readIndex + 1) % cap
        }
        if (count > ceiling) {
            val drop = count - target
            readIndex = (readIndex + drop) % cap
            count -= drop
        }
    }

    fun writeSilence(n: Int) = write(ShortArray(n), 0f)

    @Synchronized
    fun read(out: FloatArray, n: Int) {
        if (priming) {
            if (count < target) {
                out.fill(0f, 0, n)
                return
            }
            priming = false
        }
        val cap = ring.size
        val take = minOf(n, count)
        for (i in 0 until take) out[i] = ring[(readIndex + i) % cap]
        readIndex = (readIndex + take) % cap
        count -= take
        if (take < n) {
            out.fill(0f, take, n)
            priming = true
        }
    }
}

/**
 * Plays the radio's audio and, when asked, captures the phone's
 * microphone as 20 ms frames at the stream's sample rate.
 */
class AudioIO {
    val buffer = JitterBuffer()
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
