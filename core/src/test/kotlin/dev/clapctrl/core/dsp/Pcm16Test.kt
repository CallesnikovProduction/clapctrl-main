package dev.clapctrl.core.dsp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Pcm16Test {
    @Test fun `signed little endian normalization includes both extremes`() {
        val decoded = Pcm16.decodeMono(byteArrayOf(0, -128, 0, 0, -1, 127))
        assertContentEquals(floatArrayOf(-1f, 0f, 32767f / 32768f), decoded)
    }

    @Test fun `big endian and stereo downmix preserve sample grouping`() {
        val stereo = byteArrayOf(0x40, 0, -64, 0, 0x20, 0, 0x20, 0)
        assertContentEquals(floatArrayOf(0f, 0.25f), Pcm16.decodeMono(stereo, 2, false))
    }

    @Test fun `incomplete interleaved PCM is rejected by one shot decoder`() {
        assertFailsWith<IllegalArgumentException> { Pcm16.decodeMono(byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { Pcm16.decodeMono(byteArrayOf(1, 2), 2) }
    }

    @Test fun `streaming reads can split bytes channels and frames`() {
        val decoder = Pcm16Framer(8_000, 2, 2)
        val source = byteArrayOf(0, 64, 0, 64, 0, -64, 0, -64)
        val frames = source.flatMapIndexed { index, byte -> decoder.accept(byteArrayOf(byte), 100L + index) }
        assertEquals(1, frames.size)
        assertEquals(100L, frames.single().timestampMs)
        assertContentEquals(floatArrayOf(0.5f, -0.5f), frames.single().samples)
    }

    @Test fun `frame timestamps follow sample count without accumulating rounding error`() {
        val decoder = Pcm16Framer(44_100, frameSizeSamples = 441)
        val frames = decoder.accept(ByteArray(44_100 * 2), 1000)
        assertEquals(100, frames.size)
        assertEquals(1990L, frames.last().timestampMs)
        assertTrue(frames.all { it.samples.size == 441 && it.sampleRate == 44_100 })
    }

    @Test fun `reset discards partial PCM and restarts time anchor`() {
        val decoder = Pcm16Framer(8_000, frameSizeSamples = 1)
        decoder.accept(byteArrayOf(-1), 10)
        decoder.reset()
        val frame = decoder.accept(byteArrayOf(0, 64), 200).single()
        assertEquals(200L, frame.timestampMs)
        assertEquals(0.5f, frame.samples.single())
    }
}
