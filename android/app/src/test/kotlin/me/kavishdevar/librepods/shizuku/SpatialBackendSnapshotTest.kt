/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SpatialBackendSnapshotTest {
    private val now = 1_000_000_000L
    private val pid = 321
    private fun paused() = LongArray(36).apply {
        this[0] = 1; this[1] = now; this[2] = 1; this[4] = 6; this[5] = 31
        this[6] = 1; this[7] = 5; this[8] = pid.toLong(); this[9] = 780; this[10] = 100
        this[22] = 7; this[23] = 7; this[26] = pid.toLong(); this[35] = 1
    }
    private fun committed(tracking: Boolean = false) = paused().apply {
        this[4] = 126; this[6] = if (tracking) 3 else 1; this[13] = 5
        this[14] = this[6]; this[15] = 1; this[16] = 4; this[17] = now - 100; this[18] = now - 50
    }
    private fun read(value: LongArray?) = SpatialBackendSnapshot.read(value, now, pid)

    @Test fun acceptedPausedChoiceDoesNotClaimAnAudioBufferWasRendered() {
        val value = paused()
        val parsed = read(value)!!
        value[6] = 0
        val snapshot = parsed.bundle()
        assertTrue(snapshot.getBoolean("supported")); assertTrue(snapshot.getBoolean("known"))
        assertTrue(snapshot.getBoolean("desired_spatial")); assertFalse(snapshot.getBoolean("desired_tracking"))
        assertEquals(5L, snapshot.getLong("accepted_generation"))
        assertEquals(0L, snapshot.getLong("applied_generation"))
        assertFalse(snapshot.getBoolean("actual_audio")); assertFalse(snapshot.getBoolean("output_committed"))
        assertFalse(snapshot.getBoolean("applied"))
    }

    @Test fun actualAudioRequiresTheCurrentVerifiedCommittedModeAndTrackingRequiresAPose() {
        assertTrue(read(committed())!!.bundle().getBoolean("actual_audio"))
        assertTrue(read(committed().apply { this[15] = 2 })!!.bundle().getBoolean("actual_audio"))
        val oldMode = committed().apply { this[4] = 62; this[13] = 4 }
        assertFalse(read(oldMode)!!.bundle().getBoolean("actual_audio"))
        assertFalse(read(committed(true))!!.bundle().getBoolean("actual_tracking"))
        assertTrue(read(committed(true).apply { this[4] = this[4] or 128 })!!.bundle().getBoolean("actual_tracking"))
        assertFalse(read(committed().apply { this[3] = 1 })!!.bundle().getBoolean("actual_audio"))
    }

    @Test fun cachedSnapshotsHaveABoundedFreshnessWindowAndExactRootPid() {
        assertNotNull(SpatialBackendSnapshot.read(paused(), now + 2_000_000_000L, pid))
        assertNull(SpatialBackendSnapshot.read(paused(), now + 2_000_000_001L, pid))
        assertNull(SpatialBackendSnapshot.read(paused(), now - 1, pid))
        assertNull(SpatialBackendSnapshot.read(paused(), now, pid + 1))
        assertNull(SpatialBackendSnapshot.read(paused(), now, 0))
        assertNull(SpatialBackendSnapshot.read(paused(), -1, pid))
    }

    @Test fun malformedShapesCountersFlagsAndOwnershipAreRejected() {
        assertNull(read(null)); assertNull(read(LongArray(35))); assertNull(read(LongArray(37)))
        val mutations: List<(LongArray) -> Unit> = listOf(
            { it[0] = 2 }, { it[1] = 0 }, { it[2] = 2 }, { it[3] = -1 }, { it[3] = 4 },
            { it[4] = -1 }, { it[4] = 256 }, { it[4] = 2 }, { it[4] = 4 }, { it[5] = 32 },
            { it[6] = 2 }, { it[7] = 0 }, { it[8] = pid + 1L }, { it[9] = 0 }, { it[10] = 0 },
            { it[9] = Int.MAX_VALUE.toLong() + 1 }, { it[14] = 2 }, { it[15] = 3 },
            { it[22] = 8 }, { it[23] = -1 }, { it[26] = pid + 1L }, { it[35] = 2 },
        )
        for ((index, mutation) in mutations.withIndex()) assertNull("mutation $index", read(paused().also(mutation)))
    }

    @Test fun malformedAudioProofCannotBecomeActualOutput() {
        val mutations: List<(LongArray) -> Unit> = listOf(
            { it[4] = it[4] and 32L.inv() }, { it[4] = it[4] and 8L.inv() }, { it[15] = 0 },
            { it[13] = 0 }, { it[13] = 4 }, { it[14] = 0 }, { it[16] = 0 },
            { it[17] = 0 }, { it[18] = it[17] - 1 },
        )
        for ((index, mutation) in mutations.withIndex()) assertNull("audio mutation $index", read(committed().also(mutation)))
        val unavailable = SpatialBackendSnapshot.unavailable("unsupported")
        assertFalse(unavailable.getBoolean("supported")); assertFalse(unavailable.getBoolean("known"))
        assertFalse(unavailable.getBoolean("applied"))
    }
}
