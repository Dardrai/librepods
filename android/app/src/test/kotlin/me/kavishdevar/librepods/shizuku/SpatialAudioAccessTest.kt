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
class SpatialAudioAccessTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val other = "11:22:33:44:55:66"
    private val initial = SpatialAudioAccess.State(true, false, true, true, true, true,
        true, false, true, true, false, -1, true)
    private class Owner(var current: Boolean = true) : IPlaybackRecoveryOwner.Stub() {
        override fun isCurrent() = current
    }
    private inner class Platform(state: SpatialAudioAccess.State = initial,
        var masterEnabled: Boolean = state.enabled || state.deviceEnabled != true) : SpatialAudioAccess.Platform {
        val devices = mutableMapOf(address to state, other to initial.copy(enabled = true, deviceEnabled = true))
        private fun nativeState(value: SpatialAudioAccess.State) =
            value.copy(enabled = masterEnabled && value.deviceEnabled == true)
        var state: SpatialAudioAccess.State
            get() = nativeState(checkNotNull(devices[address]))
            set(value) { devices[address] = value }
        val writes = mutableListOf<String>()
        val reads = mutableListOf<String>()
        var afterRead: () -> Unit = {}
        var afterHead: () -> Unit = {}
        var afterDevice: () -> Unit = {}
        var denyDevice = false
        override fun read(address: String): SpatialAudioAccess.State {
            reads += address
            val value = nativeState(checkNotNull(devices[address]))
            afterRead()
            return value
        }
        override fun setDeviceEnabled(address: String, enabled: Boolean) {
            if (denyDevice) throw SecurityException("private platform details")
            writes += "device=$enabled"
            devices[address] = checkNotNull(devices[address]).copy(deviceEnabled = enabled,
                enabled = masterEnabled && enabled)
            afterDevice()
        }
        override fun setHeadTrackerEnabled(address: String, enabled: Boolean) {
            writes += "head=$enabled"
            devices[address] = checkNotNull(devices[address]).copy(headTrackerEnabled = enabled)
            afterHead()
        }
    }

    @Test fun malformedAddressesAndRetiredOwnersNeverReachThePlatform() {
        val platform = Platform(); val access = SpatialAudioAccess(platform) { 0 }
        for (invalid in listOf("", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "AA:BB:CC:DD:EE:FF;cmd")) {
            assertEquals("invalid_address", access.setFixedEnabled(invalid, Owner(), true).getString("reason"))
        }
        assertEquals("owner_changed", access.setFixedEnabled(address, Owner(false), true).getString("reason"))
        assertTrue(platform.reads.isEmpty()); assertTrue(platform.writes.isEmpty())
    }

    @Test fun inspectionNormalizesItsAddressAndNeverClaimsThatCapabilityIsRendering() {
        val platform = Platform(); val value = SpatialAudioAccess(platform) { 0 }.inspect(address.lowercase(), Owner())
        assertEquals(listOf(address), platform.reads); assertTrue(platform.writes.isEmpty())
        assertTrue(value.getBoolean("known")); assertTrue(value.getBoolean("can_spatialize_51"))
        assertFalse(value.getBoolean("device_enabled")); assertFalse(value.getBoolean("applied"))
        assertFalse(value.getBoolean("enabled")); assertTrue(value.getBoolean("fixed_supported"))
        assertEquals("ok", value.getString("reason"))
        assertFalse(value.containsKey("rendering")); assertFalse(value.getBoolean("head_tracker_available"))
    }

    @Test fun unknownPlatformDeviceStateAndUnrelatedOrInactiveDevicesCannotAuthorizeAWritingFallback() {
        val cases = listOf(
            initial.copy(supported = false) to "unsupported",
            initial.copy(nativePair = false) to "device_unavailable",
            initial.copy(routeSelected = false) to "route_unavailable",
            initial.copy(compatible = null) to "device_state_unavailable",
            initial.copy(deviceEnabled = null) to "device_state_unavailable",
            initial.copy(headTrackerEnabled = null) to "device_state_unavailable",
            initial.copy(compatible = false) to "device_incompatible",
            initial.copy(mutationApi = false) to "method_unavailable",
            initial.copy(deviceEnabled = true) to "global_disabled",
            initial.copy(canSpatialize51 = false) to "format_unavailable")
        for ((state, reason) in cases) {
            val platform = Platform(state)
            val value = SpatialAudioAccess(platform) { 0 }.setFixedEnabled(address, Owner(), true)
            assertEquals(reason, value.getString("reason")); assertFalse(value.getBoolean("applied"))
            assertTrue(platform.writes.isEmpty()); assertEquals(state, platform.state)
        }
    }

    @Test fun successfulFixedChoiceAndExplicitProbeRestoreTouchOnlyTheCapturedDevice() {
        val platform = Platform(); val access = SpatialAudioAccess(platform) { 0 }; val owner = Owner()
        val otherBefore = platform.devices[other]
        val applied = access.setFixedEnabled(address, owner, true)
        assertTrue(applied.getBoolean("applied")); assertTrue(applied.getBoolean("device_enabled"))
        assertTrue(applied.getBoolean("enabled"))
        assertFalse(applied.getBoolean("head_tracker_enabled"))
        assertEquals(listOf("head=false", "device=true"), platform.writes)
        assertTrue(access.restore(address, owner).getBoolean("restored"))
        assertEquals(initial, platform.state); assertEquals(otherBefore, platform.devices[other])
        assertEquals(listOf("head=false", "device=true", "device=false", "head=true"), platform.writes)
    }

    @Test fun routeChangeBeforeMutationAndAnExpiredOwnerReadCannotWrite() {
        val platform = Platform(); val access = SpatialAudioAccess(platform) { 0 }
        platform.afterRead = { platform.state = platform.state.copy(routeSelected = false) }
        assertEquals("state_changed", access.setFixedEnabled(address, Owner(), true).getString("reason"))
        assertTrue(platform.writes.isEmpty())
        var time = 0L
        val slow = Platform(); slow.afterRead = { time = 3_000 }
        assertEquals("owner_changed", SpatialAudioAccess(slow) { time }.setFixedEnabled(address, Owner(), true).getString("reason"))
        assertTrue(slow.writes.isEmpty())
    }

    @Test fun cancellationBetweenHeadAndDeviceWritesRollsBackTheCapturedHeadFlag() {
        val platform = Platform(); val owner = Owner()
        platform.afterHead = { owner.current = false }
        val value = SpatialAudioAccess(platform) { 0 }.setFixedEnabled(address, owner, true)
        assertEquals("owner_changed", value.getString("reason")); assertFalse(value.getBoolean("applied"))
        assertEquals(initial, platform.state); assertEquals(listOf("head=false", "head=true"), platform.writes)
    }

    @Test fun deniedDeviceWriteRollsBackEarlierHeadChangeAndKeepsPrivateErrorsOutOfTheResult() {
        val platform = Platform(); platform.denyDevice = true
        val value = SpatialAudioAccess(platform) { 0 }.setFixedEnabled(address, Owner(), true)
        assertEquals("permission_denied", value.getString("reason"))
        assertFalse(value.toString().contains("private platform")); assertEquals(initial, platform.state)
        assertEquals(listOf("head=false", "head=true"), platform.writes)
    }

    @Test fun lateMutationReceiptRollsBackWithoutChangingTheGlobalChoice() {
        var time = 0L
        val platform = Platform(initial.copy(headTrackerEnabled = false))
        platform.afterDevice = { time = 3_000; platform.masterEnabled = false }
        val value = SpatialAudioAccess(platform) { time }.setFixedEnabled(address, Owner(), true)
        assertEquals("owner_changed", value.getString("reason"))
        assertFalse(platform.state.deviceEnabled!!); assertFalse(platform.state.enabled)
        assertFalse(platform.masterEnabled)
        assertEquals(listOf("device=true", "device=false"), platform.writes)
    }

    @Test fun otherOwnersAddressesAndExternallyChangedValuesCannotRestoreAnOldSnapshot() {
        val platform = Platform(); val access = SpatialAudioAccess(platform) { 0 }; val owner = Owner()
        assertTrue(access.setFixedEnabled(address, owner, true).getBoolean("applied"))
        assertEquals("no_snapshot", access.restore(address, Owner()).getString("reason"))
        assertEquals("no_snapshot", access.restore(other, owner).getString("reason"))
        owner.current = false
        assertEquals("owner_changed", access.restore(address, owner).getString("reason"))
        owner.current = true
        platform.state = platform.state.copy(headTrackerEnabled = true)
        assertEquals("state_changed", access.restore(address, owner).getString("reason"))
        assertEquals(listOf("head=false", "device=true"), platform.writes)
    }

    @Test fun retiringAnAcceptedChoiceForgetsRestoreHistoryAndPreservesTheSetting() {
        val platform = Platform(); val access = SpatialAudioAccess(platform) { 0 }; val owner = Owner()
        assertTrue(access.setFixedEnabled(address, owner, true).getBoolean("applied"))
        access.retire(owner)
        assertTrue(platform.state.deviceEnabled!!); assertFalse(platform.state.headTrackerEnabled!!)
        assertEquals("no_snapshot", access.restore(address, owner).getString("reason"))
    }

    @Test fun disablingASelectedDeviceDoesNotRequireEnablingGlobalSpatialAudioOrChangingHeadTracking() {
        val platform = Platform(initial.copy(enabled = false, deviceEnabled = true))
        val value = SpatialAudioAccess(platform) { 0 }.setFixedEnabled(address, Owner(), false)
        assertTrue(value.getBoolean("applied")); assertFalse(platform.state.enabled)
        assertTrue(platform.state.headTrackerEnabled!!); assertEquals(listOf("device=false"), platform.writes)
    }

    @Test fun fixedOnOffOnAcceptsNativeEngineTransitionsWithoutUnexpectedRollback() {
        val platform = Platform(initial.copy(headTrackerEnabled = false))
        val access = SpatialAudioAccess(platform) { 0 }; val owner = Owner()
        val nativeTransitions = mutableListOf<Boolean>()
        platform.afterDevice = { nativeTransitions += platform.state.enabled }
        for (enabled in listOf(true, false, true)) {
            val value = access.setFixedEnabled(address, owner, enabled)
            assertTrue(value.getBoolean("applied"))
            assertEquals(enabled, value.getBoolean("device_enabled"))
            assertEquals(enabled, value.getBoolean("enabled"))
            assertTrue(value.getBoolean("fixed_supported"))
            assertEquals("ok", value.getString("reason"))
            val inspected = access.inspect(address, owner)
            assertEquals(enabled, inspected.getBoolean("device_enabled"))
            assertTrue(inspected.getBoolean("fixed_supported"))
        }
        assertEquals(listOf(true, false, true), nativeTransitions)
        assertEquals(listOf("device=true", "device=false", "device=true"), platform.writes)
        assertTrue(platform.masterEnabled)
    }

    @Test fun knownGlobalOffBlocksEnablingWithoutChangingEitherDeviceFlag() {
        val platform = Platform(initial.copy(deviceEnabled = true), masterEnabled = false)
        val access = SpatialAudioAccess(platform) { 0 }
        val value = access.setFixedEnabled(address, Owner(), true)
        assertEquals("global_disabled", value.getString("reason"))
        assertFalse(value.getBoolean("applied")); assertFalse(value.getBoolean("fixed_supported"))
        assertTrue(value.getBoolean("device_enabled")); assertFalse(value.getBoolean("enabled"))
        assertTrue(platform.state.headTrackerEnabled!!)
        assertFalse(platform.masterEnabled); assertTrue(platform.writes.isEmpty())
    }

    @Test fun offDeviceCanEnableItsPreferenceWhileAnUnknownGlobalOffIsReportedHonestly() {
        val platform = Platform(masterEnabled = false)
        val access = SpatialAudioAccess(platform) { 0 }; val owner = Owner()
        assertTrue(access.inspect(address, owner).getBoolean("fixed_supported"))
        val value = access.setFixedEnabled(address, owner, true)
        assertTrue(value.getBoolean("applied")); assertTrue(value.getBoolean("device_enabled"))
        assertFalse(value.getBoolean("enabled")); assertFalse(value.getBoolean("fixed_supported"))
        assertEquals("global_disabled", value.getString("reason"))
        assertFalse(platform.masterEnabled)
        assertEquals(listOf("head=false", "device=true"), platform.writes)
    }

    @Test fun externalGlobalOffDuringEnableDoesNotRollBackTheCorrectDevicePreference() {
        val platform = Platform(initial.copy(headTrackerEnabled = false))
        platform.afterDevice = { platform.masterEnabled = false }
        val value = SpatialAudioAccess(platform) { 0 }.setFixedEnabled(address, Owner(), true)
        assertTrue(value.getBoolean("applied")); assertTrue(value.getBoolean("device_enabled"))
        assertFalse(value.getBoolean("enabled")); assertEquals("global_disabled", value.getString("reason"))
        assertFalse(platform.masterEnabled); assertTrue(platform.state.deviceEnabled!!)
        assertEquals(listOf("device=true"), platform.writes)
    }
}
