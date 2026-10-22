package me.kavishdevar.librepods.presentation.screens.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CompanionLinkDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun linkingCodeOptsOutOfAutofillAndDoneDismissesKeyboardWithoutLinking() {
        val keyboard = mock(SoftwareKeyboardController::class.java)
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                LibrePodsTheme {
                    var value by remember { mutableStateOf("") }
                    CompanionLinkCodeField(value, false) { value = it }
                }
            }
        }
        val code = compose.onNodeWithText("Linking code")
        code.performClick()
        code.performTextInput("a".repeat(64)) // Synthetic test value; never a provisioned linking code.
        code.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDataType, ContentDataType.None))
        code.assertIsFocused()
        code.performImeAction()
        code.assertIsNotFocused()
        verify(keyboard).hide()
    }
}
