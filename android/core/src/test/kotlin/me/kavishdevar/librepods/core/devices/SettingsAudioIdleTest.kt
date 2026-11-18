/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.core.devices

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SettingsAudioIdleTest {
    @Test fun lockedCheckNeverQueriesTheSystemAndRequiresAnOutsideRead() {
        val monitor = Any()
        var reads = 0
        val idle = SettingsAudioIdle(monitor, { 1L }) {
            assertFalse(Thread.holdsLock(monitor))
            reads++
            true
        }
        synchronized(monitor) { assertFalse(idle.isIdle()) }
        assertEquals(0, reads)
        assertTrue(idle.isIdle())
        synchronized(monitor) { assertTrue(idle.isIdle()) }
        assertEquals(1, reads)
    }

    @Test fun expiredOrBackwardsSnapshotCannotGrantIdle() {
        val monitor = Any()
        var now = 50L
        val idle = SettingsAudioIdle(monitor, { now }) { true }
        assertTrue(idle.isIdle())
        now += 250_000_000
        synchronized(monitor) { assertTrue(idle.isIdle()) }
        now++
        synchronized(monitor) { assertFalse(idle.isIdle()) }
        now = 49
        synchronized(monitor) { assertFalse(idle.isIdle()) }
    }

    @Test fun slowQueryDoesNotBecomeFreshWhenItFinishes() {
        val monitor = Any()
        var now = 1L
        val idle = SettingsAudioIdle(monitor, { now }) { now += 250_000_001; true }
        assertFalse(idle.isIdle())
        synchronized(monitor) { assertFalse(idle.isIdle()) }
    }

    @Test fun failureAndBusyReadReplacePreviousIdle() {
        val monitor = Any()
        var failure = false
        var busy = false
        val idle = SettingsAudioIdle(monitor, { 1L }) {
            if (failure) error("system unavailable")
            !busy
        }
        assertTrue(idle.isIdle())
        busy = true
        assertFalse(idle.isIdle())
        synchronized(monitor) { assertFalse(idle.isIdle()) }
        busy = false
        assertTrue(idle.isIdle())
        failure = true
        assertFalse(idle.isIdle())
        synchronized(monitor) { assertFalse(idle.isIdle()) }
    }

    @Test fun blockedSystemQueryLeavesMotionPublicationFreeAndCannotOverwriteNewerBusyRead() {
        val monitor = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = CountDownLatch(1)
        val reads = AtomicInteger()
        val idle = SettingsAudioIdle(monitor, { 1L }) {
            assertFalse(Thread.holdsLock(monitor))
            if (reads.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                true
            } else false
        }
        val system = Thread { idle.isIdle() }
        val motion = Thread {
            synchronized(monitor) {
                assertFalse(idle.isIdle())
                published.countDown()
            }
        }
        system.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            motion.start()
            assertTrue("motion must not wait for the system query", published.await(2, TimeUnit.SECONDS))
            assertFalse(idle.isIdle())
        } finally {
            release.countDown()
            system.join(2_000)
            if (motion.state != Thread.State.NEW) motion.join(2_000)
        }
        assertFalse(system.isAlive)
        assertFalse(motion.isAlive)
        synchronized(monitor) { assertFalse(idle.isIdle()) }
        assertEquals(2, reads.get())
    }

    @Test fun busyObservationFailsClosedEvenIfANewerQueryStartedWithIdle() {
        val monitor = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val result = java.util.concurrent.atomic.AtomicBoolean(true)
        val idle = SettingsAudioIdle(monitor, { 1L }) {
            if (reads.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                false
            } else true
        }
        val busy = Thread { result.set(idle.isIdle()) }
        busy.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(idle.isIdle())
        } finally { release.countDown(); busy.join(2_000) }
        assertFalse(busy.isAlive)
        assertFalse(result.get())
        synchronized(monitor) { assertFalse(idle.isIdle()) }
        assertTrue("a new outside read can confirm idle again", idle.isIdle())
    }
}
