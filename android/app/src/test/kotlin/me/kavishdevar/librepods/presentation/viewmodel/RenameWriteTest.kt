package me.kavishdevar.librepods.presentation.viewmodel

import android.app.Application
import android.bluetooth.BluetoothSocket
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class RenameWriteTest {
    private val device = mock(AppleDevice::class.java)
    private val aacp = mock(AACPManager::class.java)
    private val socket = mock(BluetoothSocket::class.java)
    private var activeSocket: BluetoothSocket? = socket
    private val connection = MutableStateFlow(ConnectionState.CONNECTED)
    private val state = MutableStateFlow(AppleState(controlSession = 1))
    private val metadata = MutableStateFlow(AppleMetadata(name = "Original", model = AirPodsModel.AIRPODS_PRO_3))
    private lateinit var viewModel: AppleViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        `when`(device.context).thenReturn(RuntimeEnvironment.getApplication())
        `when`(device.events).thenReturn(MutableSharedFlow<AppleEvent>())
        `when`(device.connectionState).thenReturn(connection)
        `when`(device.connectionNumber).thenReturn(MutableStateFlow(1))
        `when`(device.state).thenReturn(state)
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings()))
        `when`(device.metadata).thenReturn(metadata)
        `when`(device.aacp).thenReturn(aacp)
        `when`(aacp.captureAudioSocket()).thenAnswer { activeSocket }
        val app = mock(AppDataRepository::class.java)
        `when`(app.state).thenReturn(MutableStateFlow(AppStateEntity()))
        `when`(app.settings).thenReturn(MutableStateFlow(AppSettingsEntity()))
        viewModel = AppleViewModel(device, app, mock(RecordingRepository::class.java), mock(HeartRateRepository::class.java))
    }

    @After fun cleanup() { viewModel.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun aFreshInformationReportConfirmsWithoutRefreshing() = runBlocking<Unit> {
        `when`(device.renameDevice("Studio", socket)).thenAnswer {
            metadata.value = metadata.value.copy(name = "Studio")
            true
        }
        assertTrue(viewModel.renameDevice("Studio") { error("No refresh after a matching report") })
        verify(device, times(1)).renameDevice("Studio", socket)
    }

    @Test fun aFailedSendDoesNotRefreshOrInventMetadata() = runBlocking<Unit> {
        val before = metadata.value
        assertFalse(viewModel.renameDevice("Studio") { error("Do not refresh after a failed send") })
        verify(device, times(1)).renameDevice("Studio", socket)
        assertSame(before, metadata.value)
    }

    @Test fun oneRefreshWaitsForTheReplacementInformationReport() = runBlocking<Unit> {
        val before = metadata.value
        `when`(device.renameDevice("Studio", socket)).thenReturn(true)
        var refreshes = 0
        assertTrue(viewModel.renameDevice("Studio") { requested ->
            assertSame(device, requested)
            assertSame(before, metadata.value)
            refreshes++
            activeSocket = mock(BluetoothSocket::class.java)
            state.value = state.value.copy(controlSession = 2)
            launch { delay(20); metadata.value = before.copy(name = "Studio") }
            DeviceSettingsRefreshResult.RECONNECTED
        })
        assertEquals(1, refreshes)
        verify(device, times(1)).renameDevice("Studio", socket)
    }

    @Test fun refreshFailureDoesNotReplayTheRenameOrInventMetadata() = runTest {
        val before = metadata.value
        `when`(device.renameDevice("Studio", socket)).thenReturn(true)
        var refreshes = 0
        assertFalse(viewModel.renameDevice("Studio") { refreshes++; DeviceSettingsRefreshResult.FAILED })
        assertEquals(1, refreshes)
        verify(device, times(1)).renameDevice("Studio", socket)
        assertSame(before, metadata.value)
    }

    @Test fun noFreshInformationTimesOutWithoutReplayingTheRename() = runTest {
        val before = metadata.value
        `when`(device.renameDevice("Studio", socket)).thenReturn(true)
        assertFalse(viewModel.renameDevice("Studio") {
            activeSocket = mock(BluetoothSocket::class.java)
            state.value = state.value.copy(controlSession = 2)
            DeviceSettingsRefreshResult.RECONNECTED
        })
        verify(device, times(1)).renameDevice("Studio", socket)
        assertSame(before, metadata.value)
    }

    @Test fun anUnrelatedConnectionCannotSupplyTheConfirmation() = runBlocking<Unit> {
        `when`(device.renameDevice("Studio", socket)).thenReturn(true)
        assertFalse(viewModel.renameDevice("Studio") {
            activeSocket = mock(BluetoothSocket::class.java)
            state.value = state.value.copy(controlSession = 2)
            launch {
                delay(20)
                activeSocket = mock(BluetoothSocket::class.java)
                state.value = state.value.copy(controlSession = 3)
                metadata.value = metadata.value.copy(name = "Studio")
            }
            DeviceSettingsRefreshResult.RECONNECTED
        })
        verify(device, times(1)).renameDevice("Studio", socket)
    }

    @Test fun cancellationRemainsCancellationAfterTheSingleSend() = runTest {
        `when`(device.renameDevice("Studio", socket)).thenReturn(true)
        try {
            viewModel.renameDevice("Studio") { throw CancellationException("Caller stopped") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        verify(device, times(1)).renameDevice("Studio", socket)
    }

    @Test fun disconnectedRenameDoesNotWrite() = runBlocking<Unit> {
        connection.value = ConnectionState.DISCONNECTED
        assertFalse(viewModel.renameDevice("Studio") { error("No disconnected refresh") })
        verify(device, never()).renameDevice("Studio", socket)
    }

    @Test fun replacedSocketCannotReceiveTheQueuedName() = runBlocking<Unit> {
        `when`(aacp.captureAudioSocket()).thenReturn(socket, mock(BluetoothSocket::class.java))
        assertFalse(viewModel.renameDevice("Studio") { error("No replacement refresh") })
        verify(device, never()).renameDevice("Studio", socket)
    }

    @Test fun changedControlSessionCannotReceiveTheQueuedName() = runBlocking<Unit> {
        var reads = 0
        `when`(aacp.captureAudioSocket()).thenAnswer {
            if (++reads == 2) state.value = state.value.copy(controlSession = 2)
            socket
        }
        assertFalse(viewModel.renameDevice("Studio") { error("No stale-session refresh") })
        verify(device, never()).renameDevice("Studio", socket)
    }

    @Test fun changedMetadataCannotReceiveTheQueuedName() = runBlocking<Unit> {
        var reads = 0
        `when`(aacp.captureAudioSocket()).thenAnswer {
            if (++reads == 2) metadata.value = metadata.value.copy(name = "Changed elsewhere")
            socket
        }
        assertFalse(viewModel.renameDevice("Studio") { error("No stale-metadata refresh") })
        verify(device, never()).renameDevice("Studio", socket)
    }
}
