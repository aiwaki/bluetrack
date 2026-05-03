package dev.xd.bluetrack.ui

import dev.xd.bluetrack.engine.HidMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidOutputBufferTest {
    @Test
    fun dropsEmptyMouseReports() {
        val buffer = HidOutputBuffer()

        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 0, 0, 0), queuedAtMs = 10L)

        assertFalse(buffer.hasPending())
        assertNull(buffer.poll())
    }

    @Test
    fun coalescesMouseReportsAndKeepsEarliestQueueTime() {
        val buffer = HidOutputBuffer()

        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 80, 10, 0), queuedAtMs = 10L)
        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 70, (-5).toByte(), 0), queuedAtMs = 20L)

        val first = buffer.poll()
        val second = buffer.poll()

        assertEquals(HidMode.MOUSE, first?.mode)
        assertEquals(10L, first?.queuedAtMs)
        assertArrayEquals(byteArrayOf(0, 127.toByte(), 5, 0), requireNotNull(first).report)
        assertEquals(10L, second?.queuedAtMs)
        assertArrayEquals(byteArrayOf(0, 23, 0, 0), requireNotNull(second).report)
        assertFalse(buffer.hasPending())
    }

    @Test
    fun keepsBoundedGamepadQueue() {
        val buffer = HidOutputBuffer(maxGamepadReports = 2)

        buffer.enqueue(HidMode.GAMEPAD, byteArrayOf(1, 0, 0, 0, 0, 0), queuedAtMs = 10L)
        buffer.enqueue(HidMode.GAMEPAD, byteArrayOf(2, 0, 0, 0, 0, 0), queuedAtMs = 20L)
        buffer.enqueue(HidMode.GAMEPAD, byteArrayOf(3, 0, 0, 0, 0, 0), queuedAtMs = 30L)

        assertTrue(buffer.hasPending())
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 0, 0), buffer.poll()?.report)
        assertArrayEquals(byteArrayOf(3, 0, 0, 0, 0, 0), buffer.poll()?.report)
        assertFalse(buffer.hasPending())
    }

    @Test
    fun modeSwitchClearsStalePendingReports() {
        val buffer = HidOutputBuffer()

        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 50, 0, 0), queuedAtMs = 10L)
        buffer.enqueue(HidMode.GAMEPAD, byteArrayOf(0, 0, 4, 0, 0, 0), queuedAtMs = 20L)

        val output = buffer.poll()

        assertEquals(HidMode.GAMEPAD, output?.mode)
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 0, 0), requireNotNull(output).report)
        assertFalse(buffer.hasPending())
    }
}
