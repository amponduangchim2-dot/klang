@file:OptIn(UnstableApi::class)

package app.klang.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 16-bit PCM processor: preamp + 10 peaking biquads (Winamp bands), stereo balance,
 * a peak limiter (so EQ boosts never hard-clip), TPDF dither back to 16 bit,
 * and a small FFT that feeds [AudioEngine.levels] / [AudioEngine.wave] for the visualizer.
 */
class KlangAudioProcessor : BaseAudioProcessor() {

    private var channels = 2
    private var sampleRate = 44_100

    private val n = AudioEngine.BAND_COUNT
    private val b0 = FloatArray(n)
    private val b1 = FloatArray(n)
    private val b2 = FloatArray(n)
    private val a1 = FloatArray(n)
    private val a2 = FloatArray(n)
    private val active = BooleanArray(n)
    private var anyBand = false
    private var preGain = 1f
    private var eqOn = true
    private var coefVersion = -1

    // limiter: instant attack, ~150 ms release, ceiling just under full scale
    private val ceiling = 0.966f            // -0.3 dBFS
    private var limGain = 1f
    private var release = 0f
    private val frameBuf = FloatArray(8)
    private var seed = 0x2545F491

    /** Gain reduction applied most recently, in dB (0 = limiter idle). For tests and meters. */
    var lastReductionDb = 0f
        private set

    private var z1 = Array(2) { FloatArray(n) }
    private var z2 = Array(2) { FloatArray(n) }

    // analysis
    private val fftSize = 1024
    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat() }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount < 1 || inputAudioFormat.channelCount > 8) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        z1 = Array(channels) { FloatArray(n) }
        z2 = Array(channels) { FloatArray(n) }
        coefVersion = -1
        ringPos = 0
        limGain = 1f
        release = 1f - kotlin.math.exp(-1.0 / (0.15 * sampleRate)).toFloat()
    }

    override fun onReset() {
        z1 = Array(2) { FloatArray(n) }
        z2 = Array(2) { FloatArray(n) }
        coefVersion = -1
    }

    private fun dbToLin(db: Float): Float = 10f.pow(db / 20f)

    private fun computeCoefs() {
        coefVersion = AudioEngine.version
        eqOn = AudioEngine.eqEnabled
        preGain = if (eqOn) dbToLin(AudioEngine.preampDb) else 1f
        anyBand = false
        val q = 1.41
        for (b in 0 until n) {
            val g = AudioEngine.gainDb(b)
            val f = AudioEngine.FREQS[b].toDouble()
            if (!eqOn || g == 0f || f >= sampleRate * 0.45) {
                active[b] = false
                continue
            }
            val a = 10.0.pow(g / 40.0)
            val w0 = 2.0 * PI * f / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cw = cos(w0)
            val a0 = 1.0 + alpha / a
            b0[b] = ((1.0 + alpha * a) / a0).toFloat()
            b1[b] = ((-2.0 * cw) / a0).toFloat()
            b2[b] = ((1.0 - alpha * a) / a0).toFloat()
            a1[b] = ((-2.0 * cw) / a0).toFloat()
            a2[b] = ((1.0 - alpha / a) / a0).toFloat()
            active[b] = true
            anyBand = true
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(remaining)
        if (coefVersion != AudioEngine.version) computeCoefs()

        val ch = channels
        val bal = AudioEngine.balance
        val leftGain = if (bal > 0f) 1f - bal else 1f
        val rightGain = if (bal < 0f) 1f + bal else 1f
        val frames = remaining / (2 * ch)
        val filter = eqOn && anyBand
        val pre = preGain

        val processing = filter || pre != 1f || bal != 0f
        if (release == 0f) release = 1f - kotlin.math.exp(-1.0 / (0.15 * sampleRate)).toFloat()
        var minGain = 1f
        for (frame in 0 until frames) {
            if (!processing) {
                // untouched: copy the samples bit for bit
                var mono = 0f
                for (c in 0 until ch) {
                    val v = inputBuffer.short
                    out.putShort(v)
                    mono += v / 32768f
                }
                feed(mono / ch)
                continue
            }
            var peak = 0f
            for (c in 0 until ch) {
                var x = inputBuffer.short / 32768f
                x *= pre
                if (filter) {
                    val s1 = z1[c]
                    val s2 = z2[c]
                    for (b in 0 until n) {
                        if (!active[b]) continue
                        val y = b0[b] * x + s1[b]
                        s1[b] = b1[b] * x - a1[b] * y + s2[b]
                        s2[b] = b2[b] * x - a2[b] * y
                        x = y
                    }
                }
                if (ch == 2) x *= if (c == 0) leftGain else rightGain
                if (c < 8) frameBuf[c] = x
                val ax = if (x < 0f) -x else x
                if (ax > peak) peak = ax
            }
            // limiter
            val need = if (peak * limGain > ceiling) ceiling / peak else 1f
            limGain = if (need < limGain) need else limGain + (1f - limGain) * release
            if (limGain < minGain) minGain = limGain
            var mono = 0f
            for (c in 0 until ch) {
                val x = frameBuf[c.coerceAtMost(7)] * limGain
                mono += x
                // TPDF dither of +-1 LSB before rounding to 16 bit
                val d = nextRand() - nextRand()
                val s = (x * 32767f + d).let { if (it >= 0f) it + 0.5f else it - 0.5f }.coerceIn(-32768f, 32767f)
                out.putShort(s.toInt().toShort())
            }
            feed(mono / ch)
        }
        lastReductionDb = if (minGain < 1f) 20f * log10(minGain) else 0f
        // Drop any trailing partial frame.
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    /** Uniform 0..1 from a xorshift generator (cheap, good enough for dither). */
    private fun nextRand(): Float {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return (x ushr 8) / 16777216f
    }

    private fun feed(sample: Float) {
        ring[ringPos++] = sample
        if (ringPos == fftSize) {
            analyse()
            ringPos = 0
        }
    }

    private fun analyse() {
        // waveform for the oscilloscope
        val wave = AudioEngine.wave
        val step = fftSize / wave.size
        for (i in wave.indices) wave[i] = ring[i * step]

        for (i in 0 until fftSize) {
            re[i] = ring[i] * window[i]
            im[i] = 0f
        }
        fft(re, im)

        // log-spaced bands from ~45 Hz to ~16 kHz
        val levels = AudioEngine.levels
        val bands = levels.size
        val binHz = sampleRate.toFloat() / fftSize
        val lo = 45f
        val hi = minOf(16_000f, sampleRate / 2f - binHz)
        for (b in 0 until bands) {
            val f0 = lo * (hi / lo).pow(b.toFloat() / bands)
            val f1 = lo * (hi / lo).pow((b + 1).toFloat() / bands)
            var k0 = (f0 / binHz).toInt().coerceAtLeast(1)
            val k1 = (f1 / binHz).toInt().coerceAtLeast(k0 + 1).coerceAtMost(fftSize / 2)
            if (k0 >= k1) k0 = k1 - 1
            var sum = 0f
            for (k in k0 until k1) sum += sqrt(re[k] * re[k] + im[k] * im[k])
            val mag = sum / (k1 - k0) / (fftSize / 4f)
            val db = 20f * log10(mag + 1e-6f)
            levels[b] = ((db + 62f) / 56f).coerceIn(0f, 1f)
        }
        AudioEngine.lastAudioNanos = System.nanoTime()
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(xr: FloatArray, xi: FloatArray) {
        val size = xr.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = xr[i]; xr[i] = xr[j]; xr[j] = tr
                val ti = xi[i]; xi[i] = xi[j]; xi[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < size) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = xr[i + k]
                    val ui = xi[i + k]
                    val vr = xr[i + k + len / 2] * cr - xi[i + k + len / 2] * ci
                    val vi = xr[i + k + len / 2] * ci + xi[i + k + len / 2] * cr
                    xr[i + k] = ur + vr
                    xi[i + k] = ui + vi
                    xr[i + k + len / 2] = ur - vr
                    xi[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
