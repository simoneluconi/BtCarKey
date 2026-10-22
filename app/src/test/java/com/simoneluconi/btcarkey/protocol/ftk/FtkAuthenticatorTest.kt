package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.auth.AuthOutcome
import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.protocol.LinkClosedException
import com.simoneluconi.btcarkey.protocol.NoAnswerException
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FtkAuthenticatorTest {
    private val serial = "11:22:33:44:55:66"
    private val logLines = mutableListOf<String>()

    private val box = FakeBox()
    private val vault = MemoryVault()
    private val session = FrameSession(write = box::receive, log = { logLines += it }).also { box.send = it::receive }

    private fun authenticator(allowPairing: Boolean = true, key: ByteArray = ByteArray(16) { 7 }) = FtkAuthenticator(
        serial = serial,
        channel = session,
        vault = vault,
        log = { logLines += it },
        pairingAllowed = { allowPairing },
        newKey = { key },
    )

    @Test
    fun `bound box and stored key - reuse`() = runTest {
        val key = ByteArray(16) { it.toByte() }
        box.deviceKey = key
        vault.active = key
        val outcome = authenticator().authenticate()
        assertTrue(outcome is AuthOutcome.Verified)
        assertFalse((outcome as AuthOutcome.Verified).usedPendingKey)
        assertTrue(box.authenticated)
    }

    @Test
    fun `unbound box - new key is set and saved`() = runTest {
        val newKey = ByteArray(16) { 0x42 }
        val outcome = authenticator(key = newKey).authenticate()
        assertTrue(outcome is AuthOutcome.Paired)
        assertArrayEquals(newKey, box.deviceKey)
        assertArrayEquals(newKey, vault.active)
        assertNull(vault.pending)
        assertTrue(box.authenticated)
    }

    @Test
    fun `lost SET answer - the pending key still opens the box next time`() = runTest {
        val newKey = ByteArray(16) { 0x24 }
        box.loseNextSetAnswer = true
        try {
            authenticator(key = newKey).authenticate()
            fail("expected a timeout")
        } catch (_: NoAnswerException) {
        }
        assertArrayEquals(newKey, vault.pending)
        assertNull(vault.active)

        val outcome = authenticator().authenticate()
        assertTrue(outcome is AuthOutcome.Verified && outcome.usedPendingKey)
        assertArrayEquals(newKey, vault.active)
        assertNull(vault.pending)
    }

    @Test
    fun `wrong stored key is rejected, not overwritten`() = runTest {
        box.deviceKey = ByteArray(16) { 1 }
        vault.active = ByteArray(16) { 2 }
        val outcome = authenticator().authenticate()
        assertTrue(outcome is AuthOutcome.Rejected)
        assertArrayEquals(ByteArray(16) { 2 }, vault.active)
        assertFalse(box.authenticated)
    }

    @Test
    fun `bound box without a stored key`() = runTest {
        box.deviceKey = ByteArray(16) { 1 }
        assertTrue(authenticator().authenticate() is AuthOutcome.MissingKey)
    }

    @Test
    fun `unbound box not chosen by the driver is left alone`() = runTest {
        val outcome = authenticator(allowPairing = false).authenticate()
        assertTrue(outcome is AuthOutcome.PairingNotAllowed)
        assertNull(box.deviceKey)
        assertNull(vault.pending)
    }

    @Test
    fun `repair unbinds and sets a new key, keeping the old one as backup`() = runTest {
        val old = ByteArray(16) { 3 }
        val new = ByteArray(16) { 9 }
        box.deviceKey = old
        vault.active = old
        assertTrue(authenticator().authenticate() is AuthOutcome.Verified)
        val outcome = authenticator(key = new).repair()
        assertTrue(outcome is AuthOutcome.Paired)
        assertArrayEquals(new, box.deviceKey)
        assertArrayEquals(new, vault.active)
        assertArrayEquals(old, vault.backup)
    }

    @Test
    fun `key material never reaches the log`() = runTest {
        val key = ByteArray(16) { 0x5A }
        authenticator(key = key).authenticate()
        val hex = key.joinToString(" ") { "%02x".format(it) }
        assertTrue(logLines.isNotEmpty())
        assertTrue(logLines.none { it.contains(hex) || it.contains(hex.replace(" ", "")) })
    }

    @Test
    fun `commands are answered after authentication`() = runTest {
        authenticator().authenticate()
        val answer = session.request(Cmd.SYSTEM_CTRL, CtrlKey.of(CarCommand.UNLOCK), ByteArray(0))
        assertEquals(0L, answer.errorCode)
        assertEquals(listOf(CtrlKey.of(CarCommand.UNLOCK)), box.commands)
    }

    @Test
    fun `a command the box doesn't answer times out`() = runTest {
        authenticator().authenticate()
        box.answersCommands = false
        try {
            session.request(Cmd.SYSTEM_CTRL, CtrlKey.of(CarCommand.LOCK), ByteArray(0), 4_000)
            fail("expected a timeout")
        } catch (_: NoAnswerException) {
        }
    }

    @Test
    fun `reports from the box reach the unsolicited handler`() = runTest {
        val reports = mutableListOf<Frame>()
        val s = FrameSession(write = box::receive, log = {}, onUnsolicited = { reports += it })
        box.send = s::receive
        box.report(Cmd.INFO_REPORT, 0, Tlv.encode(Tlv.BATTERY to byteArrayOf(40)))
        assertNotNull(reports.singleOrNull { it.cmd == Cmd.INFO_REPORT })
    }

    @Test
    fun `closing the session fails waiting requests`() = runTest {
        box.answersCommands = false
        authenticator().authenticate()
        val waiting = async { runCatching { session.request(Cmd.SYSTEM_CTRL, CtrlKey.of(CarCommand.TRUNK), ByteArray(0)) } }
        yield() // lets the request start and wait
        session.close()
        assertTrue(waiting.await().exceptionOrNull() is LinkClosedException)
    }
}
