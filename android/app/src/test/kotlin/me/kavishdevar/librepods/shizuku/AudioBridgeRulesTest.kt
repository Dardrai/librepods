package me.kavishdevar.librepods.shizuku

import org.junit.Assert.assertThrows
import org.junit.Test

class AudioBridgeRulesTest {
    @Test fun refusesOtherCallersAndPrivilegedUids() {
        AudioBridgeRules.requireCaller(10123, 10123)
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.requireCaller(10123, 10124) }
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.requireCaller(2000, 2000) }
    }
}
