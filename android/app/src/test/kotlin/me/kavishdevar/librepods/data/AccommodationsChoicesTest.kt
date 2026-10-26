package me.kavishdevar.librepods.data

import android.app.Application
import android.content.Context
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import me.kavishdevar.librepods.dongle.DongleProtocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AccommodationsChoicesTest {
    private fun report(phone: Boolean, media: Boolean, session: Long = 1) = AccommodationsReport.parse(
        ByteArray(140).also { byteArrayOf(4, 0, 4, 0, 0x53, 0, 0x84.toByte(), 0, 2, 2,
            if (phone) 1 else 2, if (media) 1 else 2).copyInto(it) }, session, 1, false, false)!!

    @Test fun nativeAndDongleAddressesShareOneCanonicalPairKeyAndRememberedChoices() {
        val native = "10:2a:30:4b:50:6c"
        val wireAddress = byteArrayOf(0x10, 0x2a, 0x30, 0x4b, 0x50, 0x6c)
        val status = DongleProtocol.status(DongleProtocol.header(2).also { wireAddress.copyInto(it, 12) })
        val key = accommodationsPairKey(native)
        assertEquals("10:2A:30:4B:50:6C", key)
        assertEquals(key, accommodationsPairKey("102A304B506C"))
        assertEquals(key, accommodationsPairKey(status.address))
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("accommodations-identity-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val choices = AccommodationsChoices(preferences)
        choices.save(key, true, false)
        assertEquals(true to false, choices.read(accommodationsPairKey(status.address), report(false, false)))
        choices.save(accommodationsPairKey(status.address), false, true)
        assertEquals(false to true, choices.read(accommodationsPairKey(native), report(false, false)))
        val other = accommodationsPairKey("10:2A:30:4B:50:6D")
        assertNotEquals(key, other)
        choices.save(other, false, false)
        assertEquals(false to false, choices.read(other, report(false, false)))
        assertEquals(false to true, choices.read(key, report(false, false)))
    }

    @Test fun missingMalformedAndUnselectedIdentitiesCannotCreatePreferenceEntries() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("accommodations-invalid-identity-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val choices = AccommodationsChoices(preferences)
        for (invalid in listOf(null, "", "unavailable", "00:00:00:00:00:00", "000000000000",
            "10:2A:30:4B:50", "10:2A:30:4B:50:6C:7D", "10:2A:30:4B:50:GG", " 10:2A:30:4B:50:6C", "16:42:48:75:80:108")) {
            assertNull(accommodationsPairKey(invalid))
            choices.save(invalid, false, false)
        }
        for (invalid in listOf<List<Int>?>(null, emptyList(), listOf(0, 0, 0, 0, 0, 0),
            listOf(16, 42, 48, 75, 80), listOf(16, 42, 48, 75, 80, 108, 125),
            listOf(-1, 42, 48, 75, 80, 108), listOf(256, 42, 48, 75, 80, 108))) {
            assertNull(accommodationsPairKey(invalid))
            choices.save(accommodationsPairKey(invalid), false, false)
        }
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun masterOffChoicesSurviveNewSessionsAndARecreatedPreferencesOwnerWithoutLeakingAcrossPairs() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("accommodations-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val choices = AccommodationsChoices(preferences)
        val first = "10:2A:30:4B:50:6C"
        val second = "10:2A:30:4B:50:6D"
        assertEquals(true to true, choices.read(first, report(false, false)))
        assertEquals(true to false, choices.read(first, report(true, false)))
        choices.save(first, true, false)
        assertEquals(true to false, choices.read(first, report(false, false, session = 2)))
        val restarted = AccommodationsChoices(context.getSharedPreferences("accommodations-test", Context.MODE_PRIVATE))
        assertEquals(true to false, restarted.read(first, report(false, false, session = 3)))
        assertEquals(true to true, restarted.read(second, report(false, false)))
        restarted.save(second, false, true)
        assertEquals(false to true, restarted.read(second, report(false, false)))
        assertEquals(true to false, restarted.read(first, report(false, false)))
        assertEquals(false to true, restarted.read(first, report(false, true)))
        restarted.save(first, false, false)
        val explicitNone = AccommodationsChoices(context.getSharedPreferences("accommodations-test", Context.MODE_PRIVATE))
        assertEquals(false to false, explicitNone.read(first, report(false, false, session = 4)))
    }
}
