package me.kavishdevar.librepods.presentation.navigation

import android.app.Application
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import me.kavishdevar.librepods.presentation.components.primitives.StyledIconButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledSwitch
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SwipeBackNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val requests = Channel<CompletableDeferred<Unit>>(Channel.BUFFERED)
    private lateinit var dispatcher: OnBackPressedDispatcher
    private lateinit var rootView: View
    private var fallbackBacks = 0
    private var actionClicks = 0
    private var gestureTouchSlop = 0f
    private val toggleChecked = mutableStateOf(false)
    private val sliderValue = mutableFloatStateOf(0f)

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

    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun forwardSlideKeepsItsProgressAndWholeHeaderWhenEntryContentChanges() {
        val stack = NavBackStack<Screen>(Screen.DeviceList)
        val revision = mutableStateOf(0)
        showNavigation(stack, entryRevision = revision, withHeaders = true)
        compose.mainClock.autoAdvance = false

        mutate { stack.add(Screen.AppSettings) }
        advance(64)
        val width = compose.onNodeWithTag("navigation-host").fetchSemanticsNode().size.width.toFloat()
        val before = pageX(Screen.AppSettings)
        assertTrue("The incoming page is still sliding", before > 0f && before < width)
        val headerOffsets = headerOffsets(Screen.AppSettings)
        assertParentParallax(width, before)

        mutate { revision.value++ }
        compose.onNodeWithTag(contentTag(Screen.AppSettings)).assertTextEquals("Content 1")
        val after = pageX(Screen.AppSettings)
        assertTrue("Replacing the entry lambda must not restart the slide", after > 0f && after < before)
        assertHeaderOffsets(Screen.AppSettings, headerOffsets)
        assertParentParallax(width, after)

        advance(48)
        assertTrue("The same animation keeps moving forward", pageX(Screen.AppSettings) < after)
        assertHeaderOffsets(Screen.AppSettings, headerOffsets)
        advance(300)
        assertDestinationFillsHost(Screen.AppSettings)
        assertHeaderOffsets(Screen.AppSettings, headerOffsets)
        backButton(Screen.AppSettings).assertIsDisplayed()
        compose.onNodeWithTag(actionTag(Screen.AppSettings)).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun depthThreeHeaderAndSystemBackEachPopOnlyOneDestination() {
        val stack = NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings, Screen.BLESettings)
        showNavigation(stack, withHeaders = true)

        backButton(Screen.BLESettings).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(Screen.DeviceList, Screen.AppSettings), stack.toList())
            assertEquals(0, fallbackBacks)
        }
        assertDestinationFillsHost(Screen.AppSettings)

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(Screen.DeviceList), stack.toList())
            assertEquals(0, fallbackBacks)
        }
        assertDestinationFillsHost(Screen.DeviceList)
    }

    @Test fun interruptedBackRequestCompletesAndTheReceiverHandlesTheNextRequest() {
        val stack = NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings)
        showNavigation(stack, swipeEnabled = true)
        compose.mainClock.autoAdvance = false
        val interrupted = CompletableDeferred<Unit>()
        compose.runOnIdle { assertTrue(requests.trySend(interrupted).isSuccess) }
        advance(32)
        assertTrue("Back animation started", pageX(Screen.AppSettings) > 0f)

        compose.onNodeWithTag("navigation-host").performTouchInput {
            down(Offset(8f, height * 0.75f))
            moveBy(Offset(80f, 0f))
        }
        advance(16)
        compose.runOnIdle {
            assertTrue("An interrupted animation must acknowledge its request", interrupted.isCompleted)
            assertEquals(listOf(Screen.DeviceList, Screen.AppSettings), stack.toList())
        }

        val next = CompletableDeferred<Unit>()
        compose.runOnIdle { assertTrue(requests.trySend(next).isSuccess) }
        advance(240)
        compose.runOnIdle {
            assertTrue("The receiver must survive the interruption", next.isCompleted)
            assertEquals(listOf(Screen.DeviceList), stack.toList())
        }
        compose.onNodeWithTag("navigation-host").performTouchInput { up() }
        assertDestinationFillsHost(Screen.DeviceList)
    }

    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun headerButtonsAcceptRealTouchesAcrossTheir48dpTargetsBelowTheStatusInset() {
        val stack = NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings)
        showNavigation(stack, swipeEnabled = true, withHeaders = true)
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(rootView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 24)).build())
        }
        compose.waitForIdle()
        val back = backButton(Screen.AppSettings)
        val bounds = back.fetchSemanticsNode().boundsInRoot
        assertEquals(48f, bounds.width, .5f)
        assertEquals(48f, bounds.height, .5f)
        assertTrue("Back clears the status inset", bounds.top >= 32f)
        back.performTouchInput { down(center); moveBy(Offset(1f, 1f)); up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Screen.DeviceList), stack.toList()) }
        mutate { stack.add(Screen.AppSettings) }
        compose.waitForIdle()
        val cornerBack = backButton(Screen.AppSettings)
        cornerBack.performTouchInput { down(Offset(4f, 4f)); moveBy(Offset(1f, 1f)); up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Screen.DeviceList), stack.toList()) }
        compose.onNodeWithTag(actionTag(Screen.DeviceList)).performTouchInput {
            down(Offset(4f, 4f)); moveBy(Offset(1f, 1f)); up()
        }
        compose.runOnIdle { assertEquals(1, actionClicks) }
    }

    @Test fun onlyARightwardSwipeThatStartsAtTheLeftEdgeNavigatesBack() {
        val stack = NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings)
        showNavigation(stack, swipeEnabled = true)
        val host = compose.onNodeWithTag("navigation-host")
        host.performTouchInput { swipe(Offset(60f, height * .75f), Offset(330f, height * .75f), 300) }
        assertDestinationFillsHost(Screen.AppSettings)
        host.performTouchInput { swipe(Offset(8f, height * .75f), Offset(260f, height * .75f), 300) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Screen.DeviceList), stack.toList()) }
        assertDestinationFillsHost(Screen.DeviceList)
    }

    @Test fun verticalDiagonalAndWrongWayEdgeGesturesCannotBecomeBackSwipes() {
        val stack = NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings)
        showNavigation(stack, swipeEnabled = true)
        val host = compose.onNodeWithTag("navigation-host")
        host.performTouchInput { swipe(Offset(8f, 200f), Offset(9f, 500f), 300) }
        compose.runOnIdle { assertEquals("Vertical", listOf(Screen.DeviceList, Screen.AppSettings), stack.toList()) }
        host.performTouchInput { swipe(Offset(8f, 200f), Offset(250f, 450f), 300) }
        compose.runOnIdle { assertEquals("Diagonal", listOf(Screen.DeviceList, Screen.AppSettings), stack.toList()) }
        host.performTouchInput {
            down(Offset(20f, 400f)); moveBy(Offset(-gestureTouchSlop - 8f, 0f)); moveBy(Offset(260f, 0f)); up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Screen.DeviceList, Screen.AppSettings), stack.toList()) }
        assertDestinationFillsHost(Screen.AppSettings)
    }

    @Test fun cancellingAnEdgeDragReturnsThePageWithoutNavigating() {
        val stack = NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings)
        showNavigation(stack, swipeEnabled = true)
        compose.onNodeWithTag("navigation-host").performTouchInput {
            down(Offset(8f, height * .75f)); moveBy(Offset(240f, 0f)); cancel()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Screen.DeviceList, Screen.AppSettings), stack.toList()) }
        assertDestinationFillsHost(Screen.AppSettings)
    }

    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun switchAndSliderDragsKeepTheirValuesAndNeverMoveThePage() {
        val stack = NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings)
        showNavigation(stack, swipeEnabled = true, withControls = true)
        compose.onNodeWithTag("styled-switch-thumb", useUnmergedTree = true).performTouchInput {
            swipe(Offset(2f, center.y), Offset(152f, center.y), 300)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(toggleChecked.value) }
        assertDestinationFillsHost(Screen.AppSettings)
        compose.onNodeWithTag("styled-slider-thumb", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(100f, 0f), 300)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(sliderValue.floatValue > .2f)
            assertEquals(listOf(Screen.DeviceList, Screen.AppSettings), stack.toList())
        }
        assertDestinationFillsHost(Screen.AppSettings)
    }

    @Test fun staleSceneCannotPopADestinationAddedDuringItsBackAnimation() {
        val stack = NavBackStack<Screen>(Screen.DeviceList, Screen.AppSettings)
        var dismissals = 0
        val strategy = SwipeBackSceneStrategy<Screen>(stack, false, requests) {
            dismissals++
            stack.removeAt(stack.lastIndex)
        }
        val entries = stack.map { screen ->
            NavEntry(screen) { Box(Modifier.fillMaxSize().testTag(destinationTag(screen))) }
        }
        val staleScene = with(strategy) {
            checkNotNull(SceneStrategyScope<Screen>().calculateScene(entries))
        }
        // Keep this scene alive so disposal cannot hide a stale dismiss callback.
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("navigation-host")) { staleScene.content() }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val completed = CompletableDeferred<Unit>()
        compose.runOnIdle { assertTrue(requests.trySend(completed).isSuccess) }
        advance(32)
        mutate { stack.add(Screen.BLESettings) }
        advance(240)

        compose.runOnIdle {
            assertTrue(completed.isCompleted)
            assertEquals(0, dismissals)
            assertEquals(listOf(Screen.DeviceList, Screen.AppSettings, Screen.BLESettings), stack.toList())
        }
    }

    private fun showNavigation(
        stack: NavBackStack<Screen>,
        swipeEnabled: Boolean = false,
        entryRevision: State<Int>? = null,
        withHeaders: Boolean = false,
        withControls: Boolean = false,
    ) {
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                val backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
                    .onBackPressedDispatcher
                val view = LocalView.current
                val touchSlop = LocalViewConfiguration.current.touchSlop
                SideEffect { dispatcher = backDispatcher; rootView = view.rootView; gestureTouchSlop = touchSlop }
                BackHandler { fallbackBacks++ }
                val pop = {
                    if (stack.size > 1) stack.removeAt(stack.lastIndex)
                    Unit
                }
                val strategy = remember {
                    SwipeBackSceneStrategy<Screen>(
                        backStack = stack,
                        enabled = swipeEnabled,
                        backRequests = requests,
                        onDismiss = pop,
                    )
                }
                val revision = entryRevision?.value ?: 0
                val entryProvider: (Screen) -> NavEntry<Screen> = { screen ->
                    NavEntry(screen) {
                        Box(Modifier.fillMaxSize().testTag(destinationTag(screen))) {
                            if (withHeaders) {
                                StyledScaffold(
                                    title = title(screen),
                                    navigateBack = if (screen == stack.first()) null else {
                                        { requests.trySend(CompletableDeferred<Unit>()); Unit }
                                    },
                                    actionButtons = listOf({ backdrop ->
                                        StyledIconButton(
                                            modifier = Modifier.testTag(actionTag(screen)),
                                            backdrop = backdrop,
                                            onClick = { actionClicks++ },
                                        ) { Text("A") }
                                    }),
                                ) { _, _ ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Content $revision", Modifier.testTag(contentTag(screen)))
                                    }
                                }
                            } else if (withControls && screen == stack.last()) {
                                Column(Modifier.fillMaxSize().padding(16.dp),
                                    verticalArrangement = Arrangement.Center) {
                                    StyledSwitch(toggleChecked.value, { toggleChecked.value = it })
                                    Box(Modifier.width(280.dp)) {
                                        StyledSlider(value = sliderValue.floatValue, valueRange = -1f..1f,
                                            onValueChange = { sliderValue.floatValue = it })
                                    }
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxSize().testTag("navigation-host")) {
                    if (entryRevision == null) {
                        NavDisplay(
                            backStack = stack,
                            sceneStrategies = listOf(strategy),
                            onBack = pop,
                            entryProvider = entryProvider,
                            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                        )
                    } else {
                        // The provider overload caches entries by stack keys, so replace entries explicitly.
                        NavDisplay(
                            entries = stack.map(entryProvider),
                            sceneStrategies = listOf(strategy),
                            onBack = pop,
                            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun mutate(change: () -> Unit) {
        compose.runOnIdle { change(); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun pageX(screen: Screen) = compose.onNodeWithTag(destinationTag(screen))
        .fetchSemanticsNode().positionInRoot.x

    private fun backButton(screen: Screen): SemanticsNodeInteraction = compose.onNode(
        hasContentDescription("Back") and hasAnyAncestor(hasTestTag(destinationTag(screen)))
    )

    private fun headerOffsets(screen: Screen): List<Float> {
        val page = pageX(screen)
        return listOf(
            compose.onNodeWithText(title(screen)), backButton(screen), compose.onNodeWithTag(actionTag(screen)),
        ).map { it.fetchSemanticsNode().positionInRoot.x - page }
    }

    private fun assertHeaderOffsets(screen: Screen, expected: List<Float>) {
        headerOffsets(screen).zip(expected).forEach { (actual, offset) ->
            assertEquals("Title, back and action move with their page", offset, actual, 0.5f)
        }
    }

    private fun assertParentParallax(width: Float, currentX: Float) {
        assertEquals("Parent moves continuously with the incoming page", -(width - currentX) / 3f,
            pageX(Screen.DeviceList), 0.5f)
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
    private fun actionTag(screen: Screen) = "action-$screen"
    private fun contentTag(screen: Screen) = "content-$screen"
    private fun title(screen: Screen) = "Title $screen"
}
