package me.kavishdevar.librepods.companion

import android.app.Application
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CompanionHandoffControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val device = "AA:BB:CC:DD:EE:FF"
    private val sid = "ab".repeat(32)
    private val id = "0123456789abcdef"

    @Test fun invalidatingAnInflightAcquireSuppressesItsLateReceiptWithoutRollingBack() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val fixture = fixture { phase, _, _ ->
            calls++
            assertEquals("acquire", phase)
            started.complete(Unit)
            withContext(NonCancellable) { finish.await(); "acquired" }
        }
        try {
            fixture.handle(frame("prepare").put("target", "android"))
            fixture.awaitResult("prepare")
            fixture.handle(frame("acquire"))
            withTimeout(2_000) { started.await() }
            fixture.controller.invalidate()
            finish.complete(Unit)
            delay(100)
            assertEquals(1, calls)
            assertFalse(fixture.frames().any { it.optString("code") == "acquired" })
            assertTrue(fixture.frames().any { it.getString("type") == "cancel" })
            assertFalse(fixture.controller.state.value.busy)
        } finally { finish.complete(Unit); fixture.close() }
    }

    @Test fun completedTransactionsCannotBePreparedOrExecutedAgain() = runBlocking<Unit> {
        var calls = 0
        val fixture = fixture { _, _, _ -> calls++; "released" }
        try {
            fixture.handle(frame("prepare").put("target", "pc"))
            fixture.awaitResult("prepare")
            fixture.handle(frame("release"))
            fixture.awaitResult("release")
            fixture.handle(frame("result").put("phase", "complete").put("ok", true).put("code", "acquired"))
            try {
                fixture.handle(frame("prepare").put("target", "pc"))
                fail("A replay cannot create a new reservation")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1, calls)
            assertFalse(fixture.controller.state.value.busy)
        } finally { fixture.close() }
    }

    @Test fun acquireIsRejectedBeforePrepareAndCannotRunOnTheSourceSide() = runBlocking<Unit> {
        var calls = 0
        val fixture = fixture { _, _, _ -> calls++; "acquired" }
        try {
            fixture.handle(frame("acquire"))
            assertTrue(fixture.frames().any { it.optString("code") == "invalid_state" })
            fixture.handle(frame("prepare").put("target", "pc").put("tx", "1123456789abcdef"))
            fixture.awaitResult("prepare")
            fixture.handle(frame("acquire").put("tx", "1123456789abcdef"))
            assertEquals(0, calls)
        } finally { fixture.close() }
    }

    @Test fun anOriginUpdateWithoutAFreshIncomingEventCannotInventACall() = runBlocking<Unit> {
        val fixture = fixture(calls = true, operation = { _, _, _ -> "failed" })
        try {
            fixture.controller.onCallState("ringing", false)
            fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid).put("ready", true).put("origin_token", "cd".repeat(16)))
            fixture.controller.onCallState("ringing", false)
            assertFalse(fixture.frames().any { it.optString("type") == "call_begin" })
        } finally { fixture.close() }
    }

    @Test fun aFreshIncomingEventCanWaitBrieflyForAVerifiedOrigin() = runBlocking<Unit> {
        val fixture = fixture(calls = true, operation = { _, _, _ -> "failed" })
        try {
            fixture.controller.onCallState("ringing", true)
            assertFalse(fixture.frames().any { it.optString("type") == "call_begin" })
            fixture.controller.onCallState("active", false)
            fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid)
                .put("ready", true).put("origin_token", "cd".repeat(16)))
            fixture.awaitType("call_begin")
            assertEquals(1, fixture.frames().count { it.optString("type") == "call_begin" })
        } finally { fixture.close() }
    }

    @Test fun anEndedExpiredUnlinkedOrOverriddenIncomingEventCannotBeRevived() = runBlocking<Unit> {
        for (retire in listOf("idle", "unknown", "manual", "link", "permission", "expiry")) {
            var allowed = true
            val fixture = fixture(calls = true, allowed = { allowed }, operation = { _, _, _ -> "failed" })
            try {
                fixture.controller.onCallState("ringing", true)
                when (retire) {
                    "idle", "unknown" -> fixture.controller.onCallState(retire, false)
                    "manual" -> fixture.controller.invalidate()
                    "link" -> fixture.controller.close()
                    "permission" -> allowed = false
                    "expiry" -> ShadowSystemClock.advanceBy(Duration.ofMillis(5001))
                }
                fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid)
                    .put("ready", true).put("origin_token", "cd".repeat(16)))
                allowed = true
                fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid)
                    .put("ready", true).put("origin_token", "de".repeat(16)))
                assertFalse(retire, fixture.frames().any { it.optString("type") == "call_begin" })
            } finally { fixture.close() }
        }
    }

    @Test fun prePrepareFailureCanRetryOnlyOnceWithADifferentOrigin() = runBlocking<Unit> {
        val fixture = fixture(calls = true, operation = { _, _, _ -> "failed" })
        try {
            suspend fun origin(token: String) = fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid)
                .put("ready", true).put("origin_token", token))
            suspend fun failed(tx: String) = fixture.handle(CompanionProtocol.frame("result", device, tx, sid)
                .put("phase", "complete").put("ok", false).put("code", "unavailable"))
            origin("cd".repeat(16)); fixture.controller.onCallState("ringing", true)
            val first = fixture.awaitType("call_begin")
            failed(first.getString("tx"))
            origin("cd".repeat(16))
            assertEquals(1, fixture.frames().count { it.optString("type") == "call_begin" })
            origin("de".repeat(16))
            withTimeout(2000) { while (fixture.frames().count { it.optString("type") == "call_begin" } != 2) delay(10) }
            val second = fixture.frames().last { it.optString("type") == "call_begin" }
            assertNotEquals(first.getString("tx"), second.getString("tx"))
            assertNotEquals(first.getString("episode"), second.getString("episode"))
            failed(second.getString("tx")); origin("ef".repeat(16))
            assertEquals(2, fixture.frames().count { it.optString("type") == "call_begin" })
        } finally { fixture.close() }
    }

    @Test fun anAcquireThatBeganCannotRetryFromANewOrigin() = runBlocking<Unit> {
        var acquisitions = 0
        val fixture = fixture(calls = true, callAcquire = { _, _ -> acquisitions++; "failed" }, operation = { _, _, _ -> "failed" })
        try {
            fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid).put("ready", true).put("origin_token", "cd".repeat(16)))
            fixture.controller.onCallState("ringing", true)
            val begin = fixture.awaitType("call_begin")
            fixture.handle(CompanionProtocol.frame("prepare", device, begin.getString("tx"), sid)
                .put("target", "android").put("purpose", "call_takeover").put("episode", begin.getString("episode"))
                .put("call_gen", begin.getString("call_gen")).put("origin_token", begin.getString("origin_token")))
            fixture.awaitResult("prepare")
            fixture.handle(CompanionProtocol.frame("acquire", device, begin.getString("tx"), sid))
            fixture.awaitResult("acquire")
            fixture.handle(CompanionProtocol.frame("result", device, begin.getString("tx"), sid)
                .put("phase", "complete").put("ok", false).put("code", "failed"))
            fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid).put("ready", true).put("origin_token", "de".repeat(16)))
            assertEquals(1, acquisitions)
            assertEquals(1, fixture.frames().count { it.optString("type") == "call_begin" })
        } finally { fixture.close() }
    }

    @Test fun aRetryRequiresPositiveIdleProofAndNoObservedHfpOrSco() = runBlocking<Unit> {
        for (flags in listOf(0, 3 or 8, 4)) {
            val fixture = fixture(calls = true, receipt = { _, _ -> flags }, operation = { _, _, _ -> "failed" })
            try {
                fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid).put("ready", true).put("origin_token", "cd".repeat(16)))
                fixture.controller.onCallState("ringing", true)
                val begin = fixture.awaitType("call_begin")
                fixture.handle(CompanionProtocol.frame("result", device, begin.getString("tx"), sid)
                    .put("phase", "complete").put("ok", false).put("code", "unavailable"))
                fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid).put("ready", true).put("origin_token", "de".repeat(16)))
                assertEquals("flags=$flags", 1, fixture.frames().count { it.optString("type") == "call_begin" })
            } finally { fixture.close() }
        }
    }

    @Test fun aRejectedOriginWaitsForTheOutstandingIdleReceiptBeforeItsSingleRetry() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val fixture = fixture(calls = true, receipt = { _, _ -> started.complete(Unit); finish.await(); 8 },
            operation = { _, _, _ -> "failed" })
        try {
            fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid).put("ready", true).put("origin_token", "cd".repeat(16)))
            fixture.controller.onCallState("ringing", true)
            val begin = fixture.awaitType("call_begin"); started.await()
            fixture.handle(CompanionProtocol.frame("result", device, begin.getString("tx"), sid)
                .put("phase", "complete").put("ok", false).put("code", "unavailable"))
            fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid).put("ready", true).put("origin_token", "de".repeat(16)))
            assertEquals(1, fixture.frames().count { it.optString("type") == "call_begin" })
            finish.complete(Unit)
            withTimeout(2000) { while (fixture.frames().count { it.optString("type") == "call_begin" } != 2) delay(10) }
        } finally { finish.complete(Unit); fixture.close() }
    }

    @Test fun naturalEndDrainsTheRetiredAcquireThenAllowsOnlyAFreshSameEpisodeReturn() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var owner: (() -> Boolean)? = null
        var releases = 0
        val fixture = fixture(calls = true,
            callAcquire = { _, current ->
                owner = current; started.complete(Unit)
                withContext(NonCancellable) { finish.await(); "hfp_ready" }
            }, quiet = { _, current -> finish.await(); current() },
            operation = { phase, _, _ -> assertEquals("release", phase); releases++; "released" })
        try {
            fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid).put("ready", true).put("origin_token", "cd".repeat(16)))
            fixture.controller.onCallState("ringing", true)
            val begin = fixture.awaitType("call_begin")
            val prepare = CompanionProtocol.frame("prepare", device, begin.getString("tx"), sid)
                .put("target", "android").put("purpose", "call_takeover").put("episode", begin.getString("episode"))
                .put("call_gen", begin.getString("call_gen")).put("origin_token", begin.getString("origin_token"))
            fixture.handle(prepare); fixture.awaitResult("prepare")
            fixture.handle(CompanionProtocol.frame("acquire", device, begin.getString("tx"), sid))
            started.await()
            assertTrue(owner!!())
            fixture.controller.onCallState("idle", false)
            assertFalse(owner!!())
            assertFalse(fixture.frames().any { it.optString("type") == "call_end" })
            finish.complete(Unit)
            withTimeout(2000) { while (fixture.frames().none { it.optString("reason") == "ended" }) delay(10) }
            assertFalse(fixture.frames().any { it.optString("code") == "hfp_ready" })
            val returning = JSONObject(prepare.toString()).put("tx", "1123456789abcdef").put("target", "pc").put("purpose", "call_return")
            fixture.handle(returning)
            withTimeout(2000) { while (fixture.frames().none { it.optString("tx") == "1123456789abcdef" && it.optString("code") == "ready" }) delay(10) }
            fixture.handle(CompanionProtocol.frame("release", device, "1123456789abcdef", sid))
            fixture.awaitResult("release")
            assertEquals(1, releases)
        } finally { finish.complete(Unit); fixture.close() }
    }

    @Test fun aNewPhoneChoiceRevokesCallReturnEvenIfTheOldHelperCompletes() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val fixture = fixture(calls = true, callAcquire = { _, _ ->
            started.complete(Unit); withContext(NonCancellable) { finish.await(); "hfp_ready" }
        }, operation = { _, _, _ -> "failed" })
        try {
            fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid).put("ready", true).put("origin_token", "cd".repeat(16)))
            fixture.controller.onCallState("ringing", true)
            val begin = fixture.awaitType("call_begin")
            fixture.handle(CompanionProtocol.frame("prepare", device, begin.getString("tx"), sid)
                .put("target", "android").put("purpose", "call_takeover").put("episode", begin.getString("episode"))
                .put("call_gen", begin.getString("call_gen")).put("origin_token", begin.getString("origin_token")))
            fixture.awaitResult("prepare")
            fixture.handle(CompanionProtocol.frame("acquire", device, begin.getString("tx"), sid)); started.await()
            fixture.controller.invalidate(); finish.complete(Unit); delay(50)
            fixture.controller.onCallState("idle", false)
            assertFalse(fixture.frames().any { it.optString("code") == "hfp_ready" || it.optString("reason") == "ended" })
            assertTrue(fixture.frames().any { it.optString("reason") == "invalidated" })
        } finally { finish.complete(Unit); fixture.close() }
    }

    @Test fun mediaQueryBaselinesPlaybackAndOnlyReportsANewStart() = runBlocking<Unit> {
        var media = CompanionMediaSample(known = true, playing = true, available = true, player = "player")
        val fixture = fixture(caps = 7, media = { media }, operation = { _, _, _ -> "failed" })
        try {
            fixture.handle(CompanionProtocol.frame("media_query", device, sid = sid).put("probe", id))
            assertEquals("0000000000000000", fixture.awaitType("media_state").getString("event"))
            media = media.copy(playing = false, player = "")
            fixture.handle(CompanionProtocol.frame("media_query", device, sid = sid).put("probe", "1123456789abcdef"))
            withTimeout(2000) { while (fixture.frames().none { it.optString("probe") == "1123456789abcdef" }) delay(10) }
            media = media.copy(playing = true, player = "player")
            fixture.handle(CompanionProtocol.frame("media_query", device, sid = sid).put("probe", "2123456789abcdef"))
            withTimeout(2000) { while (fixture.frames().none { it.optString("event") == "0000000000000001" }) delay(10) }
            fixture.frames().filter { it.optString("type") == "media_state" }.forEach { CompanionProtocol.validate(it, device, sid) }
        } finally { fixture.close() }
    }

    @Test fun mediaAcquireIsCancelledByAPauseAfterPrepare() = runBlocking<Unit> {
        var media = CompanionMediaSample(known = true, playing = true, available = true, player = "player")
        var calls = 0
        val fixture = fixture(caps = 7, media = { media }, operation = { _, _, _ -> calls++; "acquired" })
        try {
            fixture.handle(CompanionProtocol.frame("media_query", device, sid = sid).put("probe", id))
            val baseline = fixture.awaitType("media_state")
            fixture.handle(frame("prepare").put("target", "android").put("purpose", "media").put("media_gen", baseline.getString("generation")))
            fixture.awaitResult("prepare")
            assertTrue(fixture.frames().any { it.optString("phase") == "prepare" && it.optString("code") == "ready" })
            media = media.copy(playing = false, player = "")
            fixture.handle(frame("acquire")); fixture.awaitResult("acquire")
            assertEquals(0, calls)
            assertTrue(fixture.frames().any { it.optString("phase") == "acquire" && it.optString("code") in setOf("superseded", "invalid_state") })
        } finally { fixture.close() }
    }

    @Test fun mediaReleaseRequiresQuietOrActualSelectedTransportLoss() = runBlocking<Unit> {
        for ((sample, allowed) in listOf(
            CompanionMediaSample(known = true, playing = true, connected = true, available = true, player = "player") to false,
            CompanionMediaSample(known = true, playing = true, connected = false, available = true, player = "player") to true,
            CompanionMediaSample(known = false, connected = false) to false)) {
            val fixture = fixture(caps = 7, media = { sample }, operation = { _, _, _ -> "released" })
            try {
                fixture.handle(frame("prepare").put("target", "pc").put("purpose", "media").put("media_gen", "0000000000000001"))
                fixture.awaitResult("prepare")
                assertEquals(allowed, fixture.frames().single { it.optString("phase") == "prepare" }.getBoolean("ok"))
            } finally { fixture.close() }
        }
    }

    @Test fun mediaWatchRevokesAnInflightAcquireWithoutWaitingForAnotherPcQuery() = runBlocking<Unit> {
        var media = CompanionMediaSample(known = true, playing = true, available = true, player = "player")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var owner: (() -> Boolean)? = null
        val fixture = fixture(caps = 7, media = { media }, operation = { _, _, current ->
            owner = current; started.complete(Unit)
            withContext(NonCancellable) { finish.await(); "acquired" }
        })
        try {
            fixture.handle(CompanionProtocol.frame("media_query", device, sid = sid).put("probe", id))
            val generation = fixture.awaitType("media_state").getString("generation")
            fixture.handle(frame("prepare").put("target", "android").put("purpose", "media").put("media_gen", generation))
            fixture.awaitResult("prepare"); fixture.handle(frame("acquire")); started.await()
            assertTrue(owner!!())
            media = media.copy(playing = false, player = "")
            withTimeout(2000) { while (owner!!()) delay(20) }
            finish.complete(Unit); fixture.awaitType("cancel")
            assertFalse(fixture.frames().any { it.optString("code") == "acquired" })
        } finally { finish.complete(Unit); fixture.close() }
    }

    @Test fun aNewPhoneStartAfterSourceReleaseCancelsTheOlderPcAcquisition() = runBlocking<Unit> {
        var media = CompanionMediaSample(known = true, available = true, connected = true)
        val fixture = fixture(caps = 7, media = { media }, operation = { phase, _, _ ->
            assertEquals("release", phase)
            media = media.copy(connected = false)
            "released"
        })
        try {
            fixture.handle(CompanionProtocol.frame("media_query", device, sid = sid).put("probe", id))
            val generation = fixture.awaitType("media_state").getString("generation")
            fixture.handle(frame("prepare").put("target", "pc").put("purpose", "media").put("media_gen", "0000000000000009"))
            fixture.awaitResult("prepare"); fixture.handle(frame("release")); fixture.awaitResult("release")
            assertTrue(fixture.frames().any { it.optString("code") == "released" })
            fixture.handle(CompanionProtocol.frame("media_query", device, sid = sid).put("probe", "1123456789abcdef"))
            withTimeout(2000) { while (fixture.frames().none { it.optString("probe") == "1123456789abcdef" }) delay(10) }
            assertEquals(generation, fixture.frames().last { it.optString("type") == "media_state" }.getString("generation"))
            media = media.copy(playing = true, player = "new-player")
            fixture.awaitType("cancel")
            assertFalse(fixture.controller.state.value.busy)
            fixture.handle(CompanionProtocol.frame("media_query", device, sid = sid).put("probe", "2123456789abcdef"))
            withTimeout(2000) { while (fixture.frames().none { it.optString("probe") == "2123456789abcdef" }) delay(10) }
        } finally { fixture.close() }
    }

    @Test fun consentedIncomingCallSupersedesMediaAndRetiresItsHelperOwner() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var owner: (() -> Boolean)? = null
        val fixture = fixture(calls = true, caps = 7,
            media = { CompanionMediaSample(known = true, playing = true, available = true, player = "player") },
            operation = { _, _, current ->
                owner = current; started.complete(Unit)
                withContext(NonCancellable) { finish.await(); "acquired" }
            })
        try {
            fixture.handle(CompanionProtocol.frame("call_origin", device, sid = sid)
                .put("ready", true).put("origin_token", "cd".repeat(16)))
            fixture.handle(CompanionProtocol.frame("media_query", device, sid = sid).put("probe", id))
            val generation = fixture.awaitType("media_state").getString("generation")
            fixture.handle(frame("prepare").put("target", "android").put("purpose", "media").put("media_gen", generation))
            fixture.awaitResult("prepare"); fixture.handle(frame("acquire")); started.await()
            fixture.controller.onCallState("ringing", true)
            assertFalse(owner!!())
            fixture.awaitType("call_begin")
            assertTrue(fixture.frames().any { it.optString("type") == "cancel" && it.optString("tx") == id })
            finish.complete(Unit); delay(50)
            assertFalse(fixture.frames().any { it.optString("code") == "acquired" })
        } finally { finish.complete(Unit); fixture.close() }
    }

    private fun frame(type: String) = CompanionProtocol.frame(type, device, id, sid).apply { if (type == "prepare") put("purpose", "manual").put("episode", "0".repeat(32)).put("call_gen", CompanionProtocol.HANDSHAKE_TX).put("origin_token", "0".repeat(32)) }

    private fun fixture(calls: Boolean = false, caps: Int = 3, allowed: () -> Boolean = { calls },
        receipt: suspend (String, () -> Boolean) -> Int = { _, _ -> 8 },
        media: suspend (String) -> CompanionMediaSample = { CompanionMediaSample() },
        callAcquire: suspend (String, () -> Boolean) -> String = { _, _ -> "hfp_ready" },
        quiet: suspend (String, () -> Boolean) -> Boolean = { _, current -> current() },
        operation: suspend (String, String, () -> Boolean) -> String): Fixture {
        val context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(temporary.newFolder())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CompanionHandoffController(context, scope, { _, _ -> CompanionStatus(false, false, true) }, operation,
            callAllowed = { allowed() }, callReturnAllowed = { allowed() }, callStatus = { _, _ -> CompanionStatus(false, false, true) },
            callOperation = callAcquire, callReceipt = receipt, phoneQuiet = quiet, allCallsIdle = { true }, media = media)
        val output = ByteArrayOutputStream()
        val socket = mock(BluetoothSocket::class.java)
        `when`(socket.outputStream).thenReturn(output)
        val sessionClass = Class.forName("me.kavishdevar.librepods.companion.CompanionHandoffController\$Session")
        val constructor = sessionClass.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
        val session = constructor.newInstance(socket, CompanionLink("11:22:33:44:55:66", device, ByteArray(32)), sid, caps)
        CompanionHandoffController::class.java.getDeclaredField("session").apply { isAccessible = true }.set(controller, session)
        return Fixture(controller, scope, output, session)
    }

    private class Fixture(val controller: CompanionHandoffController, private val scope: CoroutineScope,
        private val output: ByteArrayOutputStream, private val session: Any) {
        suspend fun handle(frame: JSONObject): Unit = suspendCoroutine { continuation ->
            val method = CompanionHandoffController::class.java.declaredMethods.single { it.name == "handle" }.apply { isAccessible = true }
            try {
                val returned = method.invoke(controller, session, frame, continuation)
                if (returned !== COROUTINE_SUSPENDED) continuation.resume(Unit)
            } catch (error: InvocationTargetException) { continuation.resumeWithException(error.cause ?: error) }
        }
        suspend fun awaitType(type: String): JSONObject = withTimeout(2000) {
            while (frames().none { it.optString("type") == type }) delay(10)
            frames().last { it.optString("type") == type }
        }
        fun frames() = output.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        suspend fun awaitResult(phase: String) { withTimeout(2_000) {
            while (frames().none { it.optString("phase") == phase }) delay(10)
        } }
        fun close() { controller.close(); scope.cancel() }
    }
}
