/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean

/** Phone input only: the captured native lease owns pose policy and all source/decoder authority. */
internal class AtmosPhoneMotion(private val context: Context, private val handle: Long,
    private val current: () -> Boolean,
    private val offer: (Long, Long, FloatArray, Int) -> Boolean,
    private val failed: (Throwable) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val callbacks = HandlerThread("atmos-phone-motion").apply { start() }
    private val handler = Handler(callbacks.looper)
    private var sensors: SensorManager? = null
    private var displays: DisplayManager? = null
    private var rotation = 0
    @Volatile var registered = false
        private set
    // Delivery diagnostics only; native may ignore stale events, so these are not freshness proof.
    @Volatile var samples = 0L
        private set
    @Volatile var lastTimestampNs = 0L
        private set

    private fun live() = !closed.get() && current()
    private fun guarded(action: () -> Unit) {
        if (!live()) return
        try { action() }
        catch (error: Exception) { fail(error) }
        catch (error: LinkageError) { fail(error) }
    }
    private fun fail(error: Throwable) {
        if (live()) { close(); failed(error) }
    }
    private fun updateRotation() {
        rotation = checkNotNull(displays?.getDisplay(Display.DEFAULT_DISPLAY)) { "phone_display_unavailable" }.rotation
        check(rotation in 0..3) { "phone_display_rotation" }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) {
            if (id == Display.DEFAULT_DISPLAY) guarded { error("phone_display_removed") }
        }
        override fun onDisplayChanged(id: Int) {
            if (id == Display.DEFAULT_DISPLAY) guarded { updateRotation() }
        }
    }
    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) = guarded {
            check(event.sensor.type == Sensor.TYPE_ROTATION_VECTOR && event.values.size >= 3) { "phone_sensor_event" }
            // SensorManager returns w/x/y/z; JNI deliberately takes Android rotation-vector x/y/z/w.
            val q = FloatArray(4)
            SensorManager.getQuaternionFromVector(q, event.values)
            val xyzw = floatArrayOf(q[1], q[2], q[3], q[0])
            check(offer(handle, event.timestamp, xyzw, rotation)) { "phone_pose_rejected" }
            samples++; lastTimestampNs = event.timestamp
        }
    }

    fun start() {
        check(started.compareAndSet(false, true)) { "phone_motion_already_started" }
        handler.post { guarded {
            displays = checkNotNull(context.getSystemService(DisplayManager::class.java)) { "phone_display_service" }
            sensors = checkNotNull(context.getSystemService(SensorManager::class.java)) { "phone_sensor_service" }
            updateRotation()
            displays!!.registerDisplayListener(displayListener, handler)
            val sensor = checkNotNull(sensors!!.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)) { "phone_sensor_unavailable" }
            check(sensors!!.registerListener(sensorListener, sensor, 10_000, handler)) { "phone_sensor_registration" }
            registered = true
        } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Invalidate immediately; platform unregister may use Binder, so keep it off lease/service locks.
        handler.post {
            runCatching { sensors?.unregisterListener(sensorListener) }
            runCatching { displays?.unregisterDisplayListener(displayListener) }
            registered = false
            callbacks.quitSafely()
        }
    }
}
