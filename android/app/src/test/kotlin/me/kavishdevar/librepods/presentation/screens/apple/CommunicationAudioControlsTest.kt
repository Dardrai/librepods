package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.CommunicationAudioController.Phase
import me.kavishdevar.librepods.audio.CommunicationAudioController.State
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CommunicationAudioControlsTest {
    @Test fun waitingAndStartingAreNotClaimedAsAnActiveMicrophone() {
        assertEquals(R.string.communication_audio_description, communicationAudioDescription(State()))
        assertEquals(R.string.communication_audio_waiting,
            communicationAudioDescription(State(enabled = true, phase = Phase.WAITING)))
        assertEquals(R.string.communication_audio_starting,
            communicationAudioDescription(State(enabled = true, phase = Phase.STARTING)))
    }

    @Test fun muteAndRestorationKeepTheirActualStateVisible() {
        val active = State(enabled = true, phase = Phase.ACTIVE)
        assertEquals(R.string.communication_audio_active, communicationAudioDescription(active))
        assertEquals(R.string.communication_audio_muted, communicationAudioDescription(active.copy(muted = true)))
        assertEquals(R.string.communication_audio_stopping, communicationAudioDescription(active.copy(phase = Phase.STOPPING)))
        assertEquals(R.string.communication_audio_failed, communicationAudioDescription(active.copy(phase = Phase.FAILED)))
    }
}
