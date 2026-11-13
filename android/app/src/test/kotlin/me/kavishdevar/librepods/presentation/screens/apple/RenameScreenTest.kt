/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
// Text editing owns a platform magnifier; use the native renderer for its lifecycle.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RenameScreenTest {
    @get:Rule val compose = createComposeRule()
    private val writes = mutableListOf<String>()
    private var navigations = 0
    private val unicodeName = "Dylan\u2019s \u00c9couteurs \uD83C\uDFA7"
    private val doneText get() = RuntimeEnvironment.getApplication().getString(R.string.rename_done)
    private fun field() = compose.onNode(hasSetTextAction())
    private fun done() = compose.onNode(hasText(doneText) and hasClickAction())

    private fun show(initialName: String = "Original AirPods", designSystem: DesignSystem = DesignSystem.Apple,
        result: suspend () -> Boolean = { true }) {
        compose.setContent {
            LibrePodsTheme(designSystem = designSystem) {
                RenameContent(initialName, navigateBack = { navigations++ }) { name ->
                    writes += name
                    result()
                }
            }
        }
    }

    @Test fun editingNeverWritesAutomaticallyAndDoneIsVisible() {
        show()
        done().assertIsDisplayed().assertIsEnabled()
        field().performTextReplacement(unicodeName)
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertTrue("Typing and the previous debounce interval must not submit a name", writes.isEmpty())
            assertEquals(0, navigations)
        }
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(unicodeName)))
        done().assertIsDisplayed().assertIsEnabled()
    }

    @Test fun explicitDoneWaitsForUnicodeRenameAndSuppressesQueuedDuplicateActions() {
        val completion = CompletableDeferred<Boolean>()
        show { completion.await() }
        field().performTextReplacement(unicodeName)
        val queuedIme = requireNotNull(field().fetchSemanticsNode().config[SemanticsActions.OnImeAction].action)
        val queuedButton = requireNotNull(done().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        done().performClick()
        compose.runOnIdle {
            assertEquals(listOf(unicodeName), writes)
            assertEquals("Navigate only after the write returns true", 0, navigations)
        }
        compose.onNodeWithText(unicodeName).assertIsNotEnabled()
        done().assertIsNotEnabled()
        compose.runOnIdle { queuedIme(); queuedButton() }
        compose.runOnIdle { assertEquals("Already queued button and keyboard actions share one admission", 1, writes.size) }
        compose.runOnIdle { completion.complete(true) }
        compose.runOnIdle { assertEquals(1, navigations); assertEquals(listOf(unicodeName), writes) }
    }

    @Test fun aRejectedRenameKeepsTheDraftAndOffersAnExplicitRetry() {
        show { writes.size > 1 }
        field().performTextReplacement(unicodeName)
        done().performClick()
        compose.runOnIdle { assertEquals(listOf(unicodeName), writes); assertEquals(0, navigations) }
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(unicodeName)))
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.rename_not_sent)).assertIsDisplayed()
        done().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(unicodeName, unicodeName), writes); assertEquals(1, navigations) }
    }

    @Test fun keyboardDoneUsesTheSameExplicitSubmissionPath() {
        show(designSystem = DesignSystem.Material)
        field().performTextReplacement(unicodeName)
        field().performImeAction()
        compose.runOnIdle { assertEquals(listOf(unicodeName), writes); assertEquals(1, navigations) }
    }

    @Test fun invalidNamesCannotSubmitAndTheUtf8ByteBoundaryIsAccepted() {
        show()
        for (invalid in listOf("", "   ", "x".repeat(65), "\u00e9".repeat(33), "Invalid\u0001name")) {
            field().performTextReplacement(invalid)
            done().assertIsNotEnabled()
            field().performImeAction()
            compose.runOnIdle { assertTrue("Invalid names cannot bypass validation through IME Done", writes.isEmpty()); assertEquals(0, navigations) }
        }
        val boundary = "\u00e9".repeat(32) // 32 characters, exactly 64 UTF-8 bytes.
        field().performTextReplacement(boundary)
        done().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(boundary), writes); assertEquals(1, navigations) }
    }

    @Test fun unchangedDoneNavigatesWithoutSendingARename() {
        show(initialName = unicodeName)
        done().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(writes.isEmpty()); assertEquals(1, navigations) }
    }
}
