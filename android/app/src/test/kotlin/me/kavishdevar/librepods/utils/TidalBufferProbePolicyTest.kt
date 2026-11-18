/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TidalBufferProbePolicyTest {
    @Test fun retainedProductionPacesByDefaultAndExplicitComparisonCanBypassIt() {
        val policy = TidalBufferProbePolicy
        assertTrue(policy.paceRetainedTrack(true, true, false, false, false))
        assertFalse(policy.paceRetainedTrack(false, true, false, false, false))
        assertFalse(policy.paceRetainedTrack(true, false, false, false, false))
        assertFalse(policy.paceRetainedTrack(true, true, true, true, true))
        assertFalse(policy.paceRetainedTrack(true, true, false, true, false))
        assertTrue(policy.paceRetainedTrack(true, true, false, true, true))
    }
    private val permit = TidalBufferProbePolicy.Permit(1000, 61000, 9, 780, "02:00:00:00:00:01")
    private val format = TidalBufferProbePolicy.Format("c2.pixel.eac3.float.decoder", "audio/raw", 4, 2, 48000,
        0, false, true, false)
    private fun size(p: TidalBufferProbePolicy.Permit? = permit, now: Long = 2000, boot: Int = 9,
        sdk: Int = 37, version: String? = "2.219.0", code: Long = 10023,
        f: TidalBufferProbePolicy.Format = format, minimum: Int = 3840, used: Boolean = false) =
        TidalBufferProbePolicy.bytes(p, now, boot, sdk, version, code, f, minimum, used)

    @Test fun passiveWindowNeedsTheSameBootAndProductionChoiceAndACurrentLiveGrant() {
        val claim = TidalComponentClaimPermit("a".repeat(32), 1100, 60000, 9, 10414, 1000)
        val window = TidalBufferProbePolicy.Observation(2000, 50000, 9, 7)
        fun live(value: TidalBufferProbePolicy.Observation = window, buffer: TidalBufferProbePolicy.Permit? = permit,
            owner: TidalComponentClaimPermit? = claim, now: Long = 3000, boot: Int = 9, uid: Int = 10414,
            production: Boolean = true, epoch: Long = 7) = value.live(buffer, owner, now, boot, uid, production, epoch)
        assertTrue(live()); assertTrue(live(now = 2000))
        assertFalse(live(now = 1999)); assertFalse(live(now = 50000))
        assertFalse(live(buffer = null)); assertFalse(live(owner = null))
        assertFalse(live(buffer = permit.copy(issuedMs = 1001))) // Mismatched grant, not a new attestation.
        val replacement = permit.copy(issuedMs = 2500)
        assertTrue(live(buffer = replacement, owner = claim.copy(issuedMs = 2500, bufferIssuedMs = 2500,
            nonce = "b".repeat(32)))) // A fresh player can obtain a new grant without rearming this passive window.
        assertFalse(live(boot = 10)); assertFalse(live(uid = 10415))
        assertFalse(live(production = false)); assertFalse(live(epoch = 8)) // Same claim cannot preserve a revoked choice.
        assertTrue(window.windowLive(3000, 9)); assertFalse(live(buffer = null)) // A grant gap grants no observation.
        for (bad in listOf(window.copy(issuedMs = 0), window.copy(deadlineMs = 2000),
            window.copy(bootCount = -1), window.copy(deadlineMs = Long.MAX_VALUE), window.copy(choiceEpoch = 0)))
            assertFalse(live(bad))
    }

    @Test fun passiveLeadHooksCannotInstallTheRendererOrFeedGate() {
        val calls = mutableListOf<String>()
        tidalLeadHooks(false, true, { calls += "output" }, { calls += "render_feed_gate" })
        assertEquals(listOf("output"), calls)
        calls.clear(); tidalLeadHooks(false, false, { calls += "output" }, { calls += "render_feed_gate" })
        assertTrue(calls.isEmpty())
        tidalLeadHooks(true, false, { calls += "output" }, { calls += "render_feed_gate" })
        assertEquals(listOf("render_feed_gate"), calls)
    }

    @Test fun passiveSnapshotsAllowCompletedInputBatchesButNeverInventQueuedInputs() {
        val lead = TidalBufferProbeLead(); val owner = Any(); val track = Any(); val claim = Any()
        fun current(count: Int, high: Long) = lead.current(owner, track, claim, 1000, 1000,
            1000, false, count, high, 1100)
        assertNull(current(0, 1200))
        assertEquals(200L, current(5, 1200)!!.inputMinusPositionUs)
        assertEquals(300L, current(28, 1300)!!.inputMinusPositionUs)
        assertNotNull(current(28, 1300)) // Output retries do not imply more queued input.
        assertNull(current(28, 1301)) // A changed high-water with no completed queue call is unknown.
        assertNotNull(current(29, 1301))
        assertNull(current(1, 1302)) // Counter reset invalidates the old epoch rather than publishing old lead.
    }

    @Test fun passiveLeadResetsAcrossControlsOffsetsOwnersTracksClaimsAndSeek() {
        val lead = TidalBufferProbeLead(); var owner = Any(); var track = Any(); var claim = Any(); var position = 1000L
        fun current(input: Long = 1000, output: Long = input, control: Boolean = false) =
            lead.current(owner, track, claim, input, output, position, control, 5, 1400, 1200)
        assertNotNull(current())
        for (change in listOf<() -> Unit>({ owner = Any() }, { track = Any() }, { claim = Any() }, { position = 999 })) {
            val before = lead.observationEpoch; change()
            if (position < 1000) { assertNull(current()); position = 1000; assertNotNull(current()) }
            else assertNotNull(current())
            assertTrue(lead.observationEpoch > before)
        }
        assertNull(current(control = true)); assertNotNull(current())
        assertNull(current(output = 1001)); assertNotNull(current())
        assertNull(current(input = Long.MAX_VALUE)); assertNotNull(current())
        lead.resetObservation(); assertNotNull(current())
    }

    @Test fun onlyTheExactNewFloatPlayerGetsTheFixedRequest() {
        assertEquals(38400, size())
        assertEquals(38400, size(minimum = 38400))
        assertEquals(38400, size(minimum = 1))
        assertNull(size(used = true))
    }

    @Test fun deadlineAndBootPreventStaleOrUnboundedPermissions() {
        assertNull(size(p = null))
        assertNull(size(now = 999))
        assertEquals(38400, size(now = 1000))
        assertEquals(38400, size(now = 60999))
        assertNull(size(now = 61000)); assertNull(size(now = Long.MAX_VALUE))
        assertNull(size(boot = 10)); assertNull(size(boot = -1))
        assertNull(size(p = permit.copy(issuedMs = 0)))
        assertNull(size(p = permit.copy(deadlineMs = 1000)))
        assertNull(size(p = permit.copy(deadlineMs = Long.MIN_VALUE)))
        assertNull(size(p = permit.copy(deadlineMs = Long.MAX_VALUE)))
        assertNull(size(p = permit.copy(deadlineMs = 901001)))
        assertEquals(38400, size(p = permit.copy(deadlineMs = 901000)))
        assertEquals(38400, size(p = permit.copy(deadlineMs = 661000), now = 601000))
        assertNull(size(p = permit.copy(deadlineMs = 661000), now = 661000))
        assertEquals(902, TidalBufferProbePolicy.MAX_READBACKS)
    }

    @Test fun onlyNormalTrackBoundariesPreserveComponentClaimsWhenTimingStops() {
        for (reason in listOf("track_teardown", "decoder_or_stream_changed"))
            assertFalse(TidalBufferProbePolicy.retireComponentOnDisarm(reason))
        for (reason in listOf("preferences_changed", "deadline_or_disarmed", "input_epoch_changed",
                "input_readback_failed", "output_readback_failed", "track_configuration_mismatch", "unknown_failure", ""))
            assertTrue(TidalBufferProbePolicy.retireComponentOnDisarm(reason))
    }

    @Test fun peerAndRouteAreRequiredEvenForTheCoordinatorAttestedDiagnostic() {
        for (p in listOf(permit.copy(routeId = 0), permit.copy(routeId = -1),
            permit.copy(peer = ""), permit.copy(peer = "not a peer"), permit.copy(bootCount = -1))) assertNull(size(p = p))
    }

    @Test fun unknownBuildsAndFormatsLeaveTheOriginalRequestAlone() {
        assertNull(size(sdk = 36)); assertNull(size(version = "2.220.0")); assertNull(size(version = null))
        assertNull(size(code = 10024))
        for (f in listOf(format.copy(codec = null), format.copy(codec = "c2.android.eac3.decoder"),
            format.copy(mime = "audio/eac3"), format.copy(encoding = 2), format.copy(encoding = 5),
            format.copy(channels = 6), format.copy(rate = 44100), format.copy(explicitBytes = 128),
            format.copy(explicitBytes = -1), format.copy(hasTrack = true), format.copy(floatEnabled = false),
            format.copy(tunneling = true))) assertNull(size(f = f))
    }

    @Test fun retainedTrackAllowsVerifiedCodecDiscoveryWithoutAllowingBufferReallocation() {
        val retainedTrack = format.copy(hasTrack = true)
        assertTrue(TidalBufferProbePolicy.supportedComponent(37, "2.219.0", 10023, retainedTrack))
        assertFalse(TidalBufferProbePolicy.supported(37, "2.219.0", 10023, retainedTrack))
        assertNull(size(f = retainedTrack))
        assertNull(TidalBufferProbePolicy.retainedBytes(true, 37, "2.219.0", 10023, retainedTrack,
            3840, normalMode = true, noVisibleRecording = true))
        assertTrue(TidalBufferProbePolicy.supportedComponent(37, "2.219.0", 10023, format))
        assertEquals(38400, size())
    }

    @Test fun componentDiscoveryKeepsEveryOtherExactPlayerAndFloatFormatGuard() {
        fun supported(sdk: Int = 37, version: String? = "2.219.0", code: Long = 10023,
            candidate: TidalBufferProbePolicy.Format = format.copy(hasTrack = true)) =
            TidalBufferProbePolicy.supportedComponent(sdk, version, code, candidate)
        assertFalse(supported(sdk = 36)); assertFalse(supported(version = null))
        assertFalse(supported(version = "2.220.0")); assertFalse(supported(code = 10024))
        for (invalid in listOf(format.copy(codec = null), format.copy(codec = "c2.android.eac3.decoder"),
                format.copy(mime = "audio/eac3"), format.copy(encoding = 2), format.copy(encoding = 5),
                format.copy(channels = 6), format.copy(rate = 44100), format.copy(explicitBytes = 128),
                format.copy(explicitBytes = -1), format.copy(floatEnabled = false), format.copy(tunneling = true))) {
            for (hasTrack in listOf(false, true))
                assertFalse("$invalid hasTrack=$hasTrack", supported(candidate = invalid.copy(hasTrack = hasTrack)))
        }
    }

    @Test fun invalidEstimateRefusesButALargerEstimateKeepsTheFixedRequest() {
        for (minimum in listOf(-2, -1, 0, TidalBufferProbePolicy.MAX_ESTIMATE_BYTES + 1, Int.MAX_VALUE))
            assertNull(size(minimum = minimum))
        for (minimum in listOf(38401, 57840, TidalBufferProbePolicy.MAX_ESTIMATE_BYTES))
            assertEquals(38400, size(minimum = minimum))
    }

    @Test fun exactlyOneConcurrentPlayerCanClaimTheExperiment() {
        val claim = TidalBufferProbeClaim()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..32).map { Callable { claim.take() } })
            assertEquals(1, results.count { it.get(2, TimeUnit.SECONDS) })
            assertTrue(claim.used()); assertFalse(claim.take())
        } finally { pool.shutdownNow() }
    }

    @Test fun replacementCopiesOnlyTheSizeAndCallsTheOriginalExactlyOnce() {
        val format = Any(); val channels = intArrayOf(0, 1)
        val args = arrayOf<Any?>(format, 0, channels)
        var calls = 0
        val result = tidalBufferProbeProceed(args, 38400) { actual ->
            calls++; assertNotSame(args, actual); assertSame(format, actual[0]); assertSame(channels, actual[2])
            assertEquals(38400, actual[1]); "original result"
        }
        assertEquals("original result", result); assertEquals(1, calls); assertEquals(0, args[1])
        tidalBufferProbeProceed(args, null) { assertSame(args, it) }
    }

    @Test fun originalExceptionsAreNotSwallowedOrRetried() {
        var calls = 0
        val original = IllegalStateException("original")
        val thrown = assertThrows(IllegalStateException::class.java) {
            tidalBufferProbeProceed(arrayOf(null, 0, null), 38400) { calls++; throw original }
        }
        assertSame(original, thrown); assertEquals(1, calls)
    }

    @Test fun retainedEligibilityCanBePublishedOnlyAfterSuccessfulConfigure() {
        val args = arrayOf<Any?>(Any(), 0, null)
        var calls = 0; var owned = false
        val result = tidalBufferProbeProceed(args, null, { actual ->
            calls++; assertSame(args, actual); "configured"
        }) { owned = true }
        assertEquals("configured", result); assertTrue(owned); assertEquals(1, calls)
        owned = false
        val original = IllegalStateException("configure failed")
        assertSame(original, assertThrows(IllegalStateException::class.java) {
            tidalBufferProbeProceed(args, null, { calls++; throw original }) { owned = true }
        })
        assertFalse(owned); assertEquals(2, calls)
    }

    @Test fun nestedRendererScopeRestoresAfterFailureAndDoesNotLeakToAnotherThread() {
        val scope = TidalBufferProbeScope(); val first = Any(); val second = Any()
        assertNull(scope.renderer())
        scope.within(first) {
            assertSame(first, scope.renderer())
            assertThrows(IllegalStateException::class.java) { scope.within(second) {
                assertSame(second, scope.renderer()); error("original")
            } }
            assertSame(first, scope.renderer())
            val pool = Executors.newSingleThreadExecutor()
            try { assertNull(pool.submit(Callable { scope.renderer() }).get(2, TimeUnit.SECONDS)) }
            finally { pool.shutdownNow() }
        }
        assertNull(scope.renderer())
    }

    @Test fun leadRequiresACommittedMediaInputAndPreservesSignedTimelineDifferences() {
        val lead = TidalBufferProbeLead()
        assertNull(lead.sample(100, 50))
        assertTrue(lead.queued(12, 12, 999999)) // A successful format/no-slot call is not a queued input.
        assertNull(lead.sample(100, 50))
        assertTrue(lead.queued(12, 13, 1_096_000))
        val sample = lead.sample(1_032_000, 1_000_000)!!
        assertEquals(64_000L, sample.inputMinusOutputUs)
        assertEquals(32_000L, sample.outputMinusPositionUs)
        assertEquals(96_000L, sample.inputMinusPositionUs)
        assertEquals(-4_000L, lead.sample(1_032_000, 1_036_000)!!.outputMinusPositionUs)
        assertEquals(sample, lead.sample(1_032_000, 1_000_000)) // Retried output is observational only.
    }

    @Test fun queuedCounterOrTimestampDiscontinuityCannotPublishAnOldLead() {
        for (invalid in listOf(Triple(5, 4, 120L), Triple(5, 7, 120L), Triple(-1, 0, 120L),
            Triple(Int.MAX_VALUE, Int.MIN_VALUE, 120L), Triple(5, 6, -1L),
            Triple(5, 6, Long.MAX_VALUE), Triple(5, 6, 99L))) {
            val lead = TidalBufferProbeLead()
            assertTrue(lead.queued(4, 5, 100))
            assertFalse(lead.queued(invalid.first, invalid.second, invalid.third))
            assertNull(lead.sample(80, 50))
            assertFalse(lead.queued(5, 6, 150)) // A new decoder needs a new diagnostic, not a reused epoch.
        }
    }

    @Test fun unsetOutputAndPositionNeverBecomeHugeLeadValues() {
        val lead = TidalBufferProbeLead()
        assertTrue(lead.queued(0, 1, 0))
        assertEquals(0L, lead.sample(0, 0)!!.inputMinusPositionUs)
        for (unset in listOf(-1L, Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE)) {
            assertNull(lead.sample(unset, 0)); assertNull(lead.sample(0, unset))
        }
        assertTrue(lead.queued(1, 2, Long.MAX_VALUE - 1))
        assertEquals(Long.MAX_VALUE - 1, lead.sample(0, 0)!!.inputMinusPositionUs)
    }

    @Test fun componentClaimIsAbsentByDefaultAndBoundToThisBufferBootUidAndDeadline() {
        val claim = TidalComponentClaimPermit("0123456789abcdef0123456789abcdef", 1100, 50000, 9, 10414, 1000)
        assertNull(TidalComponentClaimPermit.fromValues(emptyMap<String, Any>()))
        assertNull(TidalComponentClaimPermit.fromValues(mapOf(TidalComponentClaimPermit.PREFIX + "nonce" to claim.nonce)))
        assertTrue(claim.live(permit, 2000, 9, 10414))
        assertFalse(claim.live(permit, 1099, 9, 10414))
        assertFalse(claim.live(permit, 50000, 9, 10414))
        assertFalse(claim.live(permit, 2000, 10, 10414))
        assertFalse(claim.live(permit, 2000, 9, 10415))
        for (invalid in listOf(claim.copy(nonce = TidalComponentClaimPermit.CLEAR), claim.copy(nonce = claim.nonce.uppercase()),
            claim.copy(nonce = "g".repeat(32)), claim.copy(nonce = "a".repeat(31)), claim.copy(nonce = "a".repeat(33)),
            claim.copy(issuedMs = 999), claim.copy(deadlineMs = 1100), claim.copy(deadlineMs = 61001),
            claim.copy(deadlineMs = Long.MAX_VALUE), claim.copy(bufferIssuedMs = 999), claim.copy(boot = 8),
            claim.copy(targetUid = 0))) assertFalse(invalid.live(permit, 2000, 9, 10414))
        val prefix = TidalComponentClaimPermit.PREFIX
        val values = mapOf(prefix + "nonce" to claim.nonce, prefix + "issued_ms" to 1100L,
            prefix + "deadline_ms" to 50000L, prefix + "boot" to 9, prefix + "target_uid" to 10414,
            prefix + "buffer_issued_ms" to 1000L)
        assertEquals(claim, TidalComponentClaimPermit.fromValues(values))
        assertNull(TidalComponentClaimPermit.fromValues(values + (prefix + "issued_ms" to 1100)))
    }

    @Test fun failedComponentApiAttemptStillClearsOnceAndCannotRetryOrChangeOwner() {
        val target = Any(); val sent = mutableListOf<Pair<Any, String>>()
        val failure = IllegalStateException("diagnostic API failure")
        val relay = TidalComponentClaimRelay<Any> { owner, nonce ->
            sent += owner to nonce
            if (nonce != TidalComponentClaimPermit.CLEAR) throw failure
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { relay.attempt(target, "claim") })
        assertFalse(relay.attempt(Any(), "second"))
        relay.retire()
        assertTrue(relay.clear()); assertFalse(relay.clear())
        assertEquals(listOf(target to "claim", target to TidalComponentClaimPermit.CLEAR), sent)
        assertFalse(relay.attempt(target, "later"))
    }

    @Test fun retirementSerializesClearAfterAnAlreadyStartedClaimAndPreventsLateSet() {
        val target = Any(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val sent = mutableListOf<String>()
        val relay = TidalComponentClaimRelay<Any> { owner, nonce ->
            assertSame(target, owner)
            if (nonce != TidalComponentClaimPermit.CLEAR) {
                entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
            }
            sent += nonce
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val sending = pool.submit(Callable { relay.attempt(target, "claim") })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            relay.retire()
            val clearing = pool.submit(Callable { relay.clear() })
            assertFalse(clearing.get(2, TimeUnit.SECONDS)) // Main thread does not wait behind startup IPC.
            release.countDown()
            assertTrue(sending.get(2, TimeUnit.SECONDS))
            assertEquals(listOf("claim", TidalComponentClaimPermit.CLEAR), sent)
            assertFalse(relay.attempt(target, "late"))
        } finally { release.countDown(); pool.shutdownNow() }
        val neverSent = TidalComponentClaimRelay<Any> { _, _ -> fail("retired before startup") }
        neverSent.retire(); assertFalse(neverSent.attempt(target, "late")); assertFalse(neverSent.clear())
    }

    @Test fun failedDeferredClearCannotReplaceTheOriginalClaimFailure() {
        val original = IllegalStateException("set failed")
        val reports = mutableListOf<Boolean>()
        lateinit var relay: TidalComponentClaimRelay<Any>
        relay = TidalComponentClaimRelay(onClear = { reports += it }) { _, nonce ->
            if (nonce == TidalComponentClaimPermit.CLEAR) error("clear failed")
            relay.retire()
            throw original
        }
        assertSame(original, assertThrows(IllegalStateException::class.java) { relay.attempt(Any(), "claim") })
        assertEquals(listOf(false), reports)
        assertFalse(relay.clear())
    }

    @Test fun retainedChoiceCanConfigureEachEligibleNewTrackButNeverAnAllocatedTrack() {
        repeat(3) {
            assertEquals(38400, TidalBufferProbePolicy.retainedBytes(true, 37, "2.219.0", 10023,
                format, 26992, normalMode = true, noVisibleRecording = true))
            assertNull(TidalBufferProbePolicy.retainedBytes(true, 37, "2.219.0", 10023,
                format.copy(hasTrack = true), 26992, normalMode = true, noVisibleRecording = true))
        }
        assertNull(TidalBufferProbePolicy.retainedBytes(false, 37, "2.219.0", 10023,
            format, 26992, normalMode = true, noVisibleRecording = true))
    }

    @Test fun retainedBufferKeepsAllFormatPlatformAndControlAdmissionGuards() {
        fun retained(f: TidalBufferProbePolicy.Format = format, sdk: Int = 37, version: String? = "2.219.0",
            code: Long = 10023, minimum: Int = 26992, normal: Boolean = true, noRecording: Boolean = true) =
            TidalBufferProbePolicy.retainedBytes(true, sdk, version, code, f, minimum, normal, noRecording)
        for (f in listOf(format.copy(codec = null), format.copy(codec = "c2.android.eac3.decoder"),
            format.copy(mime = "audio/eac3"), format.copy(encoding = 2), format.copy(channels = 6),
            format.copy(rate = 44100), format.copy(explicitBytes = 38400), format.copy(explicitBytes = -1),
            format.copy(hasTrack = true), format.copy(floatEnabled = false), format.copy(tunneling = true)))
            assertNull(retained(f))
        assertNull(retained(sdk = 36)); assertNull(retained(version = "2.220.0")); assertNull(retained(version = null))
        assertNull(retained(code = 10024)); assertNull(retained(normal = false)); assertNull(retained(noRecording = false))
        for (minimum in listOf(-2, 0, TidalBufferProbePolicy.MAX_ESTIMATE_BYTES + 1, Int.MAX_VALUE)) assertNull(retained(minimum = minimum))
        assertEquals(38400, retained(minimum = 38400))
        assertEquals(38400, retained(minimum = 57840))
        assertNull(retained(f = format.copy(hasTrack = true), minimum = 57840))
        assertNull(retained(f = format.copy(codec = "c2.android.eac3.decoder"), minimum = 57840))
    }

    @Test fun coldStartAndExpiredDiagnosticChooseRetentionWithoutCreatingAnotherDiagnosticClaim() {
        assertEquals(TidalBufferProbePolicy.Mode.NONE, TidalBufferProbePolicy.mode(false, false))
        assertEquals(TidalBufferProbePolicy.Mode.RETAINED, TidalBufferProbePolicy.mode(false, true))
        assertEquals(TidalBufferProbePolicy.Mode.DIAGNOSTIC, TidalBufferProbePolicy.mode(true, false))
        assertEquals(TidalBufferProbePolicy.Mode.DIAGNOSTIC, TidalBufferProbePolicy.mode(true, true))
        val oneShot = TidalBufferProbeClaim()
        assertTrue(oneShot.take())
        assertEquals(TidalBufferProbePolicy.Mode.DIAGNOSTIC,
            TidalBufferProbePolicy.mode(permit.live(60999, 9), true))
        assertNull(size(now = 60999, used = oneShot.used())) // Saved opt-in cannot bypass a live diagnostic's claim.
        assertEquals(TidalBufferProbePolicy.Mode.RETAINED,
            TidalBufferProbePolicy.mode(permit.live(61000, 9), true))
        assertNull(size(now = 61000, used = oneShot.used()))
        assertEquals(38400, TidalBufferProbePolicy.retainedBytes(true, 37, "2.219.0", 10023,
            format, 26992, true, true))
        assertNull(TidalBufferProbePolicy.retainedBytes(true, 37, "2.219.0", 10023,
            format.copy(hasTrack = true), 26992, true, true))
        assertFalse(oneShot.take())
        assertNull(TidalComponentClaimPermit.fromValues(mapOf(TidalBufferProbePolicy.RETAINED_KEY to true)))
    }

    @Test fun actualBufferReadbackDistinguishesExactRequestFromValidFrameworkClamping() {
        assertEquals(TidalBufferProbePolicy.BufferResult.EXACT_100_MS,
            TidalBufferProbePolicy.bufferResult(4800, 4800, 4800))
        for ((size, capacity, threshold) in listOf(Triple(4800, 7230, 7230), Triple(4800, 4800, 2400))) {
            val result = TidalBufferProbePolicy.bufferResult(size, capacity, threshold)
            assertEquals(TidalBufferProbePolicy.BufferResult.EFFECTIVE_100_MS, result)
            assertTrue(result.effective100Ms)
        }
        assertTrue(TidalBufferProbePolicy.bufferResult(4800, 4800, 4800).effective100Ms)
        assertFalse(TidalBufferProbePolicy.bufferResult(7230, 7230, 7230).effective100Ms)
        for ((size, capacity, threshold) in listOf(Triple(7230, 7230, 7230),
            Triple(4799, 4800, 4800), Triple(72000, 72000, 72000)))
            assertEquals(TidalBufferProbePolicy.BufferResult.CLAMPED,
                TidalBufferProbePolicy.bufferResult(size, capacity, threshold))
        for ((size, capacity, threshold) in listOf(Triple(0, 4800, 4800), Triple(4800, 0, 4800),
            Triple(4800, 4800, 0), Triple(-1, 4800, 4800), Triple(4801, 4800, 4800),
            Triple(4800, 4800, 4801), Triple(72001, 72001, 72001), Triple(4800, Int.MAX_VALUE, 4800)))
            assertEquals(TidalBufferProbePolicy.BufferResult.INVALID,
                TidalBufferProbePolicy.bufferResult(size, capacity, threshold))
        assertEquals(TidalBufferProbePolicy.BufferResult.INVALID,
            TidalBufferProbePolicy.bufferResult(4800, 4800, 4800, rate = 44100))
        assertEquals(TidalBufferProbePolicy.BufferResult.INVALID,
            TidalBufferProbePolicy.bufferResult(4800, 4800, 4800, channels = 6))
        assertEquals(TidalBufferProbePolicy.BufferResult.INVALID,
            TidalBufferProbePolicy.bufferResult(4800, 4800, 4800, encoding = 2))
    }

    @Test fun resumeProofRequiresOldNonceAndExactCurrentOwnerPidSessionWithinOriginalWindow() {
        val old = TidalComponentClaimPermit("a".repeat(32), 1100, 61000, 9, 42, 1000)
        val resumed = old.copy(nonce = "b".repeat(32), resumeFromNonce = old.nonce, resumePid = 123, resumeSession = 456)
        assertTrue(resumed.live(permit, 2000, 9, 42))
        assertTrue(resumed.matchesResume(old, 123, 456, true))
        assertFalse(resumed.matchesResume(old, 123, 456, false))
        assertFalse(resumed.matchesResume(old, 124, 456, true))
        assertFalse(resumed.matchesResume(old, 123, 457, true))
        assertFalse(resumed.matchesResume(old.copy(nonce = "c".repeat(32)), 123, 456, true))
        assertFalse(resumed.copy(bufferIssuedMs = 1001).matchesResume(old, 123, 456, true))
        assertFalse(resumed.live(permit, 61000, 9, 42))
        for (invalid in listOf(resumed.copy(resumeFromNonce = resumed.nonce), resumed.copy(resumePid = -1),
            resumed.copy(resumeSession = 0), resumed.copy(resumeFromNonce = null)))
            assertFalse(invalid.live(permit, 2000, 9, 42))
    }
}
