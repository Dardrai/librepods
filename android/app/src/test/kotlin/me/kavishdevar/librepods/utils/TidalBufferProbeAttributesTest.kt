/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import android.media.AudioAttributes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class TidalBufferProbeAttributesTest {
    private fun original() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setFlags(AudioAttributes.FLAG_HW_AV_SYNC)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).setHapticChannelsMuted(true)
        .setIsContentSpatialized(false).setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO).build()

    @Test fun exactProviderGetsACopyWithBothMarkersAndOtherAttributesPreserved() {
        val provider = Any(); val original = original()
        val marked = tidalBinauralAttributes(original, provider, provider, false)
        assertNotSame(original, marked)
        assertTrue(marked.isContentSpatialized)
        assertEquals(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER, marked.spatializationBehavior)
        assertEquals(original.usage, marked.usage)
        assertEquals(original.contentType, marked.contentType)
        assertEquals(original.flags, marked.flags and original.flags)
        assertEquals(original.allowedCapturePolicy, marked.allowedCapturePolicy)
        assertEquals(original.areHapticChannelsMuted(), marked.areHapticChannelsMuted())
        assertFalse(original.isContentSpatialized)
        assertEquals(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO, original.spatializationBehavior)
        assertSame(marked, tidalBinauralAttributes(marked, provider, provider, false))
    }

    @Test fun ForeignNullAndTunneledProvidersRemainExactlyUnchanged() {
        val provider = Any(); val original = original()
        assertSame(original, tidalBinauralAttributes(original, null, provider, false))
        assertSame(original, tidalBinauralAttributes(original, null, null, false))
        assertSame(original, tidalBinauralAttributes(original, provider, Any(), false))
        assertSame(original, tidalBinauralAttributes(original, provider, provider, true))
    }

    @Test fun NestedInitializationCannotLeakTheOwnedProviderScopeAfterReturnOrFailure() {
        val scope = TidalBufferProbeScope(); val provider = Any(); val original = original()
        scope.within(provider) {
            assertTrue(tidalBinauralAttributes(original, scope.renderer(), provider, false).isContentSpatialized)
            assertThrows(IllegalStateException::class.java) {
                scope.within(null) {
                    assertSame(original, tidalBinauralAttributes(original, scope.renderer(), provider, false))
                    error("original initialization failure")
                }
            }
            assertSame(provider, scope.renderer())
        }
        assertNull(scope.renderer())
        assertSame(original, tidalBinauralAttributes(original, scope.renderer(), provider, false))
    }

    @Test fun ReconfigurationAndReplacementInvalidateTheBoundedOwnershipWitness() {
        val witness = TidalBinauralTrackOwner()
        val firstSink = Any(); val firstRenderer = Any(); val firstCodec = Any()
        val secondSink = Any(); val secondRenderer = Any(); val secondCodec = Any()
        assertNull(witness.get(firstSink))
        witness.remember(firstSink, firstRenderer, firstCodec)
        assertSame(firstRenderer, witness.get(firstSink)!!.renderer)
        assertSame(firstCodec, witness.get(firstSink)!!.codec)
        assertNull(witness.get(secondSink))
        witness.forget(secondSink)
        assertNotNull(witness.get(firstSink))
        witness.forget(firstSink) // Every configure clears first, including unsupported/throwing configuration.
        assertNull(witness.get(firstSink))
        witness.remember(firstSink, firstRenderer, firstCodec)
        witness.remember(secondSink, secondRenderer, secondCodec)
        assertNull(witness.get(firstSink))
        assertSame(secondRenderer, witness.get(secondSink)!!.renderer)
        assertSame(secondCodec, witness.get(secondSink)!!.codec)
    }
}
