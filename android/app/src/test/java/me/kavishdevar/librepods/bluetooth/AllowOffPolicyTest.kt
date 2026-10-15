package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class AllowOffPolicyTest {
    @Test fun explicitSelectedPairChoiceAndConfirmedValueHaveDefinedPrecedence() {
        assertFalse(AllowOffPolicy.allowed(2, 1, 1, true, true, 9))
        assertTrue(AllowOffPolicy.allowed(1, 2, 2, true, true, 9))
        assertFalse(AllowOffPolicy.allowed(null, 2, 1, true, true, 9))
        assertTrue(AllowOffPolicy.allowed(null, 1, 2, true, true, 9))
        assertFalse(AllowOffPolicy.allowed(255, 2, 1, true, true, 9))
    }

    @Test fun unknownAndModernAdaptiveModelsDoNotDefaultToOff() {
        assertFalse(AllowOffPolicy.allowed(null, null, null, false, false, null))
        assertFalse(AllowOffPolicy.allowed(null, null, null, true, true, null))
        assertFalse(AllowOffPolicy.allowed(null, null, null, true, true, 9))
        assertTrue(AllowOffPolicy.allowed(null, null, null, true, true, 6))
        assertTrue(AllowOffPolicy.allowed(null, null, null, true, false, null))
        assertTrue(AllowOffPolicy.allowed(null, null, 1, false, false, null))
    }
}
