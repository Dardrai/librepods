package me.kavishdevar.librepods.companion

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import me.kavishdevar.librepods.core.devices.LocalPreferenceEdits
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class CompanionPreferencesTest {
    @get:Rule val files = TemporaryFolder()
    private val pair = "AA:BB:CC:DD:EE:FF"
    private fun value(v: Any) = JSONObject().put("value", v)

    @Test fun simultaneousEditsConvergeWithoutEchoAndSameStampCollisionIsRejected() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(files.newFolder())
        var actual = value(true)
        var applications = 0
        val store = CompanionPreferences(context, { mapOf("host_auto_pause" to actual) }, { _, _, p, _ -> applications++; actual = p; true })
        val initial = store.localChanges(pair).single()
        assertEquals(1uL, initial.rev)
        val remote = CompanionPreferences.Record("host_auto_pause", 1uL, "pc", value(false))
        assertEquals("applied", store.receive(pair, remote).first)
        assertTrue(store.localChanges(pair).isEmpty())
        assertEquals("unchanged", store.receive(pair, remote).first)
        assertEquals("stale", store.receive(pair, initial).first)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            store.receive(pair, remote.copy(payload = value(true)))
        } }
        assertEquals(1, applications)
        actual = value(true)
        assertEquals(2uL, store.localChanges(pair).single().rev)
    }

    @Test fun aFailedProjectionCannotBecomeALocalEditAndIsRetriedAfterRestart() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(files.newFolder())
        var actual = value(false)
        var allowed = false
        fun store() = CompanionPreferences(context, { mapOf("call_return_enabled" to actual) }, { _, _, payload, _ ->
            if (allowed) { actual = payload; true } else false
        })
        val incoming = CompanionPreferences.Record("call_return_enabled", 5uL, "pc", value(true))
        val first = store()
        assertEquals("failed", first.receive(pair, incoming).first)
        assertTrue(first.localChanges(pair).isEmpty())
        assertFalse(actual.getBoolean("value"))
        allowed = true
        val restarted = store()
        assertEquals(incoming.rev, restarted.snapshot(pair).single().rev)
        assertTrue(actual.getBoolean("value"))
        assertTrue(restarted.localChanges(pair).isEmpty())
    }

    @Test fun themeHasOneGlobalRevisionWhilePerPairPolicyRemainsSeparate() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(files.newFolder())
        var theme = value("system")
        val store = CompanionPreferences(context, { mapOf("theme" to theme, "host_auto_pause" to value(true)) },
            { _, key, payload, _ -> if (key == "theme") theme = payload; true })
        store.snapshot(pair)
        store.receive(pair, CompanionPreferences.Record("theme", 3uL, "pc", value("dark")))
        val second = store.snapshot("AA:BB:CC:DD:EE:00").associateBy { it.key }
        assertEquals(3uL, second.getValue("theme").rev)
        assertEquals(1uL, second.getValue("host_auto_pause").rev)
    }

    @Test fun malformedPolicyNeverRoundsOrAcceptsAnInvalidSecondStage() {
        for (v in listOf("01", "1.0", "1e1", "100.000000001", "NaN", "0.1234567891"))
            assertThrows(IllegalArgumentException::class.java) { CompanionPreferences.decimal(v) }
        assertEquals("43.5", CompanionPreferences.decimal(43.5f))
        assertThrows(IllegalArgumentException::class.java) {
            CompanionPreferences.validatePayload("conversation_policy", JSONObject().put("mode", "relative")
                .put("first", "20").put("second", "21").put("pause_media", false))
        }
    }

    @Test fun aFailedCanonicalWriteOrUnsupportedValueCannotApplyAnything() = runBlocking {
        val context = mock(Context::class.java)
        val blocked = files.newFile()
        `when`(context.noBackupFilesDir).thenReturn(blocked)
        var mutations = 0
        val store = CompanionPreferences(context, { mapOf("host_auto_pause" to value(true)) }, { _, _, _, _ -> mutations++; true })
        assertEquals("failed", store.receive(pair, CompanionPreferences.Record("host_auto_pause", 5uL, "pc", value(false))).first)
        assertEquals(0, mutations)
        val normal = mock(Context::class.java)
        `when`(normal.noBackupFilesDir).thenReturn(files.newFolder())
        val unsupported = CompanionPreferences(normal, { mapOf("host_auto_pause" to value(true)) }, { _, _, _, _ -> mutations++; true }, { _, _, _ -> false })
        assertEquals("unsupported", unsupported.receive(pair, CompanionPreferences.Record("host_auto_pause", 5uL, "pc", value(false))).first)
        assertEquals(0, mutations)
    }

    @Test fun anOfflineEditAfterServiceDeathSurvivesTheNextStartup() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(files.newFolder())
        var actual = value("system")
        var projections = 0
        fun store() = CompanionPreferences(context, { mapOf("theme" to actual) }, { _, _, payload, _ -> projections++; actual = payload; true })
        store().snapshot(pair)
        actual = value("dark") // App settings were edited while the foreground service was absent.
        val next = store().localChanges(pair).single()
        assertEquals("dark", next.payload.getString("value"))
        assertEquals(2uL, next.rev)
        assertEquals(0, projections)
    }

    @Test fun anAppliedDuplicateCannotOverwriteANewerLocalEditWaitingForItsObserver() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(files.newFolder())
        var actual = value(true)
        val store = CompanionPreferences(context, { mapOf("host_auto_pause" to actual) }, { _, _, payload, _ -> actual = payload; true })
        val remote = CompanionPreferences.Record("host_auto_pause", 5uL, "pc", value(false))
        assertEquals("applied", store.receive(pair, remote).first)
        actual = value(true)
        assertEquals("unchanged", store.receive(pair, remote).first)
        assertTrue(actual.getBoolean("value"))
        assertEquals(6uL, store.localChanges(pair).single().rev)
    }

    @Test fun unsupportedRevisionsRemainTypedAndARealLocalEditAdvancesPastThem() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(files.newFolder())
        var actual = value(true)
        var supported = false
        fun store() = CompanionPreferences(context, { mapOf("host_auto_pause" to actual) }, { _, _, payload, _ -> actual = payload; true }, { _, _, _ -> supported })
        val first = store()
        first.localChanges(pair)
        val incoming = CompanionPreferences.Record("host_auto_pause", 50uL, "pc", value(false))
        assertEquals("unsupported", first.receive(pair, incoming).first)
        val restarted = store()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { restarted.receive(pair, incoming.copy(payload = value(true))) } }
        supported = true
        actual = value(false)
        assertEquals(51uL, restarted.localChanges(pair).single().rev)
    }

    @Test fun aNewUserChoiceDuringRoomProjectionRetiresThatProjectionAndWinsTheNextRevision() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(files.newFolder())
        var actual = value(true)
        val store = CompanionPreferences(context, { mapOf("host_auto_pause" to actual) }, { _, _, payload, _ ->
            actual = payload
            actual = value(true) // User changes it while the Room receipt is pending.
            throw CompanionProjectionSuperseded()
        })
        val incoming = CompanionPreferences.Record("host_auto_pause", 5uL, "pc", value(false))
        assertEquals("failed", store.receive(pair, incoming).first)
        assertTrue(actual.getBoolean("value"))
        val next = store.localChanges(pair).single()
        assertEquals(6uL, next.rev)
        assertTrue(actual.getBoolean("value"))
    }

    @Test fun corruptLoadIsTransactionalAndCanRetryTheRepairedAtomicFile() = runBlocking {
        val context = mock(Context::class.java)
        val directory = files.newFolder()
        `when`(context.noBackupFilesDir).thenReturn(directory)
        var actual = value(true)
        fun store() = CompanionPreferences(context, { mapOf("host_auto_pause" to actual) }, { _, _, payload, _ -> actual = payload; true })
        store().snapshot(pair)
        val file = File(directory, "companion-preferences-v2")
        val valid = file.readText()
        file.writeText(JSONArray(valid).put(JSONObject().put("key", "broken")).toString())
        val retried = store()
        assertThrows(Exception::class.java) { runBlocking { retried.snapshot(pair) } }
        file.writeText(valid)
        assertEquals(1uL, retried.snapshot(pair).single().rev)
    }

    @Test fun supersedingAProjectionRemainsABoundedFailureWhenItsReceiptWriteFails() = runBlocking {
        val context = mock(Context::class.java)
        val directory = files.newFolder()
        `when`(context.noBackupFilesDir).thenReturn(directory)
        var actual = value(true)
        val store = CompanionPreferences(context, { mapOf("host_auto_pause" to actual) }, { _, _, _, _ ->
            actual = value(true)
            // Canonical pending value is already durable. Deny only the projection-receipt write.
            File(directory, "companion-preferences-v2.new").mkdir()
            throw CompanionProjectionSuperseded()
        })
        assertEquals("failed", store.receive(pair, CompanionPreferences.Record("host_auto_pause", 5uL, "pc", value(false))).first)
        assertTrue(actual.getBoolean("value"))
    }

    @Test fun aProjectionTimeoutCannotReapplyOverANewerUserChoice() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.noBackupFilesDir).thenReturn(files.newFolder())
        var actual = value(true)
        val store = CompanionPreferences(context, { mapOf("host_auto_pause" to actual) }, { _, _, payload, _ ->
            actual = payload
            try { delay(6000); true } catch (error: Exception) {
                if (actual.getBoolean("value") != payload.getBoolean("value")) throw CompanionProjectionSuperseded()
                throw error
            }
        })
        val newer = launch { delay(50); actual = value(true) }
        assertEquals("failed", store.receive(pair, CompanionPreferences.Record("host_auto_pause", 5uL, "pc", value(false))).first)
        newer.join()
        assertEquals(6uL, store.localChanges(pair).single().rev)
        assertTrue(actual.getBoolean("value"))
    }

    @Test fun aDurableNewLocalIntentProtectsColdStartAfterTheProjectionReceiptWriteFailed() = runBlocking {
        val context = mock(Context::class.java)
        val directory = files.newFolder()
        `when`(context.noBackupFilesDir).thenReturn(directory)
        var actual = value(true)
        fun intent(device: String, key: String) = LocalPreferenceEdits.token(context, device, key)
        val first = CompanionPreferences(context, { mapOf("host_auto_pause" to actual) }, { _, _, payload, _ ->
            actual = payload
            assertTrue(LocalPreferenceEdits.remember(context, pair, setOf("host_auto_pause")))
            actual = value(true)
            File(directory, "companion-preferences-v2.new").mkdir()
            throw CompanionProjectionSuperseded()
        }, localIntent = ::intent)
        assertEquals("failed", first.receive(pair, CompanionPreferences.Record("host_auto_pause", 5uL, "pc", value(false))).first)
        assertTrue(File(directory, "companion-preferences-v2.new").delete())
        var reapplications = 0
        val restarted = CompanionPreferences(context, { mapOf("host_auto_pause" to actual) }, { _, _, payload, _ ->
            reapplications++; actual = payload; true
        }, localIntent = ::intent)
        val winner = restarted.localChanges(pair).single()
        assertEquals(0, reapplications)
        assertEquals(6uL, winner.rev)
        assertTrue(winner.payload.getBoolean("value"))
        assertTrue(actual.getBoolean("value"))
    }

    @Test fun anOlderPendingSchemaWithoutAnIntentFenceIsDiagnosedWithoutApplyingIt() = runBlocking {
        val context = mock(Context::class.java)
        val directory = files.newFolder()
        `when`(context.noBackupFilesDir).thenReturn(directory)
        var applications = 0
        fun store() = CompanionPreferences(context, { mapOf("host_auto_pause" to value(true)) }, { _, _, _, _ -> applications++; true })
        store().snapshot(pair)
        val file = File(directory, "companion-preferences-v2")
        val rows = JSONArray(file.readText())
        rows.getJSONObject(0).remove("_intent")
        file.writeText(rows.toString())
        assertThrows(Exception::class.java) { runBlocking { store().snapshot(pair) } }
        assertEquals("failed", store().receive(pair, CompanionPreferences.Record("host_auto_pause", 5uL, "pc", value(false))).first)
        assertEquals(0, applications)
    }
}
