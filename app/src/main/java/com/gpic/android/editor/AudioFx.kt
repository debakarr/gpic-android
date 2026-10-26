package com.gpic.android.editor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Volume envelope for one media item: [gain] times a fade in over the first [fadeInUs] and a fade out over the
 * last [fadeOutUs] of [durationUs]. Counts samples from the item's start (Media3 flushes processors per item).
 */
@OptIn(UnstableApi::class)
class FadeAudioProcessor(
    private val gain: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val durationUs: Long,
) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val rate = inputAudioFormat.sampleRate
        val ch = inputAudioFormat.channelCount
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytes = if (float) 4 else 2
        val remaining = inputBuffer.remaining()
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        while (input.remaining() >= bytes * ch) {
            val tUs = frames * 1_000_000L / rate
            val g = envelope(tUs)
            repeat(ch) {
                if (float) out.putFloat(input.float * g)
                else out.putShort((input.short * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            frames++
        }
        out.flip()
    }

    internal fun envelope(tUs: Long): Float {
        var g = gain
        if (fadeInUs > 0 && tUs < fadeInUs) g *= tUs.toFloat() / fadeInUs
        if (fadeOutUs > 0 && durationUs > 0 && tUs > durationUs - fadeOutUs) g *= ((durationUs - tUs).toFloat() / fadeOutUs).coerceAtLeast(0f)
        return g
    }

    override fun onFlush() { frames = 0 }
    override fun onReset() { frames = 0 }
}
