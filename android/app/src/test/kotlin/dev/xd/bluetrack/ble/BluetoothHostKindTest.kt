package dev.xd.bluetrack.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothHostKindTest {
    @Test
    fun audioNames() {
        assertEquals(BluetoothHostKind.Audio, classifyByName("AirPods Pro"))
        assertEquals(BluetoothHostKind.Audio, classifyByName("Sony WH-1000 headphones"))
        assertEquals(BluetoothHostKind.Audio, classifyByName("JBL Speaker"))
    }

    @Test
    fun pointingAndKeyboardNames() {
        assertEquals(BluetoothHostKind.Pointing, classifyByName("Magic Mouse"))
        assertEquals(BluetoothHostKind.Pointing, classifyByName("Logitech Trackpad"))
        assertEquals(BluetoothHostKind.Keyboard, classifyByName("Magic Keyboard"))
        assertEquals(BluetoothHostKind.Keyboard, classifyByName("Logitech K380"))
    }

    @Test
    fun phoneAndComputerNames() {
        assertEquals(BluetoothHostKind.Phone, classifyByName("iPhone 15"))
        assertEquals(BluetoothHostKind.Phone, classifyByName("Pixel 8"))
        assertEquals(BluetoothHostKind.Computer, classifyByName("MacBook Pro"))
        assertEquals(BluetoothHostKind.Computer, classifyByName("DESKTOP-A1B2C3"))
        assertEquals(BluetoothHostKind.Computer, classifyByName("ThinkPad X1"))
    }

    @Test
    fun unknownWhenNoHint() {
        assertEquals(BluetoothHostKind.Unknown, classifyByName("Living Room"))
        assertEquals(BluetoothHostKind.Unknown, classifyByName(""))
    }

    @Test
    fun isCaseInsensitive() {
        assertEquals(BluetoothHostKind.Computer, classifyByName("macbook air"))
        assertEquals(BluetoothHostKind.Audio, classifyByName("AIRPODS"))
    }

    @Test
    fun audioTakesPrecedenceOverOtherHints() {
        // "speaker" (audio) is checked before "keyboard"; a combo name
        // resolves to Audio by the documented precedence order.
        assertEquals(BluetoothHostKind.Audio, classifyByName("Keyboard Speaker Combo"))
    }
}
