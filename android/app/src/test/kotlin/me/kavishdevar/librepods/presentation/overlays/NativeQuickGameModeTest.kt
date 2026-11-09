package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.ConnectionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class NativeQuickGameModeTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun blockedPreferenceWritesLeaveTheChoiceImmediateAndKeepOnlyTheLatestTail() = runBlocking {
        fixture { f ->
            val started = System.nanoTime()
            assertTrue(f.choices.edit(true))
            assertTrue("Main must not wait for the settings writer", (System.nanoTime() - started) / 1_000_000 < 250)
            assertTrue(f.entered.await(1, TimeUnit.SECONDS))
            assertTrue(f.choices.displaySettings(f.settings.value).nativeGameModeEnabled)
            assertFalse(f.settings.value.nativeGameModeEnabled)
            assertTrue(f.choices.edit(false))
            assertFalse(f.choices.displaySettings(f.settings.value).nativeGameModeEnabled)
            f.release.countDown()
            withTimeout(2_000) { while (f.choices.state.value.pending || f.choices.state.value.requested != null) delay(5) }
            assertEquals(listOf(true, false), f.applied)
            assertFalse(f.settings.value.nativeGameModeEnabled)
            assertFalse("An older combined settings emission cannot undo the completed choice",
                f.choices.displaySettings(AppleSettings(nativeGameModeEnabled = true)).nativeGameModeEnabled)
        }
    }

    @Test fun aChoiceWaitingForSettingsCannotCrossAnOwnerChangeOrDismissal() = runBlocking {
        for (loss in listOf("connection", "owner", "dismiss", "freeze")) fixture { f ->
            assertTrue(f.choices.edit(true))
            assertTrue(f.entered.await(1, TimeUnit.SECONDS))
            when (loss) {
                "connection" -> f.connection.value++
                "owner" -> f.current.set(false)
                "freeze" -> {
                    f.current.set(false); f.choices.cancelPending(); f.current.set(true); f.choices.observe()
                }
                else -> f.scope.cancel()
            }
            f.release.countDown()
            withTimeout(2_000) { while (f.applied.isEmpty()) delay(5) }
            assertEquals(listOf(false), f.applied)
            assertFalse(f.settings.value.nativeGameModeEnabled)
            if (loss == "freeze") {
                assertTrue(f.choices.edit(true))
                withTimeout(2_000) { while (!f.settings.value.nativeGameModeEnabled) delay(5) }
                assertEquals(listOf(false, true), f.applied)
            } else if (loss == "dismiss") withTimeout(2_000) { checkNotNull(f.scope.coroutineContext[Job]).join() }
            else withTimeout(2_000) { while (f.choices.state.value.pending) delay(5) }
            assertEquals("Retired work must not show a failure for the current dialog", emptyList<Unit>(), f.failures)
        }
    }

    private suspend fun fixture(check: suspend (Fixture) -> Unit) {
        val f = Fixture()
        val held = CountDownLatch(1)
        val holder = Thread { synchronized(f.lock) { held.countDown(); f.release.await(3, TimeUnit.SECONDS) } }.apply { start() }
        try { assertTrue(held.await(1, TimeUnit.SECONDS)); check(f) }
        finally { f.release.countDown(); holder.join(2_000); withTimeout(2_000) { checkNotNull(f.scope.coroutineContext[Job]).cancelAndJoin() } }
    }

    private class Fixture {
        val lock = Any()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val settings = MutableStateFlow(AppleSettings())
        val connection = MutableStateFlow(1)
        val applied = CopyOnWriteArrayList<Boolean>()
        val failures = CopyOnWriteArrayList<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val device = mock(AppleDevice::class.java)
        val choices: NativeQuickGameMode
        init {
            `when`(device.settings).thenReturn(settings)
            `when`(device.connectionNumber).thenReturn(connection)
            `when`(device.connectionState).thenReturn(MutableStateFlow(ConnectionState.CONNECTED))
            doAnswer { call ->
                assertNotEquals("Preference writes must run off Main", Looper.getMainLooper(), Looper.myLooper())
                entered.countDown()
                synchronized(lock) {
                    val transform = call.getArgument<(AppleSettings) -> AppleSettings>(0)
                    settings.value = transform(settings.value).also { applied += it.nativeGameModeEnabled }
                }
                null
            }.`when`(device).updateSettings(any<(AppleSettings) -> AppleSettings>() ?: { it })
            choices = NativeQuickGameMode(device, scope, current::get) { failures += Unit }
        }
    }
}
