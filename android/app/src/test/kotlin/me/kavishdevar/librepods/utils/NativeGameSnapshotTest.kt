/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class NativeGameSnapshotTest {
    private fun snapshot() = LongArray(79).apply {
        this[0] = 1; this[4] = 1; this[17] = 65535; this[31] = 65535; this[45] = 10
        this[61] = 1; this[71] = 10; this[72] = 10
    }

    @Test fun fixedVersionAndBoundsAreRequired() {
        assertNotNull(NativeGameSnapshot.parse(snapshot()))
        assertNull(NativeGameSnapshot.parse(snapshot().copyOf(52)))
        assertNull(NativeGameSnapshot.parse(snapshot().copyOf(56)))
        for ((index, value) in listOf(0 to 2L, 1 to 2L, 3 to 12L, 4 to 4L, 7 to 8L,
                10 to 2048L, 35 to 64L, 36 to 256L, 45 to 8L, 46 to 501L, 5 to -1L,
                56 to 2L, 57 to 2L, 58 to -1L, 59 to -1L, 60 to -1L, 61 to 0L,
                62 to 7L, 63 to -1L, 64 to -1L, 65 to 256L, 71 to 8L, 72 to 12L, 73 to 501L)) {
            assertNull("index $index", NativeGameSnapshot.parse(snapshot().apply { this[index] = value }))
        }
    }

    @Test fun negativeClockProgressIsPermittedAndInputIsCopied() {
        val source = snapshot().apply { this[47] = -480; this[76] = -480 }
        val parsed = NativeGameSnapshot.parse(source)!!
        source[1] = 1
        assertFalse(parsed.supported)
    }

    @Test fun writeAcknowledgementAloneCannotProveRestoration() {
        val source = snapshot().apply { this[9] = 1; this[10] = 0; this[11] = 1; this[12] = 0 }
        assertTrue(NativeGameSnapshot.parse(source)!!.restored)
        source[13] = 1
        assertFalse(NativeGameSnapshot.parse(source)!!.restored)
        source[13] = 0; source[11] = 0
        assertFalse(NativeGameSnapshot.parse(source)!!.restored)
        source[11] = 1; source[56] = 1
        assertFalse(NativeGameSnapshot.parse(source)!!.restored)
        source[56] = 0; source[57] = 1
        assertFalse(NativeGameSnapshot.parse(source)!!.restored)
        source[57] = 0; source[72] = 9
        assertFalse(NativeGameSnapshot.parse(source)!!.restored)
    }

    @Test fun stalePrepareBeforeAnyControllerOperationIsANoopRatherThanARestoration() {
        val source = snapshot().apply {
            this[1] = 1; this[3] = 9; this[4] = 3; this[5] = 29; this[6] = 7; this[7] = 3
            this[54] = 255; this[55] = 65535
        }
        val failed = NativeGameSnapshot.parse(source)!!
        assertTrue(failed.noControllerMutation)
        assertFalse(failed.restored)
        assertTrue(failed.summary().contains("restored=false noControllerMutation=true"))
        // A different operation/error, completed read, captured baseline, or clock activity
        // cannot use this narrow proof to discard potentially outstanding cleanup.
        for ((index, value) in listOf(1 to 0L, 2 to 1L, 3 to 10L, 4 to 1L, 5 to 0L, 6 to 0L,
                7 to 4L, 9 to 1L, 11 to 1L, 13 to 1L, 53 to 0xc27L, 54 to 0L, 55 to 64L,
                56 to 1L, 57 to 1L, 59 to 1L, 62 to 4L, 63 to 1L, 72 to 9L)) {
            assertFalse("index $index", NativeGameSnapshot.parse(source.copyOf().apply {
                this[index] = value
            })!!.noControllerMutation)
        }
    }

    @Test fun hostTupleRequiresExactSelectedPeerAndRoute() {
        val source = snapshot().apply {
            this[24] = 4; this[25] = 7; this[31] = 11; this[35] = 1
            for (i in 0..5) this[36 + i] = (i + 1).toLong()
        }
        val parsed = NativeGameSnapshot.parse(source)!!
        assertTrue(parsed.sameHost(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertFalse(parsed.sameHost(byteArrayOf(1, 2, 3, 4, 5, 7)))
        source[35] = 0
        assertFalse(NativeGameSnapshot.parse(source)!!.sameHost(parsed.peer))
    }

    @Test fun ownedClockTokenNeverReusesGlobalClockCompletion() {
        val source = snapshot().apply {
            this[43] = 4; this[44] = 900 // A previous/unrelated encoder clock completed.
            this[56] = 1; this[57] = 1; this[59] = 7
        }
        val queued = NativeGameSnapshot.parse(source)!!
        assertTrue(queued.clockAvailable)
        assertTrue(queued.clockPending)
        assertEquals(7L, queued.clockRequestSerial)
        assertEquals(0, queued.clockPhase)
        assertEquals(0L, queued.clockSerial)
        source[57] = 0; source[62] = 4; source[63] = 901; source[64] = 5
        source[72] = 10; source[73] = 90
        val complete = NativeGameSnapshot.parse(source)!!
        assertEquals(7L, complete.clockRequestSerial)
        assertEquals(901L, complete.clockSerial)
        assertEquals(5L, complete.clockEncoderGeneration)
        assertEquals(10, complete.clockInterval)
    }

    @Test fun requestAndClockOwnersAreIndependentImmutableTuples() {
        val source = snapshot().apply {
            this[15] = 4; this[16] = 5; this[17] = 11
            this[26] = 123_000_000; this[28] = 124_000_000
            this[29] = 125_000_000; this[58] = 126_000_000
            for (i in 0..5) { this[18 + i] = (i + 1).toLong(); this[65 + i] = (i + 10).toLong() }
        }
        val parsed = NativeGameSnapshot.parse(source)!!
        assertEquals(4L, parsed.requestMediaGeneration)
        assertEquals(5L, parsed.requestEncoderGeneration)
        assertEquals(11, parsed.requestAclHandle)
        assertEquals(123L, parsed.startedMs)
        assertEquals(124L, parsed.delayReceivedMs)
        assertEquals(125L, parsed.observedMs)
        assertEquals(126L, parsed.leaseDeadlineMs)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), parsed.requestPeer)
        assertArrayEquals(byteArrayOf(10, 11, 12, 13, 14, 15), parsed.clockPeer)
        parsed.requestPeer[0] = 99; parsed.clockPeer[0] = 99
        assertEquals(1, parsed.requestPeer[0].toInt())
        assertEquals(10, parsed.clockPeer[0].toInt())
    }

    @Test fun summaryExplainsStreamingWithoutTimingAndExcludesPeerIdentifiers() {
        val source = snapshot().apply {
            this[24] = 12; this[25] = 15; this[35] = 15; this[43] = 1
            for (i in 0..5) this[36 + i] = (160 + i).toLong()
        }
        val summary = NativeGameSnapshot.parse(source)!!.summary()
        assertTrue(summary.contains("streaming=true"))
        assertTrue(summary.contains("hostFlags=15 reporting=true delayValid=false encoderReady=true"))
        assertTrue(summary.contains("clockAvailable=true mediaGeneration=12 encoderGeneration=15"))
        assertTrue(summary.contains("delay=0 delaySerial=0"))
        assertFalse(summary.contains("peer", ignoreCase = true))
        assertFalse(summary.contains("160"))
        assertFalse(summary.contains("a0", ignoreCase = true))
    }
}
