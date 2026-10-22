package com.simoneluconi.btcarkey

import com.simoneluconi.btcarkey.link.GattLogFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The BLE library's trace never carries frame bytes into the app's log (a pairing write holds the key). */
class GattLogFilterTest {
    // A SET_DEVICE_KEY frame: 16-byte header + the key 00112233445566778899aabbccddeeff
    private val keyHex = "00112233445566778899AABBCCDDEEFF"
    private val frameHex = "55AA0000010006000100100078052C02$keyHex"

    @Test
    fun `write lines lose their bytes`() {
        val line = "gatt.writeCharacteristic(8849a9d1-d75f-0c8c-2348-de2d580f2f24, value=0x$frameHex, WRITE COMMAND)"
        val filtered = GattLogFilter.filter(line)!!
        assertFalse(filtered.contains(keyHex, ignoreCase = true))
        assertEquals("gatt.writeCharacteristic(8849a9d1-d75f-0c8c-2348-de2d580f2f24, value=<32 bytes>, WRITE COMMAND)", filtered)
    }

    @Test
    fun `dashed values lose their bytes too`() {
        val dashed = frameHex.chunked(2).joinToString("-")
        val line = "Data written to 8849a9d1-d75f-0c8c-2348-de2d580f2f24, value: (0x) $dashed"
        val filtered = GattLogFilter.filter(line)!!
        assertFalse(filtered.replace("-", "").contains(keyHex, ignoreCase = true))
        assertEquals("Data written to 8849a9d1-d75f-0c8c-2348-de2d580f2f24, value=<32 bytes>", filtered)
    }

    @Test
    fun `notifications lose their bytes`() {
        val filtered = GattLogFilter.filter("Notification received from 8849a9d1, value: (0x) AA-55-00-01")!!
        assertEquals("Notification received from 8849a9d1, value=<4 bytes>", filtered)
    }

    @Test
    fun `other lines pass, signal readings are dropped`() {
        assertEquals("MTU changed to: 247", GattLogFilter.filter("MTU changed to: 247"))
        assertNull(GattLogFilter.filter("Remote RSSI received: -56 dBm"))
    }
}
