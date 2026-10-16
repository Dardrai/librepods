package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test

class DeferredDeviceActionsTest {
    @Test fun endingOrAnsweringACallInvalidatesDeferredGestures() {
        val call = RingingCallSession()
        assertNull(call.token)
        call.update(true)
        val first = call.token!!
        call.update(true)
        assertSame(first, call.token)
        call.update(false) // Both idle and off-hook end answer/reject detection.
        assertFalse(call.consume(first))
        call.update(true)
        val second = call.token!!
        assertNotSame(first, second)
        assertFalse(call.consume(first))
        assertTrue(call.consume(second))
        call.update(true) // A duplicate ringing event must not re-arm an answered gesture.
        assertNull(call.token)
        assertFalse(call.consume(second))
    }

    @Test fun playbackResumeRequiresTheOriginalPairAndExactLiveTransport() {
        val socket = Any()
        val wait = DevicePlaybackRequest("first", socket, 100)
        assertFalse(wait.consume("second", socket, 101))
        assertFalse(wait.consume("first", Any(), 101))
        assertTrue(wait.consume("first", socket, 101))
        assertFalse(wait.consume("first", socket, 102))
    }

    @Test fun cancelledExpiredOrReplacedWaitsCannotResumePlayback() {
        val socket = Any()
        val old = DevicePlaybackRequest("first", socket, 0)
        old.cancel() // Replacement, ear removal, disable, disconnect, or service teardown.
        val replacement = DevicePlaybackRequest("first", socket, 10)
        assertFalse(old.consume("first", socket, 11))
        assertTrue(replacement.consume("first", socket, 11))
        val expired = DevicePlaybackRequest("first", socket, 100)
        assertFalse(expired.consume("first", socket, 15_100))
    }

    @Test fun insertingTheSecondBudStillWaitsForTheSameAudioConnection() {
        val socket = Any()
        val wait = DevicePlaybackRequest("pair", socket, 100)
        assertTrue(awaitingDevicePlayback(true, wait, "pair", socket, 100)) // First bud.
        assertTrue(awaitingDevicePlayback(false, wait, "pair", socket, 200)) // Second bud before A2DP.
        assertTrue(wait.consume("pair", socket, 300)) // Only the connection callback resumes.
        assertFalse(awaitingDevicePlayback(false, wait, "pair", socket, 301))
        assertFalse(awaitingDevicePlayback(false, wait, "other pair", Any(), 301))
    }
}
