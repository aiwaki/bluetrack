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
        // Hold the virtual clock at the queuedAtMs so the staleness
        // path in enqueueMouse never triggers — these tests exercise
        // coalescing / mode-switch / empty-drop, not the new stale
        // motion reset. The dedicated staleness test below drives
        // the clock forward explicitly.
        val now = LongArray(1) { 0L }
        val buffer = HidOutputBuffer(nowMsProvider = { now[0] })

        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 0, 0, 0), queuedAtMs = 10L)

        assertFalse(buffer.hasPending())
        assertNull(buffer.poll())
    }

    @Test
    fun coalescesMouseReportsAndKeepsEarliestQueueTime() {
        // Hold the virtual clock at the queuedAtMs so the staleness
        // path in enqueueMouse never triggers — these tests exercise
        // coalescing / mode-switch / empty-drop, not the new stale
        // motion reset. The dedicated staleness test below drives
        // the clock forward explicitly.
        val now = LongArray(1) { 10L }
        val buffer = HidOutputBuffer(nowMsProvider = { now[0] })

        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 80, 10, 0), queuedAtMs = 10L)
        now[0] = 20L
        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 70, (-5).toByte(), 0), queuedAtMs = 20L)

        val first = buffer.poll()
        val second = buffer.poll()

        // Accumulated dx = 80 + 70 = 150, capped to the
        // MAX_MOTION_PER_POLL = 48 backlog ceiling (lurch
        // mitigation, not the HID byte range of 127). dy follows
        // the same coalescing path: 10 + -5 = 5. The first poll
        // drains the full accumulated dx because 48 < 127.
        assertEquals(HidMode.MOUSE, first?.mode)
        assertEquals(10L, first?.queuedAtMs)
        assertArrayEquals(byteArrayOf(0, 48, 5, 0, 0), requireNotNull(first).report)
        assertFalse(buffer.hasPending())
        assertNull(second)
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
    fun dropsStaleAccumulatedMotionOnNewEnqueue() {
        // Reproduces the BLE-stall lurch case: a sender stall keeps
        // the first enqueue's motion sitting in the buffer past
        // STALE_MOTION_MS. When fresh user motion arrives the
        // buffer must drop the stale accumulation (button state
        // survives) so the next poll reflects current intent, not
        // the bursty backlog from before the radio recovered.
        val now = LongArray(1) { 100L }
        val buffer = HidOutputBuffer(nowMsProvider = { now[0] })

        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 40, 30, 0), queuedAtMs = 100L)
        // Sender stalled for 250 ms — well past the 100 ms staleness
        // threshold. Fresh motion arrives.
        now[0] = 350L
        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 5, 5, 0), queuedAtMs = 350L)

        val output = buffer.poll()

        // dx/dy should be the fresh deltas only — the stale 40/30
        // is gone. mouseQueuedAtMs was re-anchored to 350L on the
        // reset path so the new frame's age is accurate.
        assertEquals(HidMode.MOUSE, output?.mode)
        assertEquals(350L, output?.queuedAtMs)
        assertArrayEquals(byteArrayOf(0, 5, 5, 0, 0), requireNotNull(output).report)
        assertFalse(buffer.hasPending())
    }

    @Test
    fun modeSwitchClearsStalePendingReports() {
        // Hold the virtual clock at the queuedAtMs so the staleness
        // path in enqueueMouse never triggers — these tests exercise
        // coalescing / mode-switch / empty-drop, not the new stale
        // motion reset. The dedicated staleness test below drives
        // the clock forward explicitly.
        val now = LongArray(1) { 0L }
        val buffer = HidOutputBuffer(nowMsProvider = { now[0] })

        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 50, 0, 0), queuedAtMs = 10L)
        buffer.enqueue(HidMode.GAMEPAD, byteArrayOf(0, 0, 4, 0, 0, 0), queuedAtMs = 20L)

        val output = buffer.poll()

        assertEquals(HidMode.GAMEPAD, output?.mode)
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 0, 0), requireNotNull(output).report)
        assertFalse(buffer.hasPending())
    }

    @Test
    fun keyboardFrameDrainsFirstWithoutDisturbingMouseMotion() {
        val buffer = HidOutputBuffer(nowMsProvider = { 0L })
        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 5, 7, 0, 0), queuedAtMs = 0L)
        buffer.enqueue(HidMode.KEYBOARD, byteArrayOf(0x08, 0, 0x2E, 0, 0, 0, 0, 0), queuedAtMs = 0L)

        val first = buffer.poll()
        assertEquals(HidMode.KEYBOARD, first?.mode)
        assertEquals(0x2E.toByte(), requireNotNull(first).report[2])

        val second = buffer.poll()
        assertEquals(HidMode.MOUSE, second?.mode)
        assertArrayEquals(byteArrayOf(0, 5, 7, 0, 0), requireNotNull(second).report)
    }

    @Test
    fun mouseFrameCarriesHorizontalWheelByte() {
        val buffer = HidOutputBuffer(nowMsProvider = { 0L })
        buffer.enqueue(HidMode.MOUSE, byteArrayOf(0, 0, 0, 2, 3), queuedAtMs = 0L)

        val out = buffer.poll()
        assertEquals(HidMode.MOUSE, out?.mode)
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 3), requireNotNull(out).report)
    }

    @Test
    fun keyboardFramesAreFifoPassThrough() {
        val buffer = HidOutputBuffer(nowMsProvider = { 0L })
        buffer.enqueue(HidMode.KEYBOARD, byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0), queuedAtMs = 0L)
        buffer.enqueue(HidMode.KEYBOARD, byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0), queuedAtMs = 0L)

        assertEquals(1.toByte(), requireNotNull(buffer.poll()).report[0])
        assertEquals(2.toByte(), requireNotNull(buffer.poll()).report[0])
        assertNull(buffer.poll())
    }

    @Test
    fun clearWipesKeyboardQueue() {
        val buffer = HidOutputBuffer(nowMsProvider = { 0L })
        buffer.enqueue(HidMode.KEYBOARD, byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0), queuedAtMs = 0L)
        assertTrue(buffer.hasPending())

        buffer.clear()
        assertNull(buffer.poll())
        assertFalse(buffer.hasPending())
    }
}
