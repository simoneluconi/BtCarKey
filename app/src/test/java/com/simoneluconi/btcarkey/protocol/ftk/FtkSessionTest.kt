package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.auth.AuthOutcome
import com.simoneluconi.btcarkey.auth.AuthStep
import com.simoneluconi.btcarkey.protocol.AutoLockConfig
import com.simoneluconi.btcarkey.protocol.BoxStatus
import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.protocol.CommandAck
import com.simoneluconi.btcarkey.protocol.PhoneInfo
import com.simoneluconi.btcarkey.protocol.ProtocolSession
import com.simoneluconi.btcarkey.protocol.RawConfigKind
import com.simoneluconi.btcarkey.protocol.RemoteButton
import com.simoneluconi.btcarkey.protocol.SessionContext
import com.simoneluconi.btcarkey.protocol.UnsupportedFeatureException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The FTK protocol seen only through the generic [ProtocolSession], the way the controller uses it. */
class FtkSessionTest {
    private val box = FakeBox()
    private val vault = MemoryVault()
    private val steps = mutableListOf<AuthStep>()
    private val statuses = mutableListOf<BoxStatus>()

    private val session: ProtocolSession = FtkProtocol.newSession(
        SessionContext(
            serial = "11:22:33:44:55:66",
            vault = vault,
            write = box::receive,
            log = {},
            pairingAllowed = { true },
            onStep = { steps += it },
            onStatus = { statuses += it },
            phone = { PhoneInfo(epochSeconds = 1_700_000_000, utcOffsetSeconds = 3600, is24h = true, language = "it") },
        )
    ).also { s -> box.send = s::onData }

    @Test
    fun `pairs a new box, then syncs the clock and reads the battery`() = runTest {
        val outcome = session.authenticate()
        assertTrue(outcome is AuthOutcome.Paired)
        assertEquals("1.2.3", outcome.info!!.firmware)
        assertEquals(listOf(AuthStep.HELLO, AuthStep.PAIRING), steps)
        assertTrue(box.deviceKey != null)

        session.start()
        val sync = Tlv.decode(box.infoSyncs.single()).toMap()
        assertEquals("it", String(sync[Tlv.LANGUAGE]!!))
        assertEquals(87, statuses.last().batteryPercent)
    }

    @Test
    fun `commands are acknowledged, unanswered ones are unconfirmed`() = runTest {
        session.authenticate()
        assertEquals(CommandAck.Done, session.command(CarCommand.UNLOCK))
        assertEquals(CtrlKey.of(CarCommand.UNLOCK), box.commands.last())
        box.answersCommands = false
        assertEquals(CommandAck.Unconfirmed, session.command(CarCommand.LOCK))
    }

    @Test
    fun `remote buttons are pressed and released`() = runTest {
        session.authenticate()
        session.press(RemoteButton.TRUNK, true)
        session.press(RemoteButton.TRUNK, false)
        val trunk = CtrlKey.of(RemoteButton.TRUNK)
        assertEquals(listOf(trunk to 0, trunk to 1), box.presses)
    }

    @Test
    fun `comfort entry, signal, NFC and vendor settings`() = runTest {
        session.authenticate()
        assertEquals(AutoLockConfig(enabled = false, lockDbm = 75, unlockDbm = 65), session.readAutoLock())
        val config = AutoLockConfig(enabled = true, lockDbm = 82, unlockDbm = 61)
        session.writeAutoLock(config)
        assertEquals(config, session.readAutoLock())
        assertEquals(58, session.readRssi())
        session.registerNfc(10)
        session.writeRawConfig(RawConfigKind.UNLOCK, byteArrayOf(1, 2))
        assertEquals(CfgKey.of(RawConfigKind.UNLOCK), box.rawConfigs.single().first)
        assertArrayEquals(byteArrayOf(1, 2), box.rawConfigs.single().second)
    }

    @Test
    fun `repair replaces the key`() = runTest {
        val old = ByteArray(16) { 0x11 }
        box.deviceKey = old
        vault.active = old
        assertTrue(session.authenticate() is AuthOutcome.Verified)
        assertTrue(session.repair() is AuthOutcome.Paired)
        // A new random key, the same on both sides; the old one kept as backup
        assertArrayEquals(box.deviceKey, vault.active)
        assertFalse(old.contentEquals(vault.active))
        assertArrayEquals(old, vault.backup)
    }

    @Test
    fun `reports the box sends on its own reach the app`() = runTest {
        box.report(Cmd.INFO_REPORT, 0, Tlv.encode(Tlv.BATTERY to byteArrayOf(41)))
        assertEquals(41, statuses.last().batteryPercent)
    }

    @Test
    fun `optional features a protocol doesn't implement fail clearly`() = runTest {
        val minimal = object : ProtocolSession {
            override fun onData(bytes: ByteArray) = Unit
            override fun close() = Unit
            override suspend fun authenticate(): AuthOutcome = AuthOutcome.Failed(null, "test")
            override suspend fun command(command: CarCommand): CommandAck = CommandAck.Done
        }
        try {
            minimal.readRssi()
            fail("expected UnsupportedFeatureException")
        } catch (_: UnsupportedFeatureException) {
        }
    }
}
