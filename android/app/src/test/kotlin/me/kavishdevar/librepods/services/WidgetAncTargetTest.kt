package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.database.widget.WidgetConfigDao
import me.kavishdevar.librepods.database.widget.WidgetConfigEntity
import me.kavishdevar.librepods.repository.WidgetConfigRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetAncTargetTest {
    private lateinit var service: LibrePodsService
    private lateinit var devices: MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
    private val firstAddress = MacAddress("00:11:22:33:44:55")
    private val otherAddress = MacAddress("00:11:22:33:44:66")
    private val first = mock(AppleDevice::class.java)
    private val other = mock(AppleDevice::class.java)
    private val firstConnection = MutableStateFlow(ConnectionState.CONNECTED)
    private val firstCommands = CopyOnWriteArrayList<Int>()
    private val otherCommands = CopyOnWriteArrayList<Int>()
    private lateinit var dao: Rows

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        service = Robolectric.buildService(LibrePodsService::class.java).get()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        `when`(first.macAddress).thenReturn(firstAddress)
        `when`(other.macAddress).thenReturn(otherAddress)
        `when`(first.connectionState).thenReturn(firstConnection)
        `when`(other.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
        doAnswer { firstCommands += it.getArgument<Int>(1); true }.`when`(first)
            .setControlCommand(eq(ControlCommandIdentifier.LISTENING_MODE) ?: ControlCommandIdentifier.LISTENING_MODE, anyInt())
        doAnswer { otherCommands += it.getArgument<Int>(1); true }.`when`(other)
            .setControlCommand(eq(ControlCommandIdentifier.LISTENING_MODE) ?: ControlCommandIdentifier.LISTENING_MODE, anyInt())
        @Suppress("UNCHECKED_CAST")
        val registry = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices = registry
        devices.value = linkedMapOf(otherAddress to other, firstAddress to first)
        dao = Rows()
    }

    @After fun close() {
        (field("serviceScope").get(service) as CoroutineScope).cancel()
        Dispatchers.resetMain()
    }

    @Test fun coldWidgetTapWaitsForPersistedConfigurationInsteadOfChoosingTheOtherConnectedPair() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            dao.beforeLoad = { entered.complete(Unit); release.await() }
            val repository = repository()
            entered.await()
            assertTrue(repository.widgetConfigs.value.isEmpty())
            dispatch(widget())
            assertTrue(firstCommands.isEmpty())
            assertTrue(otherCommands.isEmpty())
            release.complete(Unit)
            finishActions()
            assertEquals(listOf(3), firstCommands)
            assertTrue(otherCommands.isEmpty())
        }
    }

    @Test fun missingForgottenAndInvalidWidgetTargetsNeverFallBackEvenWithAnotherMacExtra() = runBlocking {
        repository()
        devices.value = mapOf(otherAddress to other)
        for (widgetId in listOf(11, 99, AppWidgetManager.INVALID_APPWIDGET_ID, -1)) {
            dispatch(widget(widgetId).putExtra("MAC_ADDRESS", otherAddress.value))
            finishActions()
        }
        assertTrue(firstCommands.isEmpty())
        assertTrue(otherCommands.isEmpty())
    }

    @Test fun explicitMacMustBeValidPresentAndConnectedAndCanOnlyControlItsOwnDevice() = runBlocking {
        repository()
        for (mac in listOf(null, "", "not-a-mac", "00:11:22:33:44:77")) {
            dispatch(action().putExtra("MAC_ADDRESS", mac))
            finishActions()
        }
        firstConnection.value = ConnectionState.DISCONNECTED
        dispatch(action().putExtra("MAC_ADDRESS", firstAddress.value))
        finishActions()
        assertTrue(firstCommands.isEmpty())
        assertTrue(otherCommands.isEmpty())
        firstConnection.value = ConnectionState.CONNECTED
        dispatch(action().putExtra("MAC_ADDRESS", firstAddress.value))
        finishActions()
        assertEquals(listOf(3), firstCommands)
        assertTrue(otherCommands.isEmpty())
    }

    @Test fun genuinelyUntargetedLegacyActionsStillUseTheFirstConnectedPairAndInvalidModesDoNothing() = runBlocking {
        repository()
        for (mode in listOf(-1, 0, 5, 255)) {
            dispatch(action().putExtra("ANC_MODE", mode))
            finishActions()
        }
        assertTrue(otherCommands.isEmpty())
        dispatch(action())
        finishActions()
        assertEquals(listOf(3), otherCommands)
        assertTrue(firstCommands.isEmpty())
    }

    @Test fun forgettingTheConfiguredPairDuringLookupDoesNotRetargetThePendingTap() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            dao.beforeLookup = { entered.complete(Unit); release.await() }
            repository()
            dispatch(widget())
            entered.await()
            devices.value = mapOf(otherAddress to other)
            release.complete(Unit)
            finishActions()
            assertTrue(firstCommands.isEmpty())
            assertTrue(otherCommands.isEmpty())
        }
    }

    @Test fun failedPersistedLookupCannotSelectAnUnrelatedConnectedPair() = runBlocking {
        dao.beforeLookup = { throw IllegalStateException("Database unavailable") }
        repository()
        dispatch(widget())
        finishActions()
        assertTrue(firstCommands.isEmpty())
        assertTrue(otherCommands.isEmpty())
    }

    private fun repository() = WidgetConfigRepository(dao).also {
        field("widgetConfigRepository\$delegate").set(service, lazyOf(it))
    }

    private fun action() = Intent(service, LibrePodsService::class.java)
        .setAction("ACTION_SET_ANC_MODE").putExtra("ANC_MODE", 3)
    private fun widget(id: Int = 11) = action().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    private fun dispatch(intent: Intent) { service.onStartCommand(intent, 0, 1) }
    private suspend fun finishActions() = withTimeout(10_000) {
        (field("serviceScope").get(service) as CoroutineScope).coroutineContext[Job]!!.children.toList().joinAll()
    }
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private inner class Rows : WidgetConfigDao {
        var beforeLoad: suspend () -> Unit = {}
        var beforeLookup: suspend () -> Unit = {}
        override suspend fun getAll(): List<WidgetConfigEntity> {
            beforeLoad()
            return listOf(WidgetConfigEntity(11, firstAddress))
        }
        override suspend fun getWidgetById(appWidgetId: Int): WidgetConfigEntity? {
            beforeLookup()
            return if (appWidgetId == 11) WidgetConfigEntity(11, firstAddress) else null
        }
        override suspend fun upsert(widgetConfig: WidgetConfigEntity) = Unit
    }
}
