package me.kavishdevar.librepods.dongle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DongleLifecycleTest {
    @Test fun retired_attempt_cannot_publish_a_socket_or_session() {
        val lifecycle = DongleLifecycle()
        val old = lifecycle.current()
        var publication = "old"
        val current = lifecycle.retire { publication = "cleared" }
        try { lifecycle.publish(old) { publication = "stale" }; fail() } catch (_: CancellationException) { }
        assertEquals("cleared", publication)
        lifecycle.publish(current) { publication = "new" }
        try { lifecycle.publish(old) { publication = "stale cleanup" }; fail() } catch (_: CancellationException) { }
        assertEquals("new", publication)
    }

    @Test fun retirement_and_publication_share_one_fence() {
        val lifecycle = DongleLifecycle()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val publishing = Thread {
            lifecycle.publish(lifecycle.current()) { entered.countDown(); assertTrue(finish.await(2, TimeUnit.SECONDS)) }
        }
        publishing.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val retiring = Thread { lifecycle.retire { cleaned.countDown() } }
        retiring.start()
        assertEquals(1L, cleaned.count)
        finish.countDown()
        publishing.join(2000); retiring.join(2000)
        assertFalse(publishing.isAlive); assertFalse(retiring.isAlive)
        assertEquals(0L, cleaned.count)
    }
}
