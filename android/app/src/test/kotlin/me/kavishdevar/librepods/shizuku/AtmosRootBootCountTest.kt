/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AtmosRootBootCountTest {
    private class Command(val text: String = "38\n", val exit: Int = 0, val waits: Int = 1) : Process() {
        var elapsed = 0L
        var polls = 0
        var destroyed = false
        private val output = ByteArrayInputStream(text.toByteArray())
        override fun getInputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = exit
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            elapsed += unit.toNanos(timeout)
            return ++polls >= waits
        }
        override fun exitValue() = exit
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
        fun read(live: () -> Boolean = { true }) = AtmosRootBootCount.read(live, { this }, { elapsed })
    }

    @Test fun independentlyReadsBootAfterOldHalfSecondWaitAndStartsOnlyOnce() {
        val command = Command(waits = 13)
        var starts = 0
        val boot = AtmosRootBootCount.read({ true }, { starts++; command }, { command.elapsed })
        assertEquals(38, boot)
        assertEquals(1, starts)
        assertEquals(650_000_000L, command.elapsed)
        assertTrue(command.destroyed)
        assertTrue(AtmosRootBootCount.READER_STARTUP_WAIT_MS > 2_000)
        assertTrue(AtmosRootBootCount.READER_STARTUP_WAIT_MS < 5_000)
    }

    @Test fun rejectsFailedMissingMalformedAndOverflowingResults() {
        for (text in listOf("", "null\n", "-1\n", "38 warning", "2147483648", "3\n8", "3".repeat(33))) {
            val command = Command(text)
            assertEquals("boot_count_invalid", assertThrows(IllegalStateException::class.java) { command.read() }.message)
            assertTrue(command.destroyed)
        }
        val failed = Command(exit = 1)
        assertEquals("boot_count_exit", assertThrows(IllegalStateException::class.java) { failed.read() }.message)
        assertTrue(failed.destroyed)
    }

    @Test fun timeoutAndRetirementKillOnlyTheCapturedCommand() {
        val timeout = Command(waits = 100)
        assertEquals("boot_count_timeout", assertThrows(IllegalStateException::class.java) { timeout.read() }.message)
        assertEquals(2_000_000_000L, timeout.elapsed)
        assertTrue(timeout.destroyed)
        val cancelled = Command(waits = 100)
        assertEquals("owner_changed", assertThrows(IllegalStateException::class.java) {
            cancelled.read { cancelled.polls < 2 }
        }.message)
        assertEquals(100_000_000L, cancelled.elapsed)
        assertTrue(cancelled.destroyed)
        var starts = 0
        assertThrows(IllegalStateException::class.java) { AtmosRootBootCount.read({ false }, { starts++; Command() }) }
        assertEquals(0, starts)
    }

    @Test fun firstFailureDetailIsBoundedAndSingleLine() {
        val error = SecurityException("resolver attribution\n\t" + "x".repeat(400))
        val detail = atmosFailureMessage(error)
        assertEquals(240, detail.length)
        assertTrue(detail.startsWith("resolver attribution  "))
        assertFalse(detail.any { it.isISOControl() })
        assertEquals("", atmosFailureMessage(IllegalStateException()))
    }
}
