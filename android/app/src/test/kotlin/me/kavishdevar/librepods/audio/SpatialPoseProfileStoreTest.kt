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
            key.copy(rawRole = listOf(1, 0, 1, 1)), key.copy(modelNumber = "A3063"),
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
}
