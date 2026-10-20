package me.kavishdevar.librepods.utils

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AssistantLauncherTest {
    @Test fun voiceCommandTargetsTheConfiguredAssistantInsteadOfOpeningAnUnrelatedChooser() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, "assistant", "com.example.assistant/.VoiceService")
        assertTrue(AssistantLauncher.launch(context))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VOICE_COMMAND, intent.action)
        assertEquals("com.example.assistant", intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun missingAssistantDoesNotCrashTheBackgroundService() {
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(RuntimeEnvironment.getApplication().contentResolver)
        doThrow(ActivityNotFoundException()).`when`(context).startActivity(any(Intent::class.java))
        assertFalse(AssistantLauncher.launch(context))
    }
}
