/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeGameModeControllerLifecycleTest {
    private class Fixture : AutoCloseable {
        val parent = CoroutineScope(SupervisorJob())
        private val device = mock(AppleDevice::class.java).apply {
            `when`(connectionState).thenReturn(MutableStateFlow(ConnectionState.DISCONNECTED))
        }
        val controller = NativeGameModeController(device, parent) { false }
        val runner = ReflectionHelpers.getField<Job>(controller, "runner")
        val wakeup = ReflectionHelpers.getField<Channel<Unit>>(controller, "wakeup")
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        init {
            runner.invokeOnCompletion { failure.set(it); finished.countDown() }
        }

        fun awaitFinished() = assertTrue("Controller runner did not stop", finished.await(3, TimeUnit.SECONDS))

        override fun close() { controller.close(); parent.cancel() }
    }

    @Test fun closingWakeupDuringRunnerWaitFinishesNormally() {
        Fixture().use { f ->
            // Reproduce cleanup winning the race with the runner's suspended receive.
            f.wakeup.close()
            f.awaitFinished()
            assertNull(f.failure.get())
        }
    }

    @Test fun repeatedCloseAndParentCancellationDoNotThrowFromWakeup() {
        Fixture().use { f ->
            f.parent.cancel()
            f.controller.close()
            f.controller.close()
            f.awaitFinished()
            assertTrue(f.failure.get() == null || f.failure.get() is CancellationException)
            assertFalse(f.controller.state.value.desiredOn)
            assertFalse(f.controller.state.value.active)
        }
    }

    @Test fun handoverOfAnUnheldRoutePreservesPreferenceAndRejectsAClosedController() = runBlocking {
        Fixture().use { f ->
            f.controller.setDesiredOn(true)
            assertTrue(f.controller.suspendForHandover())
            assertTrue(f.controller.state.value.desiredOn)
            assertFalse(f.controller.state.value.active)
            f.controller.resumeAfterMicrophone()
            f.controller.close()
            assertFalse(f.controller.suspendForHandover())
        }
    }
}
