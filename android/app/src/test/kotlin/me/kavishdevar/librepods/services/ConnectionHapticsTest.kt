package me.kavishdevar.librepods.services

import android.app.Application
import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ConnectionHapticsTest {
    private val context = mock(Context::class.java)
    private val manager = mock(VibratorManager::class.java)
    private val vibrator = mock(Vibrator::class.java)
    private val feedback = ConnectionHaptics(context)
    private val airpods = "AA:BB:CC:DD:EE:FF"
    private val car = "00:11:22:33:44:55"

    init {
        `when`(context.getSystemService(VibratorManager::class.java)).thenReturn(manager)
        `when`(manager.defaultVibrator).thenReturn(vibrator)
        `when`(vibrator.hasVibrator()).thenReturn(true)
    }

    @Test fun startupInventoryAndRepeatedRegistrationOrControlUpdatesStayQuiet() {
        feedback.update(setOf(airpods), emptySet())
        repeat(3) { feedback.update(setOf(airpods), setOf(airpods)) }
        verifyNoInteractions(vibrator)
    }

    @Test fun outputBeforePairingClassificationStillProducesOneGentleTouchTick() {
        feedback.update(emptySet(), emptySet())
        feedback.update(setOf(airpods.lowercase()), emptySet())
        verifyNoInteractions(vibrator)
        repeat(3) { feedback.update(setOf(airpods.lowercase()), setOf(airpods)) }
        val effect = ArgumentCaptor.forClass(VibrationEffect::class.java)
        val attributes = ArgumentCaptor.forClass(VibrationAttributes::class.java)
        verify(vibrator).vibrate(effect.capture(), attributes.capture())
        assertEquals(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK), effect.value)
        assertEquals(VibrationAttributes.USAGE_TOUCH, attributes.value.usage)
        assertEquals(0, attributes.value.flags)
    }

    @Test fun onlyActualAudioDetachRearmsConnectionFeedback() {
        feedback.update(emptySet(), setOf(airpods))
        repeat(3) { feedback.update(setOf(airpods), setOf(airpods)) }
        feedback.update(emptySet(), setOf(airpods))
        repeat(3) { feedback.update(setOf(airpods), setOf(airpods)) }
        verify(vibrator, times(2)).vibrate(any(VibrationEffect::class.java), any(VibrationAttributes::class.java))
    }

    @Test fun unrelatedOutputsAndDetachedUnclassifiedOutputsCannotVibrateLater() {
        feedback.update(emptySet(), emptySet())
        feedback.update(setOf(car), setOf(airpods))
        feedback.update(setOf(car, airpods), emptySet())
        feedback.update(setOf(car), setOf(airpods))
        verifyNoInteractions(vibrator)
    }

    @Test fun missingVibratorOrRejectedEffectsDoNotRetryDuringTheSameConnection() {
        `when`(vibrator.hasVibrator()).thenReturn(false)
        feedback.update(emptySet(), setOf(airpods))
        feedback.update(setOf(airpods), setOf(airpods))
        verify(vibrator, never()).vibrate(any(VibrationEffect::class.java), any(VibrationAttributes::class.java))
        feedback.clear()
        `when`(vibrator.hasVibrator()).thenReturn(true)
        doThrow(SecurityException("denied")).`when`(vibrator)
            .vibrate(any(VibrationEffect::class.java), any(VibrationAttributes::class.java))
        repeat(3) { feedback.update(setOf(airpods), setOf(airpods)) }
        verify(vibrator).vibrate(any(VibrationEffect::class.java), any(VibrationAttributes::class.java))
    }
}
