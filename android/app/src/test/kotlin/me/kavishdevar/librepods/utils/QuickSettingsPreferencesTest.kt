/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class QuickSettingsPreferencesTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("local-ui", Context.MODE_PRIVATE)

    @Before fun reset() { preferences.edit().clear().commit() }

    @Test fun absentAndEmptyPreferencesHideNothing() {
        assertTrue(QuickSettingsPreferences.hidden(context).isEmpty())
        preferences.edit().putStringSet(QuickSettingsPreferences.PREFERENCE_KEY, emptySet()).commit()
        assertTrue(QuickSettingsPreferences.hidden(context).isEmpty())
    }

    @Test fun rapidEditsSurviveReopeningWithoutDroppingUnknownOrUnrelatedChoices() {
        preferences.edit().putStringSet(QuickSettingsPreferences.PREFERENCE_KEY, setOf("future-control"))
            .putBoolean("show_pc_controls", true).putInt("unrelated", 7).commit()
        repeat(8) {
            QuickSettingsPreferences.setHidden(context, "GAME_MODE", true)
            QuickSettingsPreferences.setHidden(context, "HEARING_AID", true)
            QuickSettingsPreferences.setHidden(context, "GAME_MODE", false)
        }
        QuickSettingsPreferences.setHidden(context, "GAME_MODE", true)
        val reopened = context.createConfigurationContext(context.resources.configuration)
        assertEquals(setOf("future-control", "GAME_MODE", "HEARING_AID"), QuickSettingsPreferences.hidden(reopened))
        QuickSettingsPreferences.setHidden(reopened, "HEARING_AID", false)
        assertEquals(setOf("future-control", "GAME_MODE"), QuickSettingsPreferences.hidden(context))
        assertTrue(preferences.getBoolean("show_pc_controls", false))
        assertEquals(7, preferences.getInt("unrelated", 0))
    }

    @Test fun snapshotsAreImmutableAndDetachedFromLaterWrites() {
        QuickSettingsPreferences.setHidden(context, "GAME_MODE", true)
        val snapshot = QuickSettingsPreferences.hidden(context)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot as MutableSet<String>).add("HEARING_AID")
        }
        QuickSettingsPreferences.setHidden(context, "HEARING_AID", true)
        QuickSettingsPreferences.setHidden(context, "GAME_MODE", false)
        assertEquals(setOf("GAME_MODE"), snapshot)
        assertEquals(setOf("HEARING_AID"), QuickSettingsPreferences.hidden(context))
    }

    @Test fun compositionObservesExternalEditsAndClearThenUnregistersOnDisposal() {
        val mounted = mutableStateOf(true)
        lateinit var observed: State<Set<String>>
        compose.setContent {
            if (mounted.value) {
                observed = rememberHiddenQuickSettings()
                Text("Hidden: ${observed.value.sorted().joinToString()}")
            }
        }
        compose.onNodeWithText("Hidden: ").assertIsDisplayed()
        compose.runOnIdle {
            preferences.edit().putStringSet(QuickSettingsPreferences.PREFERENCE_KEY, setOf("GAME_MODE")).apply()
        }
        compose.onNodeWithText("Hidden: GAME_MODE").assertIsDisplayed()
        compose.runOnIdle { preferences.edit().clear().apply() }
        compose.onNodeWithText("Hidden: ").assertIsDisplayed()
        val retired = observed
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            preferences.edit().putStringSet(QuickSettingsPreferences.PREFERENCE_KEY, setOf("HEARING_AID")).apply()
        }
        compose.waitForIdle()
        assertTrue(retired.value.isEmpty())
        assertEquals(setOf("HEARING_AID"), QuickSettingsPreferences.hidden(context))
    }
}
