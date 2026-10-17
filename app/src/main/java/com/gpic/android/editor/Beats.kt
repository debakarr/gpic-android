package com.gpic.android.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Tempo and beat times of a song, for beat markers and cutting to the music. */
class BeatInfo(val bpm: Float, val beatsMs: List<Long>)

/**
 * Beat tracking on the phone (nothing is uploaded): spectral-flux onsets → tempo by autocorrelation (60–180 BPM,
 * nudged towards ~120 like most pop/EDM) → the beat grid phase that lines up with the strongest onsets, then each
 * beat snapped to a nearby onset. The maths is pure ([detect]) and unit-tested with a synthetic click track.
 */
object Beats {
    const val RATE = 11025
    private const val FRAME = 1024
    private const val HOP = 256

    /** Beats every 1, 2 or 4 beats: LightCut's Fast / Normal / Chillout. */
    enum class Pace(val label: String, val every: Int) { FAST("Fast", 1), NORMAL("Normal", 2), CHILL("Chillout", 4) }

    fun detect(samples: FloatArray, rate: Int = RATE): BeatInfo? {
        val env = onsetEnvelope(samples)
        if (env.size < 64) return null
        val hopMs = HOP * 1000.0 / rate
        // Tempo: autocorrelation of the onset envelope over 60..180 BPM lags.
        val minLag = (60_000.0 / 180 / hopMs).toInt().coerceAtLeast(1)
        val maxLag = (60_000.0 / 60 / hopMs).toInt().coerceAtMost(env.size / 2)
        if (maxLag <= minLag) return null
        var bestLag = 0
        var best = -1.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in lag until env.size) s += env[i] * env[i - lag]
            s /= (env.size - lag)
            // Gentle preference for ~120 BPM (log-Gaussian), so a 60/240 octave error is less likely.
            val bpm = 60_000.0 / (lag * hopMs)
            val w = kotlin.math.exp(-0.5 * (ln(bpm / 120.0) / 0.7).let { it * it })
            if (s * w > best) { best = s * w; bestLag = lag }
        }
        if (bestLag == 0 || best <= 0) return null
        // Fine-tune the period (fractional frames) and the phase together: the comb of beats that collects the
        // most onset energy, each tooth looking ±1 frame so a slightly-off period doesn't miss later beats.
        var period = bestLag.toDouble()
        var phase = 0.0
        var bestSum = -1.0
        var p = bestLag - 1.0
        while (p <= bestLag + 1.0) {
            var ph = 0.0
            while (ph < p) {
                var sum = 0.0
                var k = ph
                while (k < env.size) {
                    val c = k.toInt()
                    sum += maxOf(env[c], env.getOrElse(c - 1) { 0.0 }, env.getOrElse(c + 1) { 0.0 })
                    k += p
                }
                if (sum > bestSum) { bestSum = sum; period = p; phase = ph }
                ph += 0.5
            }
            p += 0.05
        }
        // Each beat moves to the strongest onset within ±10 % of a period, so slight tempo drift doesn't accumulate.
        val beats = mutableListOf<Long>()
        val win = max(1, (period * 0.1).toInt())
        var k = phase
        while (k < env.size) {
            val c = k.toInt()
            var arg = c
            for (j in (c - win).coerceAtLeast(0)..(c + win).coerceAtMost(env.size - 1)) if (env[j] > env[arg]) arg = j
            // Only move onto a real onset; otherwise keep the grid.
            val at = if (env[arg] > 0.05) arg.toDouble() else k
            beats += (at * hopMs + ONSET_DELAY_MS).toLong()
            k = at + period
        }
        return BeatInfo((60_000.0 / (period * hopMs)).toFloat(), beats.filter { it >= 0 }.distinct())
    }

    /** A sound shows up in the flux about this long after a frame starts (measured with click tracks). */
    private val ONSET_DELAY_MS = FRAME * 0.6 * 1000 / RATE

    /** Half-wave-rectified log-magnitude spectral flux per hop, normalised and with its local mean removed. */
    internal fun onsetEnvelope(x: FloatArray): DoubleArray {
        if (x.size < FRAME) return DoubleArray(0)
        val n = (x.size - FRAME) / HOP + 1
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / FRAME) }
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val bins = FRAME / 2
        var prev = DoubleArray(bins)
        val env = DoubleArray(n)
        for (f in 0 until n) {
            val off = f * HOP
            for (i in 0 until FRAME) { re[i] = x[off + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            val mag = DoubleArray(bins)
            var flux = 0.0
            for (b in 1 until bins) {
                mag[b] = ln(1 + 100 * sqrt(re[b] * re[b] + im[b] * im[b]))
                val d = mag[b] - prev[b]
                if (d > 0) flux += d
            }
            env[f] = flux
            prev = mag
        }
        // The first frames compare against silence: not onsets.
        for (i in 0 until minOf(4, n)) env[i] = 0.0
        // Remove the slowly varying part (loudness changes) and normalise.
        val out = DoubleArray(n)
        val half = 8
        for (i in 0 until n) {
            var s = 0.0
            var c = 0
            for (j in (i - half).coerceAtLeast(0)..(i + half).coerceAtMost(n - 1)) { s += env[j]; c++ }
            out[i] = (env[i] - s / c).coerceAtLeast(0.0)
        }
        val mx = out.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        for (i in out.indices) out[i] /= mx
        return out
    }

    /** In-place radix-2 FFT (size must be a power of two). */
    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Beats every [Pace.every] beats, shifted into output time for a track that starts at [startMs] of the song. */
    fun markers(info: List<Long>, pace: Pace, startMs: Long, lengthMs: Long): List<Long> =
        info.filter { it >= startMs }.filterIndexed { i, _ -> i % pace.every == 0 }.map { it - startMs }.filter { it <= lengthMs }

    /**
     * Decodes up to [maxMs] of [uri]'s audio to mono at [RATE] Hz (simple averaging resample; plenty for beats and
     * waveforms). Returns null when there's no audio track Android can decode.
     */
    fun decodeMono(context: Context, uri: Uri, maxMs: Long = 5 * 60_000L): FloatArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = FloatArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var acc = 0.0
            var accN = 0.0
            var pos = 0.0
            val maxSamples = maxMs * RATE / 1000
            try {
                while (out.size < maxSamples) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val step = rate.toDouble() / RATE
                        while (buf.remaining() >= channels) {
                            var s = 0
                            repeat(channels) { s += buf.get() }
                            acc += s / (channels * 32768.0); accN++
                            pos += 1.0
                            if (pos >= step) { out.add((acc / accN).toFloat()); acc = 0.0; accN = 0.0; pos -= step }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return out.toArray()
        } catch (e: Exception) {
            android.util.Log.w("GPicEditor", "decode audio failed: ${e.message}")
            return null
        } finally {
            ex.release()
        }
    }

    /** Peak level per [buckets] slice of [samples], 0..1, for drawing a waveform. */
    fun waveform(samples: FloatArray, buckets: Int): FloatArray {
        if (samples.isEmpty() || buckets <= 0) return FloatArray(0)
        val per = max(1, samples.size / buckets)
        val out = FloatArray(minOf(buckets, samples.size))
        for (b in out.indices) {
            var m = 0f
            val end = minOf(samples.size, (b + 1) * per)
            for (i in b * per until end) m = max(m, kotlin.math.abs(samples[i]))
            out[b] = m
        }
        val top = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in out.indices) out[i] /= top
        return out
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(1 shl 16)
        var size = 0
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }
}
