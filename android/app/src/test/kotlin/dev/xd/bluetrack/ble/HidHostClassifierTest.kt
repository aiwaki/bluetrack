package dev.xd.bluetrack.ble

import android.bluetooth.BluetoothClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidHostClassifierTest {
    @Test
    fun rejectsAudioDevicesEvenWhenBonded() {
        assertNull(
            HidHostCandidate(
                name = "AirPods Pro",
                majorDeviceClass = BluetoothClass.Device.Major.AUDIO_VIDEO,
            ).hidHostRank(),
        )
        assertNull(
            HidHostCandidate(
                name = "WH-1000XM5",
                majorDeviceClass = null,
            ).hidHostRank(),
        )
    }

    @Test
    fun acceptsComputerClassAndComputerNamedHosts() {
        assertEquals(
            100,
            HidHostCandidate(
                name = "MacBook Pro",
                majorDeviceClass = BluetoothClass.Device.Major.COMPUTER,
            ).hidHostRank(),
        )
        assertEquals(
            75,
            HidHostCandidate(
                name = "aiwaki's MacBook Pro",
                majorDeviceClass = null,
            ).hidHostRank(),
        )
    }

    @Test
    fun rejectsInputPeripheralsAsHosts() {
        assertNull(
            HidHostCandidate(
                name = "Magic Trackpad",
                majorDeviceClass = BluetoothClass.Device.Major.PERIPHERAL,
            ).hidHostRank(),
        )
    }
}
