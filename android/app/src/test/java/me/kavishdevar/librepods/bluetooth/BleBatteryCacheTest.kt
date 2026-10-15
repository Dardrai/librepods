package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class BleBatteryCacheTest {
    @Test fun aBindingChangeBetweenVerificationAndCommitRejectsTheOldReport() {
        val preferences = mutableMapOf<String, Any>("mac_address" to "pair-a", "proximity_key_device_address" to "pair-a",
            "IRK" to "irk-a", "ENC_KEY" to "key-a")
        val verified = BleKeyBinding.from(preferences)
        assertTrue(verified.ownsKeys)
        assertTrue(verified.isCurrent(preferences))
        preferences["mac_address"] = "pair-b"
        assertFalse(verified.isCurrent(preferences))
        assertFalse(BleKeyBinding.from(preferences).ownsKeys)
        preferences.putAll(mapOf("proximity_key_device_address" to "pair-b", "IRK" to "irk-b", "ENC_KEY" to "key-b"))
        assertFalse(verified.isCurrent(preferences))
        val replacement = BleKeyBinding.from(preferences)
        assertTrue(replacement.ownsKeys)
        assertTrue(replacement.isCurrent(preferences))
        preferences["ENC_KEY"] = "rotated-key-b"
        assertFalse(replacement.isCurrent(preferences))
    }

    @Test fun aBindingOwnsBothKeysOnlyForItsSelectedIdentity() {
        assertFalse(BleKeyBinding.from(emptyMap<String, Any>()).ownsKeys)
        assertFalse(BleKeyBinding.from(mapOf("mac_address" to "pair-a", "proximity_key_device_address" to "pair-b")).ownsKeys)
        val preferences = mapOf("mac_address" to "pair-a", "proximity_key_device_address" to "pair-a", "IRK" to "a", "ENC_KEY" to "b")
        val snapshot = BleKeyBinding.from(preferences)
        assertFalse(snapshot.isCurrent(preferences + ("IRK" to "changed")))
        assertFalse(snapshot.isCurrent(preferences + ("proximity_key_device_address" to "changed")))
    }

    @Test fun partialReportsRetainOnlyEachComponentsOwnPercentageAndAge() {
        val cache = BleBatteryCache()
        cache.update(80, true, 70, false, 60, true, 100)
        val next = cache.update(null, true, 65, false, null, true, 200)
        assertEquals(80, next.left.level)
        assertEquals(100L, next.left.updatedAt)
        assertFalse(next.left.present)
        assertFalse(next.left.charging)
        assertFalse(next.left.live(200))
        assertEquals(65, next.right.level)
        assertEquals(200L, next.right.updatedAt)
        assertTrue(next.right.live(200))
        assertEquals(60, next.case.level)
        assertEquals(100L, next.case.updatedAt)
        assertFalse(next.case.chargingNow(200))
    }

    @Test fun zeroIsValidUnknownIsNotAndChargingExpires() {
        val cache = BleBatteryCache()
        val first = cache.update(0, true, 100, true, 255, true, 10)
        assertEquals(0, first.left.level)
        assertTrue(first.left.chargingNow(10))
        assertEquals(100, first.right.level)
        assertNull(first.case.level)
        assertNull(first.case.updatedAt)
        assertFalse(first.case.charging)
        assertTrue(first.left.live(90_009))
        assertFalse(first.left.live(90_010))
        assertFalse(first.left.chargingNow(90_010))
        assertFalse(first.left.live(9))
    }

    @Test fun switchingKeyBindingClearsAllRetainedParts() {
        val cache = BleBatteryCache()
        cache.update(80, true, 70, true, 60, true, 100)
        cache.clear()
        assertEquals(BleBatteryCache.Snapshot(), cache.snapshot)
        val differentPair = cache.update(null, true, 20, false, null, true, 200)
        assertNull(differentPair.left.level)
        assertNull(differentPair.case.level)
        assertEquals(20, differentPair.right.level)
    }
}
