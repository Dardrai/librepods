package me.kavishdevar.librepods.companion

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.database.app.AppSettingsDao
import me.kavishdevar.librepods.database.app.AppStateDao
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.presentation.design.NightTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class CompanionLocalEditsTest {
    @get:Rule val files = TemporaryFolder()
    private val pair = "AA:BB:CC:DD:EE:FF"
    private fun context(): Context = mock(Context::class.java).also { `when`(it.noBackupFilesDir).thenReturn(files.newFolder()) }
    private fun pod(context: Context): AppleDevice {
        val bluetooth = mock(BluetoothDevice::class.java)
        `when`(bluetooth.address).thenReturn(pair)
        `when`(bluetooth.name).thenReturn("Fixture")
        return AppleDevice(context, mock(BluetoothAdapter::class.java), bluetooth, ConnectionState.DISCONNECTED)
    }
    @Test fun explicitDeviceEditsAreDurablePerFieldAndRemoteProjectionDoesNotForgeLocalIntent() {
        val context = context(); val pod = pod(context)
        val old = LocalPreferenceEdits.token(context, pair, "host_auto_pause")
        pod.updateSettings { it.copy(hostAutoPause = false) }
        val local = LocalPreferenceEdits.token(context, pair, "host_auto_pause")
        assertNotEquals(old, local)
        assertEquals("0".repeat(32), LocalPreferenceEdits.token(context, pair, "connection_popup_enabled"))
        pod.updateSettingsFromCompanion { it.copy(hostAutoPause = true) }
        assertEquals(local, LocalPreferenceEdits.token(context, pair, "host_auto_pause"))
    }
    @Test fun aFailedJournalWriteRefusesTheExplicitEditBeforePublishingIt() {
        val context = context(); val pod = pod(context)
        File(context.noBackupFilesDir, "companion-local-edits-v2.new").mkdir()
        var errors = 0; pod.onSettingsSaveFailed = { errors++ }
        pod.updateSettings { it.copy(hostAutoPause = false) }
        assertTrue(pod.settings.value.hostAutoPause)
        assertEquals(1, errors)
    }
    @Test fun corruptJournalNeverTurnsIntoAnEmptyChoiceHistory() {
        val context = context()
        File(context.noBackupFilesDir, "companion-local-edits-v2").writeText("{}")
        assertThrows(Exception::class.java) { LocalPreferenceEdits.token(context, pair, "host_auto_pause") }
        assertFalse(LocalPreferenceEdits.remember(context, pair, setOf("host_auto_pause")))
    }
    @Test fun explicitThemeEditsUseTheGlobalFenceWhileRemoteThemeProjectionDoesNot() = runBlocking {
        val context = context()
        val repository = AppDataRepository(mock(AppSettingsDao::class.java), mock(AppStateDao::class.java),
            { LocalPreferenceEdits.remember(context, "global", setOf("theme")) })
        repository.awaitInitialized()
        repository.updateSettings { it.copy(nightMode = NightTheme.Dark) }
        val token = LocalPreferenceEdits.token(context, pair, "theme")
        assertNotEquals("0".repeat(32), token)
        assertEquals(token, LocalPreferenceEdits.token(context, "AA:BB:CC:DD:EE:00", "theme"))
        assertTrue(repository.applySharedTheme(NightTheme.Light) { true })
        assertEquals(token, LocalPreferenceEdits.token(context, pair, "theme"))
    }
}
