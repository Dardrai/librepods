package me.kavishdevar.librepods.presentation.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SwipeBackNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val requests = Channel<CompletableDeferred<Unit>>(Channel.BUFFERED)
    private lateinit var dispatcher: OnBackPressedDispatcher
    private var fallbackBacks = 0

    @After fun closeRequests() {
        requests.close()
    }

    @Test fun rootBackDelegatesWithoutSlidingTheOnlyScreenAway() {
        val stack = NavBackStack<Screen>(Screen.DeviceList)
        showNavigation(stack)

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, fallbackBacks)
            assertEquals(listOf(Screen.DeviceList), stack.toList())
        }
        assertDestinationFillsHost(Screen.DeviceList)
    }

    @Test fun systemBackPopsWithSwipeAnywhereDisabledThenDelegatesAtRoot() {
        val stack = NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings)
        showNavigation(stack)
        assertDestinationFillsHost(Screen.AppSettings)

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(0, fallbackBacks)
            assertEquals(listOf(Screen.DeviceList), stack.toList())
        }
        assertDestinationFillsHost(Screen.DeviceList)

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(1, fallbackBacks) }
        assertDestinationFillsHost(Screen.DeviceList)
    }

    @Test fun bufferedBackRequestsAtRootCompleteWithoutMovingTheScreen() {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        assertTrue(requests.trySend(first).isSuccess)
        assertTrue(requests.trySend(second).isSuccess)
        val stack = NavBackStack<Screen>(Screen.DeviceList)

        showNavigation(stack)

        compose.runOnIdle {
            assertTrue(first.isCompleted)
            assertTrue(second.isCompleted)
            assertEquals(0, fallbackBacks)
            assertEquals(listOf(Screen.DeviceList), stack.toList())
        }
        assertDestinationFillsHost(Screen.DeviceList)
    }

    private fun showNavigation(stack: NavBackStack<Screen>) {
        compose.setContent {
            val backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
                .onBackPressedDispatcher
            SideEffect { dispatcher = backDispatcher }
            BackHandler { fallbackBacks++ }
            val pop = {
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
                Unit
            }
            val strategy = remember {
                SwipeBackSceneStrategy<Screen>(
                    enabled = false,
                    backRequests = requests,
                    onDismiss = pop,
                )
            }
            Box(Modifier.fillMaxSize().testTag("navigation-host")) {
                NavDisplay(
                    backStack = stack,
                    sceneStrategies = listOf(strategy),
                    onBack = pop,
                    entryProvider = { screen ->
                        NavEntry(screen) {
                            Box(Modifier.fillMaxSize().testTag(destinationTag(screen)))
                        }
                    },
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertDestinationFillsHost(screen: Screen) {
        val host = compose.onNodeWithTag("navigation-host").fetchSemanticsNode().boundsInRoot
        val destination = compose.onNodeWithTag(destinationTag(screen))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(host.left, destination.left, 0.5f)
        assertEquals(host.top, destination.top, 0.5f)
        assertEquals(host.right, destination.right, 0.5f)
        assertEquals(host.bottom, destination.bottom, 0.5f)
    }

    private fun destinationTag(screen: Screen) = "destination-$screen"
}
