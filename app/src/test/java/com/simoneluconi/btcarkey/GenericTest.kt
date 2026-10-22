package com.simoneluconi.btcarkey

import com.simoneluconi.btcarkey.auth.Serials
import com.simoneluconi.btcarkey.protocol.Advertisement
import com.simoneluconi.btcarkey.protocol.AutoLockConfig
import com.simoneluconi.btcarkey.protocol.Protocols
import com.simoneluconi.btcarkey.protocol.RemoteButton
import com.simoneluconi.btcarkey.protocol.ftk.FtkProtocol
import com.simoneluconi.btcarkey.settings.ActionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol-agnostic pieces: the registry, serials, calibration, action settings. */
class GenericTest {

    @Test
    fun `registry finds the protocol of an advertisement`() {
        val advert = Advertisement(manufacturerData = mapOf(0xF715 to byteArrayOf(1, 2, 3, 4, 5, 6)))
        val (protocol, serial) = Protocols.identify(advert)!!
        assertSame(FtkProtocol, protocol)
        assertEquals("01:02:03:04:05:06", serial)
        assertNull(Protocols.identify(Advertisement(name = "Some headphones")))
    }

    @Test
    fun `registry ids are unique and resolvable`() {
        val ids = Protocols.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Protocols.all.forEach { assertSame(it, Protocols.byId(it.id)) }
        assertNull(Protocols.byId("nope"))
        // Devices saved before protocols had ids are FTK boxes
        assertEquals(FtkProtocol.ID, Protocols.default.id)
        assertTrue(Protocols.scanFilters.isNotEmpty())
    }

    @Test
    fun `calibration uses trimmed means and keeps lock beyond unlock`() {
        // One outlier at each end is dropped out of ten samples
        val near = listOf(60, 61, 59, 60, 62, 58, 60, 61, 20, 99)
        val far = listOf(80, 82, 78, 81, 79, 80, 80, 81, 40, 120)
        assertEquals(AutoLockConfig(enabled = true, lockDbm = 80, unlockDbm = 60), AutoLockConfig.calibrate(near, far))
        // Lock samples not further than unlock: lock moves to unlock + 8
        assertEquals(68, AutoLockConfig.calibrate(listOf(60, 60), listOf(61, 61)).lockDbm)
        // No samples: the vendor defaults
        assertEquals(AutoLockConfig.DEFAULT.copy(enabled = true), AutoLockConfig.calibrate(emptyList(), emptyList()))
    }

    @Test
    fun `action config round trip and bounds`() {
        val config = ActionConfig(ActionConfig.Method.REMOTE, RemoteButton.TRUNK, presses = 2, holdMs = 2000)
        assertEquals(config, ActionConfig.decode(config.encode()))
        assertEquals(ActionConfig.MAX_PRESSES, ActionConfig.decode("REMOTE:LOCK:9:200")!!.presses)
        assertEquals(ActionConfig.HOLD_RANGE_MS.last, ActionConfig.decode("REMOTE:LOCK:1:99999")!!.holdMs)
        assertNull(ActionConfig.decode("REMOTE:HORN:1:200"))
        assertNull(ActionConfig.decode("garbage"))
    }

    @Test
    fun `serials match in either byte order`() {
        assertEquals("A1:B2:C3:D4:E5:F6", Serials.normalize("a1b2c3d4e5f6"))
        assertNull(Serials.normalize("A1:B2:C3"))
        assertTrue(Serials.same("A1:B2:C3:D4:E5:F6", "a1-b2-c3-d4-e5-f6"))
        assertTrue(Serials.same("A1:B2:C3:D4:E5:F6", "F6:E5:D4:C3:B2:A1"))
        assertFalse(Serials.same("A1:B2:C3:D4:E5:F6", "A1:B2:C3:D4:E5:F7"))
    }
}
