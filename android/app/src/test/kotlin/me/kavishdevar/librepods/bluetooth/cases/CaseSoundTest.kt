package me.kavishdevar.librepods.bluetooth.cases

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaseSoundTest {
    @Test fun routesHaveDistinctExactCommandsAndTypedResponses() {
        assertArrayEquals(byteArrayOf(0, 3), CaseSoundRoute.DULT.command(false))
        assertArrayEquals(byteArrayOf(1, 3), CaseSoundRoute.DULT.command(true))
        assertArrayEquals(byteArrayOf(1, 0, 2), CaseSoundRoute.HAWKEYE.command(false))
        assertArrayEquals(byteArrayOf(1, 1, 3), CaseSoundRoute.NON_OWNER.command(true))
        assertEquals(CaseSoundResponse.Acknowledged(false, 0), CaseSoundRoute.DULT.response(byteArrayOf(2, 3, 0, 3, 0, 0)))
        assertEquals(CaseSoundResponse.Acknowledged(true, 65535), CaseSoundRoute.HAWKEYE.response(byteArrayOf(1, 11, 2, 1, 2, -1, -1)))
        assertEquals(CaseSoundResponse.Completed, CaseSoundRoute.DULT.response(byteArrayOf(3, 3)))
        assertEquals(CaseSoundResponse.Completed, CaseSoundRoute.HAWKEYE.response(byteArrayOf(1, 13, 2)))
        assertNull(CaseSoundRoute.NON_OWNER.response(byteArrayOf(1, 11, 2, 0, 2, 0, 0)))
        assertNull(CaseSoundRoute.DULT.response(byteArrayOf(1, 13, 2)))
        assertNull(CaseSoundRoute.DULT.response(byteArrayOf(2, 3, 2, 3, 0, 0)))
        assertNull(CaseSoundRoute.DULT.response(byteArrayOf(2, 3, 0, 3, 0)))
        assertNull(CaseSoundRoute.HAWKEYE.response(byteArrayOf(1, 13, 2, 0)))
    }

    @Test fun identityAndSubscriptionMustMatchExactly() {
        val pnp = byteArrayOf(1, 0x4c, 0, 0x29, 0x20, 1, 0)
        assertTrue(verifiedPro3Case(pnp))
        pnp[3] = 0x28
        assertFalse(verifiedPro3Case(pnp))
        assertFalse(verifiedPro3Case(pnp.copyOf(6)))
        assertTrue(validCaseCccd(byteArrayOf(3, 0)))
        assertFalse(validCaseCccd(byteArrayOf(4, 0)))
        assertFalse(validCaseCccd(byteArrayOf(0)))
    }

    @Test(timeout = 5_000) fun deadlineIncludesWaitingForStartAndStopsOnce() = runBlocking<Unit> {
        var starts = 0; var stops = 0
        val never = CompletableDeferred<Unit>()
        runCaseSoundWindow({ true }, { starts++; never.await() }, { stops++ })
        assertEquals(1, starts); assertEquals(1, stops)
    }

    @Test fun cancellationAndStartFailureStillStopButUnsafeOrCancelledWorkNeverStarts() = runBlocking<Unit> {
        var starts = 0; var stops = 0
        val started = CompletableDeferred<Unit>()
        val job = launch { runCaseSoundWindow({ true }, { starts++; started.complete(Unit) }, { delay(10); stops++ }) }
        started.await(); job.cancelAndJoin()
        assertEquals(1, starts); assertEquals(1, stops)
        runCatching { runCaseSoundWindow({ true }, { starts++; error("write") }, { stops++ }) }
        assertEquals(2, starts); assertEquals(2, stops)
        runCatching { runCaseSoundWindow({ false }, { starts++ }, { stops++ }) }
        assertEquals(2, starts); assertEquals(2, stops)
        val cancelled = launch { runCaseSoundWindow({ true }, { starts++ }, { stops++ }) }
        cancelled.cancelAndJoin()
        assertEquals(2, starts); assertEquals(2, stops)
    }

    @Test fun AudioActivityEndsTheWindowWithoutASecondStart() = runBlocking<Unit> {
        var safe = true; var starts = 0; var stops = 0
        runCaseSoundWindow({ safe }, { starts++; safe = false }, { stops++ })
        assertEquals(1, starts); assertEquals(1, stops)
    }
}
