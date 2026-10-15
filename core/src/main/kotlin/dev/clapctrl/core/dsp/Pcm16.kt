package dev.clapctrl.core.dsp

import dev.clapctrl.core.AudioFrame

/** Signed 16-bit PCM. Channel averaging happens before DSP; output is in [-1, 1). */
object Pcm16 {
    fun decodeMono(bytes: ByteArray, channels: Int = 1, littleEndian: Boolean = true): FloatArray {
        require(channels in 1..32) { "Unsupported channel count: $channels" }
        require(bytes.size % (2 * channels) == 0) { "PCM buffer ends inside a sample frame" }
        return FloatArray(bytes.size / (2 * channels)) { index ->
            var sum = 0.0
            for (channel in 0 until channels) {
                val offset = (index * channels + channel) * 2
                val low = bytes[offset + if (littleEndian) 0 else 1].toInt() and 255
                val high = bytes[offset + if (littleEndian) 1 else 0].toInt()
                sum += ((high shl 8) or low) / 32768.0
            }
            (sum / channels).toFloat()
        }
    }
}

/** Bounded streaming decoder, including reads split inside PCM samples or channel groups. */
class Pcm16Framer(
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val frameSizeSamples: Int = sampleRate / 100,
    private val littleEndian: Boolean = true,
) {
    init {
        require(sampleRate in 8_000..192_000) { "Sample rate must be 8000–192000 Hz" }
        require(channels in 1..32)
        require(frameSizeSamples in 1..sampleRate)
    }

    private val frame = FloatArray(frameSizeSamples)
    private var firstByte = -1
    private var channelCount = 0
    private var channelSum = 0.0
    private var position = 0
    private var emittedSamples = 0L
    private var anchorMs: Long? = null

    fun accept(bytes: ByteArray, timestampMs: Long, offset: Int = 0, length: Int = bytes.size - offset): List<AudioFrame> {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (length == 0) return emptyList()
        if (anchorMs == null) anchorMs = timestampMs
        val output = ArrayList<AudioFrame>()
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and 255
            if (firstByte < 0) {
                firstByte = value
                continue
            }
            val signed = if (littleEndian) ((value shl 8) or firstByte).toShort().toInt()
            else ((firstByte shl 8) or value).toShort().toInt()
            firstByte = -1
            channelSum += signed / 32768.0
            channelCount++
            if (channelCount == channels) {
                frame[position++] = (channelSum / channels).toFloat()
                channelCount = 0
                channelSum = 0.0
                if (position == frame.size) {
                    output += AudioFrame(frame.copyOf(), sampleRate, anchorMs!! + emittedSamples * 1000L / sampleRate)
                    emittedSamples += frame.size
                    position = 0
                }
            }
        }
        return output
    }

    /** Incomplete PCM is intentionally discarded at device stop/reconnect. */
    fun reset() {
        firstByte = -1
        channelCount = 0
        channelSum = 0.0
        position = 0
        emittedSamples = 0
        anchorMs = null
    }
}
