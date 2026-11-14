package me.kavishdevar.librepods.audio

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import me.kavishdevar.librepods.core.data.apple.MotionReport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SpatialPoseProfileStoreTest {
    private lateinit var preferences: SharedPreferences
    private val key = SpatialPoseProfileKey("00:AA:22:33:44:55", listOf(1, 0, 1, 0), "A3064", "test-firmware")
    private val rows = listOf(listOf(0.0, 0.0, -1.0), listOf(0.0, 1.0, 0.0), listOf(1.0, 0.0, 0.0))
    private val profile get() = checkNotNull(SpatialPoseProfile.fromBasis(rows))

    @Before fun prepare() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("spatial_profile_store_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @Test fun reloadRetainsOnlyValidatedBasisAndStartsANewSensorClockEpoch() {
        assertTrue(SpatialPoseProfileStore(preferences).save(key, profile))
        val restored = checkNotNull(SpatialPoseProfileStore(preferences).load(key.copy(macAddress = key.macAddress.lowercase())))
        assertEquals(rows, restored.headFromRaw)
        assertNull(restored.referenceTimestampNs); assertNull(restored.referenceCounter)
        val stored = preferences.all.values.single().toString()
        assertFalse(stored.contains("timestamp")); assertFalse(stored.contains("counter"))
        val converter = SpatialPoseConverter(restored, 3)
        val first = MotionReport(10, listOf(0, -23170, 0), listOf(0, 0, 0), listOf(0, 0, 0), listOf(-1000, 0, 0), 4)
        assertNull(converter.accept(first))
        val next = checkNotNull(converter.accept(first.copy(timestampNs = 20_000_010, counter = 8)))
        assertEquals(3, next.discontinuity)
    }

    @Test fun everySourceIdentityFieldMustMatchAndRoleTwoIsNotInferred() {
        val store = SpatialPoseProfileStore(preferences)
        assertTrue(store.save(key, profile))
        for (other in listOf(key.copy(macAddress = "00:AA:22:33:44:66"), key.copy(rawRole = listOf(2, 0, 1, 0)),
            key.copy(rawRole = listOf(1, 0, 1, 1)), key.copy(modelNumber = "A3048"),
            key.copy(firmware = "other-firmware"), key.copy(formatVersion = 2))) assertNull(store.load(other))
        assertEquals(1, preferences.all.size)
    }

    @Test fun unknownCorruptOrMismatchedStoredDataIsIgnored() {
        val store = SpatialPoseProfileStore(preferences)
        assertTrue(store.save(key, profile))
        val slot = preferences.all.keys.single()
        val original = checkNotNull(preferences.getString(slot, null))
        val invalid = listOf("not-json", "{}", JSONObject(original).put("version", 2).toString(),
            JSONObject(original).put("key", JSONObject().put("mac", "other")).toString(),
            JSONObject(original).put("head_from_raw", JSONArray(listOf(listOf(1, 0, 0), listOf(0, 1, 0), listOf(0, 0, -1)))).toString(),
            JSONObject(original).put("head_from_raw", JSONArray(listOf(listOf("1", 0, 0), listOf(0, 1, 0), listOf(0, 0, 1)))).toString())
        for (text in invalid) {
            preferences.edit().putString(slot, text).commit()
            assertNull(store.load(key))
        }
        preferences.edit().putInt(slot, 1).commit()
        assertNull(store.load(key))
    }

    @Test fun incompleteSourceKeysCannotSaveOrLoadAnything() {
        val store = SpatialPoseProfileStore(preferences)
        for (bad in listOf(key.copy(macAddress = "bad"), key.copy(rawRole = emptyList()), key.copy(rawRole = listOf(0)),
            key.copy(rawRole = listOf(1, -1)), key.copy(modelNumber = ""), key.copy(firmware = ""), key.copy(formatVersion = 2))) {
            assertFalse(store.save(bad, profile)); assertNull(store.load(bad))
        }
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun legacyPro3ModelAliasesReuseOneBasisWithoutRestoringAnOldClock() {
        legacy(key)
        val store = SpatialPoseProfileStore(preferences)
        for (model in listOf("A3063", "A3064", "A3065", "AIRPODS_PRO_3")) {
            val restored = checkNotNull(store.load(key.copy(modelNumber = model)))
            assertEquals(rows, restored.headFromRaw)
            assertNull(restored.referenceTimestampNs); assertNull(restored.referenceCounter)
            assertEquals("AIRPODS_PRO_3", spatialPoseModelIdentity(model))
        }
        assertEquals(1, preferences.all.size) // Loading is read-only.
        for (other in listOf(key.copy(macAddress = "00:AA:22:33:44:66"),
            key.copy(rawRole = listOf(2, 0, 1, 0)), key.copy(rawRole = listOf(1, 0, 1, 1)),
            key.copy(firmware = "other-firmware"), key.copy(formatVersion = 2))) assertNull(store.load(other))
    }

    @Test fun identicalLegacyDuplicatesAreAcceptedButConflictingValidBasesAreRefused() {
        legacy(key); legacy(key.copy(modelNumber = "A3063"))
        val store = SpatialPoseProfileStore(preferences)
        assertEquals(rows, checkNotNull(store.load(key.copy(modelNumber = "A3065"))).headFromRaw)
        legacy(key.copy(modelNumber = "A3065"), identityRows)
        for (model in listOf("A3063", "A3064", "A3065")) assertNull(store.load(key.copy(modelNumber = model)))
    }

    @Test fun invalidLegacyDataCannotBecomeACalibrationOrHideOneUnambiguousValidBasis() {
        val invalidRows = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, -1.0))
        legacy(key.copy(modelNumber = "A3063"), invalidRows)
        val store = SpatialPoseProfileStore(preferences)
        assertNull(store.load(key))
        legacy(key)
        assertEquals(rows, checkNotNull(store.load(key.copy(modelNumber = "A3065"))).headFromRaw)
    }

    @Test fun canonicalSaveReplacesOnlyTheSameScopeAndWinsForEveryAlias() {
        val firstAlias = legacy(key); val secondAlias = legacy(key.copy(modelNumber = "A3063"))
        val otherRole = key.copy(rawRole = listOf(2, 0, 1, 0), modelNumber = "A3065")
        val otherMac = key.copy(macAddress = "00:AA:22:33:44:66")
        val otherFirmware = key.copy(firmware = "other-firmware")
        val preserved = listOf(legacy(otherRole), legacy(otherMac), legacy(otherFirmware))
            .associateWith { preferences.getString(it, null) }
        val store = SpatialPoseProfileStore(preferences)
        assertTrue(store.save(key.copy(modelNumber = "A3063"), checkNotNull(SpatialPoseProfile.fromBasis(identityRows))))
        assertFalse(preferences.contains(firstAlias)); assertFalse(preferences.contains(secondAlias))
        for ((slot, value) in preserved) assertEquals(value, preferences.getString(slot, null))
        for (model in listOf("A3063", "A3064", "A3065"))
            assertEquals(identityRows, checkNotNull(store.load(key.copy(modelNumber = model))).headFromRaw)
        assertEquals(rows, checkNotNull(store.load(otherRole)).headFromRaw)
        assertEquals(4, preferences.all.size)
    }

    @Test fun aCorruptCanonicalEntryNeverResurrectsAnOlderValidAlias() {
        val store = SpatialPoseProfileStore(preferences)
        assertTrue(store.save(key, checkNotNull(SpatialPoseProfile.fromBasis(identityRows))))
        val canonical = preferences.all.keys.single()
        legacy(key) // Simulate an older backup remaining alongside the canonical choice.
        assertEquals(identityRows, checkNotNull(store.load(key)).headFromRaw)
        preferences.edit().putString(canonical, "not-json").commit()
        for (model in listOf("A3063", "A3064", "A3065")) assertNull(store.load(key.copy(modelNumber = model)))
        preferences.edit().putInt(canonical, 1).commit()
        assertNull(store.load(key))
    }

    @Test fun otherModelNumbersKeepTheirExactOriginalIdentity() {
        val store = SpatialPoseProfileStore(preferences)
        val other = key.copy(modelNumber = "A3048")
        assertEquals("A3048", spatialPoseModelIdentity(other.modelNumber))
        assertTrue(store.save(other, profile)); assertEquals(rows, checkNotNull(store.load(other)).headFromRaw)
        assertNull(store.load(other.copy(modelNumber = "A3047")))
        assertNull(store.load(key))
    }

    private val identityRows = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))

    /** Original format-1 raw-model fixture, deliberately independent of the new identity helper. */
    private fun legacy(source: SpatialPoseProfileKey, basis: List<List<Double>> = rows): String {
        val identity = JSONObject().put("mac", source.macAddress.uppercase(Locale.ROOT))
            .put("role", JSONArray(source.rawRole)).put("model_number", source.modelNumber)
            .put("firmware", source.firmware).put("format", source.formatVersion)
        val slot = MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val value = JSONObject().put("version", 1).put("key", identity)
            .put("head_from_raw", JSONArray(basis.map { JSONArray(it) }))
        assertTrue(preferences.edit().putString(slot, value.toString()).commit())
        return slot
    }
}
