package me.kavishdevar.librepods.shizuku

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioBridgeRulesTest {
    @Test fun refusesOtherCallersAndPrivilegedUids() {
        AudioBridgeRules.requireCaller(10123, 10123)
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.requireCaller(10123, 10124) }
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.requireCaller(2000, 2000) }
    }

    @Test fun leasesTrustOnlyTheExactSelectedBackend() {
        assertTrue(AudioBridgeRules.helperOwnsRequest(0, 0))
        assertTrue(AudioBridgeRules.helperOwnsRequest(2000, 2000))
        assertFalse(AudioBridgeRules.helperOwnsRequest(0, 2000))
        assertFalse(AudioBridgeRules.helperOwnsRequest(2000, 0))
        assertFalse(AudioBridgeRules.helperOwnsRequest(null, 0))
        assertFalse(AudioBridgeRules.helperOwnsRequest(10001, 10001))
    }

    @Test fun ordinaryConnectDoesNotRequestRootWithoutAuthorizationOrAfterDenial() {
        assertFalse(AudioBridgeRules.mayReconnectRoot(null, false))
        assertFalse(AudioBridgeRules.mayReconnectRoot(false, false))
        assertFalse(AudioBridgeRules.mayReconnectRoot(false, true))
        assertTrue(AudioBridgeRules.mayReconnectRoot(null, true))
        assertTrue(AudioBridgeRules.mayReconnectRoot(true, false))
        assertFalse(AudioBridgeRules.mayReconnectRoot(null, true, attempted = true))
        assertFalse(AudioBridgeRules.mayReconnectRoot(false, true, attempted = true))
        assertTrue(AudioBridgeRules.mayReconnectRoot(true, true, attempted = true))
    }
}
