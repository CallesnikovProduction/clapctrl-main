package dev.clapctrl.core.dsp

import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.DetectorSettings
import dev.clapctrl.core.Gesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemporalGestureRecognizerTest {
    @Test fun `single clap does not trigger`() {
        assertTrue(TemporalGestureRecognizer().accept(AcousticClass.CLAP, 0.9, 1000).isEmpty())
    }

    @Test fun `timing endpoints are inclusive and confidence uses weaker clap`() {
        for (interval in listOf(150L, 700L)) {
            val recognizer = TemporalGestureRecognizer()
            recognizer.accept(AcousticClass.CLAP, 0.9, 1000)
            val result = recognizer.accept(AcousticClass.CLAP, 0.8, 1000 + interval).single()
            assertEquals(Gesture.DOUBLE_CLAP, result.gesture)
            assertEquals(0.8, result.confidence)
        }
    }

    @Test fun `echo does not move original matching window`() {
        val recognizer = TemporalGestureRecognizer()
        recognizer.accept(AcousticClass.CLAP, 0.9, 1000)
        assertTrue(recognizer.accept(AcousticClass.CLAP, 0.9, 1050).isEmpty())
        assertTrue(recognizer.accept(AcousticClass.CLAP, 0.9, 1110).isEmpty())
        assertEquals(1, recognizer.accept(AcousticClass.CLAP, 0.9, 1250).size)
    }

    @Test fun `expired first clap becomes a fresh first clap`() {
        val recognizer = TemporalGestureRecognizer()
        recognizer.accept(AcousticClass.CLAP, 0.9, 1000)
        assertTrue(recognizer.accept(AcousticClass.CLAP, 0.9, 1701).isEmpty())
        assertEquals(1, recognizer.accept(AcousticClass.CLAP, 0.9, 2000).size)
    }

    @Test fun `triple clap emits one pair without overlapping matches`() {
        val recognizer = TemporalGestureRecognizer()
        val results = listOf(1000L, 1300L, 1600L).flatMap { recognizer.accept(AcousticClass.CLAP, 0.8, it) }
        assertEquals(1, results.size)
    }

    @Test fun `snaps emit immediately and break clap sequence`() {
        val recognizer = TemporalGestureRecognizer()
        recognizer.accept(AcousticClass.CLAP, 0.9, 1000)
        assertEquals(Gesture.FINGER_SNAP, recognizer.accept(AcousticClass.SNAP, 0.9, 1200).single().gesture)
        assertTrue(recognizer.accept(AcousticClass.CLAP, 0.9, 1400).isEmpty())
    }

    @Test fun `negative or invalid confidence clears pending clap`() {
        for (confidence in listOf(0.0, Double.NaN)) {
            val recognizer = TemporalGestureRecognizer()
            recognizer.accept(AcousticClass.CLAP, 0.9, 1000)
            assertTrue(recognizer.accept(AcousticClass.CLAP, confidence, 1200).isEmpty())
            assertTrue(recognizer.accept(AcousticClass.CLAP, 0.9, 1400).isEmpty())
        }
    }

    @Test fun `old timestamps and duplicates cannot rewind temporal state`() {
        val recognizer = TemporalGestureRecognizer()
        recognizer.accept(AcousticClass.CLAP, 0.9, 1000)
        assertTrue(recognizer.accept(AcousticClass.CLAP, 0.9, 1000).isEmpty())
        recognizer.accept(AcousticClass.NEGATIVE, 0.0, 1200)
        assertTrue(recognizer.accept(AcousticClass.CLAP, 0.9, 1100).isEmpty())
        assertTrue(recognizer.accept(AcousticClass.CLAP, 0.9, 1400).isEmpty())
    }

    @Test fun `reset clears pending and timestamp state`() {
        val recognizer = TemporalGestureRecognizer()
        recognizer.accept(AcousticClass.CLAP, 0.9, 1000)
        recognizer.reset()
        assertTrue(recognizer.accept(AcousticClass.CLAP, 0.9, 0).isEmpty())
        assertEquals(1, recognizer.accept(AcousticClass.CLAP, 0.9, 300).size)
    }

    @Test fun `invalid timing contracts fail fast`() {
        assertFailsWith<IllegalArgumentException> { TemporalGestureRecognizer(DetectorSettings(doubleClapMinMs = 0)) }
        assertFailsWith<IllegalArgumentException> { TemporalGestureRecognizer(DetectorSettings(doubleClapMaxMs = 100)) }
    }
}
