/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.app.Application
import android.os.Bundle
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.shizuku.AtmosClientLease
import me.kavishdevar.librepods.shizuku.AtmosTrackingLease
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosCoordinatorFailureTest {
    @Test fun firstCapturedFaultSurvivesParentCancellationAndCleanup() = runBlocking {
        val first = AtmosCoordinatorFailure { 1234 }
        val original = CalibratedHeadMotionFault("sensor_gap")
        val observed = runCatching {
            coroutineScope {
                launch { first.capture({ "calibrated_source" }) { throw original } }
                try { first.capture({ "decoder_monitor" }) { awaitCancellation() } }
                finally { first.record("cleanup", IllegalStateException("atmos_output_retired")) }
            }
        }.exceptionOrNull()
        // Coroutine stack recovery may copy the exception while retaining the original as its cause.
        assertTrue(generateSequence(observed) { it.cause }.any { it === original })
        val saved = first.snapshot()!!
        assertEquals("calibrated_source", saved.getString("stage"))
        assertEquals("CalibratedHeadMotionFault", saved.getString("class"))
        assertEquals("sensor_gap", saved.getString("reason"))
        assertEquals(1234L, saved.getLong("at_ms"))
    }

    @Test fun cancellationIsNotAProducerFaultAndCapturedRunCannotOverwriteNewRun() = runBlocking {
        val old = AtmosCoordinatorFailure { 10 }
        val current = AtmosCoordinatorFailure { 20 }
        val cancelled = CancellationException("arbitrary cancellation text")
        assertSame(cancelled, runCatching {
            old.captureProducer { throw cancelled }
        }.exceptionOrNull())
        assertNull(old.snapshot())
        old.record("calibrated_source", IllegalStateException("owner_changed"))
        assertNull(current.snapshot())
    }

    @Test fun brokenPipeBetweenMonitorReadsRetainsRootFailureBeforeProducerRethrow() = runBlocking {
        val failure = AtmosCoordinatorFailure { 10 }
        val pipeError = ErrnoException("write", OsConstants.EPIPE)
        val firstRead = CompletableDeferred<Unit>()
        val nativeFailure = LongArray(36).apply { this[0] = 1; this[1] = 1; this[19] = 321; this[20] = 7 }
        var rootFailed = false
        val remote = mock(AtmosTrackingLease::class.java)
        `when`(remote.state()).thenAnswer {
            rootState(!rootFailed).apply {
                if (rootFailed) {
                    putString("reason", "native_offer_rejected"); putString("failure_stage", "reader_pose_pipe")
                    putLongArray("native_failure_diagnostic", nativeFailure)
                }
            }
        }
        val lease = AtmosClientLease(remote) {}
        var retained = Bundle()
        val observed = runCatching {
            try {
                coroutineScope {
                    val sender = launch { failure.captureProducer {
                        firstRead.await(); rootFailed = true; throw pipeError
                    } }
                    failure.readRootState(sender, lease::state) { retained = it }
                    assertTrue(retained.getBoolean("live"))
                    firstRead.complete(Unit); sender.join()
                    assertTrue(coroutineContext.isActive)
                    failure.readRootState(sender, lease::state) { retained = it }
                }
            } finally {
                lease.close()
                retained = retiredAtmosCoordinatorState(retained, "experiment_failed")
            }
        }.exceptionOrNull()
        assertTrue(generateSequence(observed) { it.cause }.any { it === pipeError })
        assertEquals("errno_${OsConstants.EPIPE}", failure.snapshot()!!.getString("reason"))
        assertEquals("native_offer_rejected", retained.getString("reason"))
        assertEquals("reader_pose_pipe", retained.getString("failure_stage"))
        assertArrayEquals(nativeFailure, retained.getLongArray("native_failure_diagnostic"))
        assertRetired(retained)
        verify(remote, times(2)).state(); verify(remote).retire()
    }

    @Test fun failedRootReadKeepsOldEvidenceHistoricalWithoutInventingTerminalRootState() = runBlocking {
        val failure = AtmosCoordinatorFailure { 10 }
        val remote = mock(AtmosTrackingLease::class.java)
        val unavailable = IllegalStateException("root unavailable")
        `when`(remote.state()).thenReturn(rootState(true)).thenThrow(unavailable)
        val lease = AtmosClientLease(remote) {}
        var retained = lease.state()
        failure.captureProducer { throw ErrnoException("write", OsConstants.EPIPE) }
        val observed = runCatching {
            try {
                failure.readRootState(Job().apply { complete() }, lease::state) { retained = it }
            } finally {
                lease.close()
                retained = retiredAtmosCoordinatorState(retained, "experiment_failed")
            }
        }.exceptionOrNull()
        assertSame(unavailable, observed)
        assertEquals("errno_${OsConstants.EPIPE}", failure.snapshot()!!.getString("reason"))
        assertEquals(4L, retained.getLongArray("native_status")!![1])
        assertFalse(retained.containsKey("native_failure_diagnostic"))
        assertFalse(retained.containsKey("failure_stage"))
        assertRetired(retained)
        verify(remote, times(2)).state(); verify(remote).retire()
    }

    @Test fun producerCompletionDuringRootReadRequiresAnotherReadBeforeRethrow() = runBlocking<Unit> {
        val failure = AtmosCoordinatorFailure { 10 }
        val pipeError = ErrnoException("write", OsConstants.EPIPE)
        failure.captureProducer { throw pipeError }
        val sender = Job()
        val remote = mock(AtmosTrackingLease::class.java)
        `when`(remote.state()).thenAnswer { sender.complete(); rootState(true) }.thenReturn(rootState(false))
        val lease = AtmosClientLease(remote) {}
        var retained = Bundle()
        try {
            failure.readRootState(sender, lease::state) { retained = it }
            assertTrue(retained.getBoolean("live"))
            assertSame(pipeError, runCatching {
                failure.readRootState(sender, lease::state) { retained = it }
            }.exceptionOrNull())
            assertFalse(retained.getBoolean("live"))
            verify(remote, times(2)).state()
        } finally { lease.close() }
    }

    private fun rootState(live: Boolean) = Bundle().apply {
        putBoolean("live", live); putBoolean("can_stream", live); putBoolean("playback_verified", live)
        putBoolean("handoff_pending", live); putBoolean("handoff_authorizable", live)
        putLongArray("native_status", LongArray(20).apply { this[0] = 1; this[1] = if (live) 4L else 5L })
    }

    private fun assertRetired(state: Bundle) {
        for (key in listOf("live", "can_stream", "playback_verified", "handoff_pending", "handoff_authorizable"))
            assertFalse(key, state.getBoolean(key))
        assertTrue(state.getBoolean("root_state_historical"))
    }

    @Test fun arbitraryMessagesAreRedactedButPipeErrnoRemainsUseful() {
        val first = AtmosCoordinatorFailure { 10 }
        first.record("decoder_monitor", IllegalStateException("nonce=abcdef raw_pose=1,2,3"))
        assertEquals("unclassified", first.snapshot()!!.getString("reason"))
        val pipe = AtmosCoordinatorFailure { 20 }
        pipe.record("calibrated_source", ErrnoException("arbitrary details", OsConstants.EPIPE))
        assertEquals("errno_${OsConstants.EPIPE}", pipe.snapshot()!!.getString("reason"))
    }

    @Test fun monitorFaultKeepsItsOriginalObjectAndFirstStage() = runBlocking {
        val first = AtmosCoordinatorFailure { 10 }
        val original = IllegalStateException("decoder_replaced_or_retired")
        assertSame(original, runCatching {
            first.capture({ "decoder_monitor" }) { throw original }
        }.exceptionOrNull())
        first.record("coordinator", IllegalStateException("root_lease_retired"))
        assertEquals("decoder_monitor", first.snapshot()!!.getString("stage"))
        assertEquals("decoder_replaced_or_retired", first.snapshot()!!.getString("reason"))
    }
}
