package me.kavishdevar.librepods.presentation

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.ExperimentalSerializationApi
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.database.Converters
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class UpstreamResourcesTest {
    @Test fun eqLabelsAndNewLocalesResolveWithoutChangingForkOnlyDefaults() {
        val context = RuntimeEnvironment.getApplication()
        val locales = mapOf(
            "de" to listOf("Tiefen", "Mitten", "Höhen"), "es" to listOf("Graves", "Medios", "Agudos"),
            "fr" to listOf("Graves", "Médiums", "Aigus"), "ja" to listOf("低音", "中音", "高音"),
            "ko" to listOf("저음", "중음", "고음"), "pt" to listOf("Graves", "Médios", "Agudos"),
            "tr" to listOf("Bas", "Orta", "Tiz"), "uk" to listOf("Низькі", "Середні", "Високі"),
            "vi" to listOf("Thấp", "Trung", "Cao"), "zh-CN" to listOf("低频", "中频", "高频"),
            "zh-TW" to listOf("低頻", "中頻", "高頻"), "en" to listOf("Low", "Mid", "High"))
        for ((tag, expected) in locales) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag)))
            }
            val resources = context.createConfigurationContext(configuration).resources
            assertEquals(tag, expected, listOf(R.string.eq_low, R.string.eq_mid, R.string.eq_high).map(resources::getString))
            assertTrue(resources.getString(R.string.noise_control).isNotBlank())
            if (tag in setOf("ja", "ko")) {
                assertNotEquals(context.getString(R.string.back), resources.getString(R.string.back))
                assertEquals(context.getString(R.string.noise_cycle_shared_description), resources.getString(R.string.noise_cycle_shared_description))
                assertEquals(context.getString(R.string.mute_unmute_notice), resources.getString(R.string.mute_unmute_notice))
                for (count in listOf(0, 1, 2)) {
                    val expectedAge = if (tag == "ja") "最後に検出: ${count}秒前" else "마지막 감지: ${count}초 전"
                    assertEquals(expectedAge, resources.getQuantityString(R.plurals.dongle_finder_last, count, count))
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun savedStemAssignmentsRetainTheirSerializedIdentifiers() {
        val saved = AppleSettings(leftLongPressAction = StemAction.DIGITAL_ASSISTANT,
            rightLongPressAction = StemAction.CYCLE_NOISE_CONTROL_MODES)
        val encoded = Cbor { encodeDefaults = true }.encodeToByteArray(AppleSettings.serializer(), saved)
        assertTrue(encoded.toString(Charsets.ISO_8859_1).contains("CYCLE_NOISE_CONTROL_MODES"))
        assertEquals(saved, Converters.bytesToAppleSettings(encoded))
        assertEquals(saved, Converters.bytesToAppleSettings(Converters.appleSettingsToBytes(saved)))
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, StemAction.fromString("CYCLE_NOISE_CONTROL_MODES"))
    }
}
