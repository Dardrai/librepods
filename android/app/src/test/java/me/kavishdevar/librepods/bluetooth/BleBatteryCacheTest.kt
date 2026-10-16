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
        cache.update(80, true, 70, false, 60, true, 100, 100_000_000)
        val next = cache.update(null, true, 65, false, null, true, 200, 200_000_000)
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
        val first = cache.update(0, true, 100, true, 255, true, 10, 10_000_000)
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
        cache.update(80, true, 70, true, 60, true, 100, 100_000_000)
        cache.clear()
        assertEquals(BleBatteryCache.Snapshot(), cache.snapshot)
        val differentPair = cache.update(null, true, 20, false, null, true, 200, 200_000_000)
        assertNull(differentPair.left.level)
        assertNull(differentPair.case.level)
        assertEquals(20, differentPair.right.level)
    }

    @Test fun anOlderBatchCannotReplaceANewerPercentageOrMarkItsComponentsAbsent() {
        val cache = BleBatteryCache()
        val latest = cache.update(80, true, 70, false, 60, true, 200, 200_000_000)
        assertEquals(latest, cache.update(20, false, null, true, null, true, 100, 100_000_000))
        assertEquals(200L, cache.snapshot.left.updatedAt)
        assertTrue(cache.snapshot.left.chargingNow(200))
        cache.clear()
        assertEquals(20, cache.update(20, false, null, false, null, false, 100, 100_000_000).left.level)
    }

    @Test fun clockCorrectionsDoNotBlockNewRadioReportsOrAcceptOlderRadioReports() {
        val cache = BleBatteryCache()
        cache.update(80, true, 70, false, 60, true, 100_000, 100_000_000)
        val newer = cache.update(79, false, 69, false, 59, false, 90_000, 200_000_000)
        assertEquals(79, newer.left.level)
        assertEquals(90_000L, newer.left.updatedAt)
        assertTrue(newer.left.live(90_000))
        assertEquals(newer, cache.update(20, true, null, false, null, false, 110_000, 150_000_000))
        assertEquals(newer, cache.update(20, true, null, false, null, false, 110_000, 200_000_000))
    }

    @Test fun queuedScanCallbacksAreInvalidAfterStopOrReplacement() {
        val session = BleScanSession()
        val first = session.begin()
        assertTrue(session.isCurrent(first))
        session.end() // Invalidated before Android stopScan, including when it throws.
        assertFalse(session.active)
        assertFalse(session.isCurrent(first))
        val replacement = session.begin()
        assertTrue(session.active)
        assertTrue(session.isCurrent(replacement))
        assertFalse(session.isCurrent(first))
        session.end()
        assertFalse(session.isCurrent(replacement))
    }

    @Test fun adapterOffOrScanFailurePausesScanningUntilRecoveryButStopCancelsRecovery() {
        val session = BleScanSession()
        session.request() // Requested while the adapter is off.
        assertTrue(session.requested)
        assertFalse(session.active)
        val initial = session.begin() // Adapter on.
        session.end() // Adapter off or failed scan retires callbacks, but keeps the request.
        assertTrue(session.requested)
        assertFalse(session.active)
        assertFalse(session.isCurrent(initial))
        val retry = session.begin()
        assertTrue(session.isCurrent(retry))
        session.stop() // Service stop prevents later adapter or retry events restarting it.
        assertFalse(session.requested)
        assertFalse(session.active)
        assertFalse(session.isCurrent(retry))
    }

    @Test fun delayedObservationsKeepTheirRadioAgeAndStaleOrFutureBatchesAreRejected() {
        assertEquals(9_500L, bleObservationTime(10_000, 5_000_000_000, 4_500_000_000, 15_000))
        assertEquals(10_000L, bleObservationTime(10_000, 5_000_000_000, 5_000_000_000, 15_000))
        assertNull(bleObservationTime(30_000, 30_000_000_000, 15_000_000_000, 15_000))
        assertNull(bleObservationTime(10_000, 5_000_000_000, 5_000_000_001, 15_000))
        assertNull(bleObservationTime(10_000, 5_000_000_000, -1, 15_000))
        val cache = BleBatteryCache()
        val observed = bleObservationTime(100_000, 100_000_000_000, 90_000_000_000, 15_000)!!
        val battery = cache.update(80, true, null, false, null, false, observed, 90_000_000_000).left
        assertEquals(90_000L, battery.updatedAt)
        assertFalse(battery.live(180_000)) // Delivery at100s must not extend freshness to190s.
    }
}
