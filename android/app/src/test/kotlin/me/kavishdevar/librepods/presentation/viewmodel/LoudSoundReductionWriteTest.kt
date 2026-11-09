package me.kavishdevar.librepods.presentation.viewmodel

import android.app.Application
import android.bluetooth.BluetoothSocket
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.database.app.AppStateEntity
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.repository.HeartRateRepository
import me.kavishdevar.librepods.repository.RecordingRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class LoudSoundReductionWriteTest {
    private val device = mock(AppleDevice::class.java)
    private val aacp = mock(AACPManager::class.java)
    private val socket = mock(BluetoothSocket::class.java)
    private val connection = MutableStateFlow(1)
    private lateinit var viewModel: AppleViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        `when`(device.context).thenReturn(RuntimeEnvironment.getApplication())
        `when`(device.events).thenReturn(MutableSharedFlow<AppleEvent>())
        `when`(device.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
        `when`(device.connectionNumber).thenReturn(connection)
        `when`(device.state).thenReturn(MutableStateFlow(AppleState(controlSession = 1,
            loudSoundReductionReadStatus = SettingsReadStatus.AVAILABLE)))
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings()))
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3)))
        `when`(device.aacp).thenReturn(aacp)
        `when`(aacp.captureAudioSocket()).thenReturn(socket)
        `when`(device.loudSoundReductionWritable).thenReturn(true)
        val app = mock(AppDataRepository::class.java)
        `when`(app.state).thenReturn(MutableStateFlow(AppStateEntity()))
        `when`(app.settings).thenReturn(MutableStateFlow(AppSettingsEntity()))
        viewModel = AppleViewModel(device, app, mock(RecordingRepository::class.java), mock(HeartRateRepository::class.java))
    }

    @After fun cleanup() { viewModel.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun repeatedTapsDoNotQueueWritesAndCompletionRestoresTheUiHint() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(device.writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(1), null, null, null)).thenAnswer {
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            true
        }
        val observer = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }
        var write: Job? = null
        try {
            withTimeout(2_000) { while (!viewModel.uiState.value.loudSoundReductionWritable) delay(5) }
            write = viewModel.writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { while (viewModel.uiState.value.loudSoundReductionWritable) delay(5) }
            repeat(3) { viewModel.writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(1)).join() }
            verify(device, times(1)).writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(1), null, null, null)
            release.countDown()
            write.join()
            withTimeout(2_000) { while (!viewModel.uiState.value.loudSoundReductionWritable) delay(5) }
        } finally { release.countDown(); write?.cancelAndJoin(); observer.cancelAndJoin() }
    }

    @Test fun queuedChoiceCannotWriteAfterTheConnectionOwnerChanges() = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val write = viewModel.writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(1))
        connection.value++
        main.scheduler.runCurrent()
        withTimeout(2_000) { while (!write.isCompleted) { main.scheduler.runCurrent(); delay(5) } }
        verify(device, never()).writeATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION, byteArrayOf(1), null, null, null)
        assertTrue(viewModel.uiState.value.loudSoundReductionWritable)
    }
}
