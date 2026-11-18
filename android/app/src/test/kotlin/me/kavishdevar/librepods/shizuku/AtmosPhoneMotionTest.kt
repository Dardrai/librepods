/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosPhoneMotionTest {
    private class Fixture {
        val context = mock(Context::class.java)
        val sensors = mock(SensorManager::class.java)
        val displays = mock(DisplayManager::class.java)
        val display = mock(Display::class.java)
        val sensor = mock(Sensor::class.java)
        val ready = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        lateinit var listener: SensorEventListener
        lateinit var displayListener: DisplayManager.DisplayListener
        val offers = mutableListOf<List<Any>>()
        val failures = mutableListOf<Throwable>()
        var live = true
        var accept = true
        init {
            `when`(context.getSystemService(SensorManager::class.java)).thenReturn(sensors)
            `when`(context.getSystemService(DisplayManager::class.java)).thenReturn(displays)
            `when`(displays.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(display)
            `when`(sensor.type).thenReturn(Sensor.TYPE_ROTATION_VECTOR)
            `when`(sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)).thenReturn(sensor)
            doAnswer { displayListener = it.getArgument(0); null }.`when`(displays)
                .registerDisplayListener(any(DisplayManager.DisplayListener::class.java), any(Handler::class.java))
            `when`(sensors.registerListener(any(SensorEventListener::class.java), eq(sensor), eq(10_000), any(Handler::class.java)))
                .thenAnswer { listener = it.getArgument(0); ready.countDown(); true }
            doAnswer { removed.countDown(); null }.`when`(sensors).unregisterListener(any(SensorEventListener::class.java))
        }
        fun collector(handle: Long = 71) = AtmosPhoneMotion(context, handle, { live }, { h, t, q, r ->
            offers += listOf(h, t, q.toList(), r); accept
        }, { failures += it; failed.countDown() })
        fun event(time: Long = 123456789L, values: FloatArray = floatArrayOf(0f, 0f, 0.6f, 0.8f)): SensorEvent {
            val event = ReflectionHelpers.callConstructor(SensorEvent::class.java, ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, values.size))
            event.sensor = sensor; event.timestamp = time
            values.copyInto(event.values)
            return event
        }
        fun await(latch: CountDownLatch) { assertTrue("callback timed out", latch.await(3, TimeUnit.SECONDS)) }
    }

    @Test fun originalClockQuaternionAndCachedDisplayRotationReachCapturedHandle() {
        val f = Fixture(); val collector = f.collector()
        try {
            collector.start(); f.await(f.ready)
            f.listener.onSensorChanged(f.event())
            assertEquals(listOf(71L, 123456789L, listOf(0f, 0f, 0.6f, 0.8f), 0), f.offers.single())
            `when`(f.display.rotation).thenReturn(3)
            f.displayListener.onDisplayChanged(Display.DEFAULT_DISPLAY)
            clearInvocations(f.displays)
            f.listener.onSensorChanged(f.event(123456790L, floatArrayOf(0f, 0f, 0f)))
            assertEquals(listOf(71L, 123456790L, listOf(0f, 0f, 0f, 1f), 3), f.offers.last())
            verifyNoInteractions(f.displays) // No display service lookup on the sensor path.
            assertEquals(2L, collector.samples); assertEquals(123456790L, collector.lastTimestampNs)
        } finally { collector.close(); f.await(f.removed) }
    }

    @Test fun retiredProducerCannotFeedReplacementAndUnregistersOnce() {
        val f = Fixture(); val old = f.collector(71)
        old.start(); f.await(f.ready)
        val late = f.listener
        old.close(); old.close(); f.await(f.removed)
        late.onSensorChanged(f.event())
        assertTrue(f.offers.isEmpty()); assertTrue(f.failures.isEmpty())
        verify(f.sensors, times(1)).unregisterListener(late)
        val next = Fixture(); val replacement = next.collector(72)
        try {
            replacement.start(); next.await(next.ready)
            late.onSensorChanged(f.event())
            next.listener.onSensorChanged(next.event())
            assertTrue(f.offers.isEmpty()); assertEquals(72L, next.offers.single()[0])
        } finally { replacement.close(); next.await(next.removed) }
    }

    @Test fun localOwnerLossSkipsNativeAndRejectionRetiresCollector() {
        val f = Fixture(); val collector = f.collector()
        collector.start(); f.await(f.ready)
        f.live = false; f.listener.onSensorChanged(f.event())
        assertTrue(f.offers.isEmpty())
        f.live = true; f.accept = false
        f.listener.onSensorChanged(f.event()); f.await(f.removed)
        assertEquals("phone_pose_rejected", f.failures.single().message)
        f.listener.onSensorChanged(f.event())
        assertEquals(1, f.offers.size); assertEquals(0L, collector.samples)
    }

    @Test fun closeBeforeStartDoesNotRegisterOrPublish() {
        val f = Fixture(); val collector = f.collector()
        collector.close(); collector.start()
        val worker = ReflectionHelpers.getField<HandlerThread>(collector, "callbacks")
        worker.join(3000); assertFalse(worker.isAlive)
        verify(f.sensors, never()).registerListener(any(SensorEventListener::class.java), any(Sensor::class.java), anyInt(), any(Handler::class.java))
        assertTrue(f.offers.isEmpty())
    }

    @Test fun unavailableRotationSensorFailsClosedAndReleasesDisplayListener() {
        val f = Fixture()
        `when`(f.sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)).thenReturn(null)
        val collector = f.collector()
        collector.start(); f.await(f.failed)
        val worker = ReflectionHelpers.getField<HandlerThread>(collector, "callbacks")
        worker.join(3000); assertFalse(worker.isAlive)
        assertEquals("phone_sensor_unavailable", f.failures.single().message)
        verify(f.displays).unregisterDisplayListener(f.displayListener)
        assertFalse(collector.registered); assertTrue(f.offers.isEmpty())
    }
}
