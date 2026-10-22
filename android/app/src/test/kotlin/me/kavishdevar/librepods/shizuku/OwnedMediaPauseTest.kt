package me.kavishdevar.librepods.shizuku

import org.junit.Assert.*
import org.junit.Test

class OwnedMediaPauseTest {
    @Test fun onlyTheAcknowledgedOwnedPauseCanResume() {
        val claim = PauseClaim()
        assertFalse(claim.mayResume())
        claim.observe(true)
        assertTrue(claim.mayResume())
        claim.observe(false)
        claim.observe(true)
        assertFalse("A newer play/pause must not restore an old resume permission", claim.mayResume())
    }

    @Test fun anUnrelatedPlaybackChangeBeforeOurPauseCannotBeOwned() {
        val claim = PauseClaim()
        claim.observe(false)
        claim.observe(true)
        assertFalse(claim.mayResume())
    }
}
