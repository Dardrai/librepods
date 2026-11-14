/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class RootHeadTrackerProbeTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val initial = SpatialAudioAccess.State(true, true, true, true, true, true,
        true, true, false, true, false, -1, true)
    private class Owner : IPlaybackRecoveryOwner.Stub() {
        @Volatile var current = true
        override fun isCurrent() = current
    }
    private class Events : IHeadTrackerEvents.Stub() {
        val stopped = CopyOnWriteArrayList<String>()
        val reporting = CopyOnWriteArrayList<Boolean>()
        override fun onReportingChanged(enabled: Boolean, intervalNanos: Long) { reporting += enabled }
        override fun onStopped(reason: String) { stopped += reason }
    }
    private class Sink : HeadTrackerSinkSession {
        @Volatile override var reporting = RootHeadTrackerSink.Reporting(false, 10_000_000)
        @Volatile override var halHandshake = true
        @Volatile override var isClosed = false
        override val stopReason: String? get() = if (isClosed) "closed" else null
        @Volatile var delayClose = false
        override fun close() { if (!delayClose) isClosed = true }
    }
    private inner class Platform : SpatialAudioAccess.Platform {
        @Volatile var state = initial
        val writes = CopyOnWriteArrayList<Boolean>()
        @Volatile var throwAfterEnable = false
        override fun read(address: String): SpatialAudioAccess.State = state
        override fun setDeviceEnabled(address: String, enabled: Boolean) = error("device writes forbidden")
        override fun setHeadTrackerEnabled(address: String, enabled: Boolean) {
            assertEquals(this@RootHeadTrackerProbeTest.address, address)
            writes += enabled
            state = state.copy(headTrackerEnabled = enabled)
            if (enabled && throwAfterEnable) throw SecurityException("private platform detail")
        }
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_500_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for bounded lease", condition())
    }
    private fun probe(platform: Platform, sink: Sink = Sink(), permitted: () -> Boolean = { true },
        mutate: (Boolean, () -> Unit) -> Unit = { _, action -> action() }) =
        RootHeadTrackerProbe(platform, { _, _, _ -> sink }, mutate, permitted) { System.nanoTime() / 1_000_000 }

    @Test fun startRequiresDurationSpatialAndLiveOwnerAndClosesRejectedFd() {
        val platform = Platform(); val probe = probe(platform); val owner = Owner(); val events = Events()
        val invalid = ParcelFileDescriptor.createPipe()
        val descriptor = invalid[0].fileDescriptor
        assertFalse(probe.start(address, owner, invalid[0], events, 31).getBoolean("ready"))
        assertFalse(descriptor.valid()); invalid[1].close()
        owner.current = false
        val pipe = ParcelFileDescriptor.createPipe()
        assertFalse(probe.start(address, owner, pipe[0], events, 5).getBoolean("ready"))
        assertTrue(platform.writes.isEmpty()); pipe[1].close(); probe.close()
        for (state in listOf(initial.copy(enabled = false), initial.copy(deviceEnabled = false),
            initial.copy(routeSelected = false), initial.copy(nativePair = false))) {
            platform.state = state
            val blocked = probe(platform); val blockedPipe = ParcelFileDescriptor.createPipe()
            assertFalse(blocked.start(address, Owner(), blockedPipe[0], Events(), 5).getBoolean("ready"))
            assertTrue(platform.writes.isEmpty()); blockedPipe[1].close(); blocked.close()
        }
    }

    @Test fun handshakeAndSelectedHeadPresenceAreBothRequiredBeforeEnable() {
        val platform = Platform(); val owner = Owner(); val sink = Sink().apply { halHandshake = false }
        val started = AtomicBoolean()
        val probe = RootHeadTrackerProbe(platform, { _, _, _ -> started.set(true); sink },
            { _, action -> action() }, { true }) { System.nanoTime() / 1_000_000 }
        val pipe = ParcelFileDescriptor.createPipe()
        val opener = Thread { probe.start(address, owner, pipe[0], Events(), 5) }.apply { start() }
        waitUntil { started.get() }; assertTrue(platform.writes.isEmpty())
        owner.current = false; opener.join(2_000)
        assertFalse(opener.isAlive); assertTrue(platform.writes.isEmpty()); assertTrue(sink.isClosed)
        pipe[1].close(); probe.close()
        platform.state = initial.copy(headTrackerPresent = false)
        val noHead = probe(platform); val noHeadPipe = ParcelFileDescriptor.createPipe()
        assertFalse(noHead.start(address, Owner(), noHeadPipe[0], Events(), 5).getBoolean("ready"))
        assertTrue(platform.writes.isEmpty()); noHeadPipe[1].close(); noHead.close()
    }

    @Test fun explicitFinishIsOwnerScopedAndRestoresAfterOwnerAndRouteDisappear() {
        val platform = Platform(); val owner = Owner(); val events = Events(); val probe = probe(platform)
        val pipe = ParcelFileDescriptor.createPipe()
        val ready = probe.start(address, owner, pipe[0], events, 5)
        assertTrue(ready.getBoolean("ready")); assertTrue(ready.getBoolean("hal_handshake"))
        assertFalse(ready.getBoolean("head_tracker_available")); assertEquals(-1, ready.getInt("actual_head_tracking_mode"))
        assertEquals("no_owned_probe", probe.finish(Owner()).getString("reason"))
        owner.current = false
        platform.state = platform.state.copy(routeSelected = false, nativePair = false, enabled = false)
        val finished = probe.finish(owner)
        assertTrue(finished.getBoolean("cleanup_complete")); assertTrue(finished.getBoolean("restored"))
        assertFalse(platform.state.headTrackerEnabled!!); assertFalse(platform.state.enabled)
        assertEquals(listOf(true, false), platform.writes)
        probe.finish(owner); assertEquals(listOf(true, false), platform.writes)
        waitUntil { events.stopped.size == 1 }; pipe[1].close(); probe.close()
    }

    @Test fun lateSetterFailureStillRestoresAndNeverReturnsReady() {
        val platform = Platform().apply { throwAfterEnable = true }; val probe = probe(platform)
        val pipe = ParcelFileDescriptor.createPipe()
        val result = probe.start(address, Owner(), pipe[0], Events(), 5)
        assertFalse(result.getBoolean("ready")); assertTrue(result.getBoolean("restored"))
        assertEquals(listOf(true, false), platform.writes)
        pipe[1].close(); probe.close()
    }

    @Test fun deadlineSinkFailureAndRootCloseRestoreWithoutAClientFinish() {
        for (trigger in listOf("deadline", "sink", "root", "permission")) {
            val platform = Platform(); val sink = Sink(); val owner = Owner(); val events = Events()
            val permitted = AtomicBoolean(true)
            val probe = probe(platform, sink, permitted::get)
            val pipe = ParcelFileDescriptor.createPipe()
            assertTrue(probe.start(address, owner, pipe[0], events, 1).getBoolean("ready"))
            when (trigger) {
                "sink" -> sink.isClosed = true
                "root" -> probe.close()
                "permission" -> permitted.set(false)
            }
            waitUntil { probe.state(owner).getBoolean("cleanup_complete") }
            assertEquals(listOf(true, false), platform.writes)
            waitUntil { events.stopped.size == 1 }; pipe[1].close(); probe.close()
        }
    }

    @Test fun externalPreferenceChangeIsPreservedAndOldOwnerCannotCloseReplacement() {
        val platform = Platform(); val owner = Owner()
        val probe = RootHeadTrackerProbe(platform, { _, _, _ -> Sink() },
            { _, action -> action() }, { true }) { System.nanoTime() / 1_000_000 }
        val pipe = ParcelFileDescriptor.createPipe()
        assertTrue(probe.start(address, owner, pipe[0], Events(), 5).getBoolean("ready"))
        platform.state = platform.state.copy(deviceEnabled = false, enabled = false)
        val result = probe.finish(owner)
        assertEquals("state_changed", result.getString("restore_reason")); assertEquals(listOf(true), platform.writes)
        platform.state = initial
        val next = Owner(); val nextPipe = ParcelFileDescriptor.createPipe()
        assertTrue(probe.start(address, next, nextPipe[0], Events(), 5).getBoolean("ready"))
        assertEquals("no_owned_probe", probe.finish(owner).getString("reason"))
        assertTrue(probe.state(next).getBoolean("ready")); probe.finish(next)
        pipe[1].close(); nextPipe[1].close(); probe.close()
    }

    @Test fun failedRestoreKeepsOwnershipUntilFinishRetriesAndDelayedSinkCanBeReaped() {
        val platform = Platform(); val owner = Owner(); val cleanupAllowed = AtomicBoolean(false)
        val sink = Sink().apply { delayClose = true }
        val probe = probe(platform, sink, mutate = { cleanup, action ->
            if (cleanup && !cleanupAllowed.get()) throw IllegalStateException("busy")
            action()
        })
        val pipe = ParcelFileDescriptor.createPipe()
        assertTrue(probe.start(address, owner, pipe[0], Events(), 5).getBoolean("ready"))
        assertFalse(probe.finish(owner).getBoolean("cleanup_complete"))
        val otherPipe = ParcelFileDescriptor.createPipe()
        assertEquals("busy", probe.start(address, Owner(), otherPipe[0], Events(), 5).getString("reason"))
        cleanupAllowed.set(true)
        assertFalse(probe.finish(owner).getBoolean("cleanup_complete"))
        assertEquals(listOf(true, false), platform.writes)
        sink.isClosed = true
        assertTrue(probe.state(owner).getBoolean("cleanup_complete"))
        pipe[1].close(); otherPipe[1].close(); probe.close()
    }

    @Test fun blockedAppOwnerCannotHoldSensorOrPreferenceCleanupAndCannotSpawnReplacementPollers() {
        val platform = Platform(); val sink = Sink(); val probe = probe(platform, sink)
        val calls = AtomicInteger(); val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = object : IPlaybackRecoveryOwner.Stub() {
            override fun isCurrent(): Boolean {
                if (calls.incrementAndGet() > 1) {
                    blocked.countDown()
                    // Binder waits cannot be cancelled by Thread.interrupt().
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                }
                return true
            }
        }
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            assertTrue(probe.start(address, owner, pipe[0], Events(), 1).getBoolean("ready"))
            assertTrue(blocked.await(1, TimeUnit.SECONDS))
            waitUntil { sink.isClosed && platform.writes.toList() == listOf(true, false) }
            val stopped = probe.state(owner)
            assertTrue(stopped.getBoolean("sink_closed")); assertTrue(stopped.getBoolean("restored"))
            assertFalse(stopped.getBoolean("owner_poller_retired")); assertFalse(stopped.getBoolean("cleanup_complete"))
            val replacement = ParcelFileDescriptor.createPipe()
            try {
                assertEquals("busy", probe.start(address, Owner(), replacement[0], Events(), 1).getString("reason"))
                assertEquals(2, calls.get())
            } finally { replacement[1].close() }
            release.countDown()
            waitUntil { probe.state(owner).getBoolean("cleanup_complete") }
        } finally { release.countDown(); pipe[1].close(); probe.close() }
    }
}
