package com.sonora.player.visualizer

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time audio analysis for the Now Playing visualizer.
 *
 * The player's audio pipeline passes every PCM buffer through [sink] (see [SonoraRenderersFactory]).
 * While the visualizer is on screen ([setActive]) the sink runs a small FFT and stores spectrum
 * bands plus a waveform snippet in a short ring buffer. Each entry is stamped with the time its
 * audio is expected to be heard, so the UI can read the entry that matches what is playing now
 * instead of what the audio buffer is about to play. When the visualizer is hidden the sink does
 * nothing, so it costs no CPU.
 *
 * No microphone permission is needed: the data comes from the decoded audio, not the mic.
 */
object AudioTap {
    const val BAND_COUNT = 48
    const val WAVE_POINTS = 96

    private const val RING = 64
    private const val OUTPUT_LATENCY_NS = 60_000_000L
    private const val STALE_NS = 250_000_000L

    @Volatile
    private var active = false

    private val lock = Any()
    private val ringBands = Array(RING) { FloatArray(BAND_COUNT) }
    private val ringWave = Array(RING) { FloatArray(WAVE_POINTS) }
    private val ringTime = LongArray(RING)
    private var head = 0
    private var count = 0

    /** Hooked into the player by [SonoraRenderersFactory]. */
    val sink: TeeAudioProcessor.AudioBufferSink = SpectrumSink()

    /** Turn analysis on or off. Called by the UI when the visualizer is visible or hidden. */
    fun setActive(on: Boolean) {
        active = on
        if (!on) clear()
    }

    internal fun isActive(): Boolean = active

    internal fun clear() {
        synchronized(lock) {
            head = 0
            count = 0
        }
    }

    internal fun publish(bands: FloatArray, wave: FloatArray, stampNs: Long) {
        synchronized(lock) {
            bands.copyInto(ringBands[head])
            wave.copyInto(ringWave[head])
            ringTime[head] = stampNs
            head = (head + 1) % RING
            if (count < RING) count++
        }
    }

    /**
     * Copies the spectrum bands (0..1) and waveform (-1..1) that match the audio being heard now.
     * Returns false when there is nothing current (paused, seeking, silence between tracks).
     */
    fun read(bands: FloatArray, wave: FloatArray): Boolean {
        val target = System.nanoTime() - OUTPUT_LATENCY_NS
        synchronized(lock) {
            for (k in 0 until count) {
                val i = (head - 1 - k + RING) % RING
                val t = ringTime[i]
                if (t <= target) {
                    if (target - t > STALE_NS) return false
                    ringBands[i].copyInto(bands)
                    ringWave[i].copyInto(wave)
                    return true
                }
            }
        }
        return false
    }
}

private const val FFT_SIZE = 2048
private const val HOP = 1024
private const val WAVE_STRIDE = 10
private const val MIN_HZ = 40f
private const val MAX_HZ = 16000f
private const val FLOOR_DB = -72f
private const val CEIL_DB = -8f

/** Receives PCM from the player's audio pipeline (audio thread) and turns it into display data. */
private class SpectrumSink : TeeAudioProcessor.AudioBufferSink {
    private var sampleRate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    private val samples = FloatArray(FFT_SIZE)
    private var writePos = 0
    private var filled = 0
    private var sinceHop = 0

    private val fft = Fft(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val mag = FloatArray(FFT_SIZE / 2)

    private val bands = FloatArray(AudioTap.BAND_COUNT)
    private val wave = FloatArray(AudioTap.WAVE_POINTS)
    private val bandLo = FloatArray(AudioTap.BAND_COUNT)
    private val bandHi = FloatArray(AudioTap.BAND_COUNT)
    private val bandTilt = FloatArray(AudioTap.BAND_COUNT)
    private var waveAgc = 0.25f

    // Virtual clock: the time at which the next sample will be heard.
    private var virtualNs = 0.0
    private var nsPerFrame = 1e9 / 44100.0

    init {
        configureBands()
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = if (sampleRateHz > 0) sampleRateHz else 44100
        channels = max(1, channelCount)
        this.encoding = encoding
        nsPerFrame = 1e9 / sampleRate
        writePos = 0
        filled = 0
        sinceHop = 0
        virtualNs = 0.0
        samples.fill(0f)
        configureBands()
        AudioTap.clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!AudioTap.isActive()) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val ch = channels
        val now = System.nanoTime()
        // After a pause or underrun the virtual clock falls behind real time: catch up.
        if (virtualNs < now) virtualNs = now.toDouble()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frames = b.remaining() / (2 * ch)
                for (f in 0 until frames) {
                    var sum = 0
                    for (c in 0 until ch) sum += b.getShort().toInt()
                    push(sum / (32768f * ch))
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frames = b.remaining() / (4 * ch)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += b.getFloat()
                    push(sum / ch)
                }
            }
            C.ENCODING_PCM_32BIT -> {
                val frames = b.remaining() / (4 * ch)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += b.getInt() / 2147483648f
                    push(sum / ch)
                }
            }
            C.ENCODING_PCM_24BIT -> {
                val frames = b.remaining() / (3 * ch)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) {
                        val lo = b.get().toInt() and 0xFF
                        val mid = b.get().toInt() and 0xFF
                        val hi = b.get().toInt() // signed
                        sum += ((hi shl 16) or (mid shl 8) or lo) / 8388608f
                    }
                    push(sum / ch)
                }
            }
            C.ENCODING_PCM_8BIT -> {
                val frames = b.remaining() / ch
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += ((b.get().toInt() and 0xFF) - 128) / 128f
                    push(sum / ch)
                }
            }
            else -> return
        }
    }

    private fun push(v: Float) {
        samples[writePos] = v
        writePos++
        if (writePos == FFT_SIZE) writePos = 0
        if (filled < FFT_SIZE) filled++
        virtualNs += nsPerFrame
        sinceHop++
        if (sinceHop >= HOP) {
            sinceHop = 0
            if (filled >= FFT_SIZE) analyze(virtualNs.toLong())
        }
    }

    private fun analyze(stampNs: Long) {
        val n = FFT_SIZE
        var idx = writePos // oldest sample
        for (i in 0 until n) {
            re[i] = samples[idx] * window[i]
            im[i] = 0f
            idx++
            if (idx == n) idx = 0
        }
        fft.transform(re, im)

        // 1.0 = a full-scale sine wave.
        val scale = 4f / n
        for (k in 0 until n / 2) {
            mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        }

        for (b in 0 until AudioTap.BAND_COUNT) {
            val lo = bandLo[b]
            val hi = bandHi[b]
            val v: Float
            if (hi - lo < 1f) {
                // Band narrower than one FFT bin: interpolate between neighbours.
                val c = (lo + hi) * 0.5f
                val i0 = c.toInt().coerceIn(0, mag.size - 2)
                val frac = (c - i0).coerceIn(0f, 1f)
                v = mag[i0] * (1f - frac) + mag[i0 + 1] * frac
            } else {
                val i0 = ceil(lo).toInt().coerceIn(1, mag.size - 1)
                val i1 = floor(hi).toInt().coerceIn(i0, mag.size - 1)
                var sum = 0f
                for (k in i0..i1) sum += mag[k] * mag[k]
                v = sqrt(sum / (i1 - i0 + 1))
            }
            val db = 20f * log10(max(v, 1e-6f)) + bandTilt[b]
            bands[b] = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0f, 1f)
        }

        // Waveform: the newest WAVE_POINTS * WAVE_STRIDE samples, keeping the strongest sample
        // of each group, then auto-gained so quiet tracks still draw clearly.
        val span = AudioTap.WAVE_POINTS * WAVE_STRIDE
        var pos = (writePos + n - span) % n
        var peak = 0f
        for (p in 0 until AudioTap.WAVE_POINTS) {
            var best = 0f
            for (j in 0 until WAVE_STRIDE) {
                val s = samples[pos]
                if (abs(s) > abs(best)) best = s
                pos++
                if (pos == n) pos = 0
            }
            wave[p] = best
            peak = max(peak, abs(best))
        }
        waveAgc = max(peak, waveAgc * 0.985f)
        val gain = 1f / max(waveAgc, 0.15f)
        for (p in 0 until AudioTap.WAVE_POINTS) {
            wave[p] = (wave[p] * gain).coerceIn(-1f, 1f)
        }

        AudioTap.publish(bands, wave, stampNs)
    }

    /** Log-spaced bands; recomputed whenever the sample rate changes. */
    private fun configureBands() {
        val binHz = sampleRate.toFloat() / FFT_SIZE
        val maxHz = min(MAX_HZ, sampleRate * 0.45f)
        val ratio = maxHz / MIN_HZ
        for (b in 0 until AudioTap.BAND_COUNT) {
            val f0 = MIN_HZ * ratio.pow(b.toFloat() / AudioTap.BAND_COUNT)
            val f1 = MIN_HZ * ratio.pow((b + 1).toFloat() / AudioTap.BAND_COUNT)
            bandLo[b] = f0 / binHz
            bandHi[b] = f1 / binHz
            val center = sqrt(f0 * f1)
            // Music loses energy towards the treble; lift it so the right side of the display moves too.
            bandTilt[b] = if (center > 500f) 3f * log2(center / 500f) else 0f
        }
    }
}

/** Plain radix-2 FFT (in place). */
private class Fft(private val n: Int) {
    private val cosTable = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val reversed = IntArray(n)

    init {
        val levels = n.countTrailingZeroBits()
        for (i in 0 until n) {
            var x = i
            var r = 0
            for (bit in 0 until levels) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            reversed[i] = r
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tpre = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tpim = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tpre
                    im[l] = im[j] - tpim
                    re[j] += tpre
                    im[j] += tpim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
