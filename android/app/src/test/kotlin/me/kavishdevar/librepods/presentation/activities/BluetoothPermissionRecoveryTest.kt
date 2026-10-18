package me.kavishdevar.librepods.presentation.activities

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.bluetooth.nearbyDevicesPermissions
import me.kavishdevar.librepods.database.app.AppStateEntity
import me.kavishdevar.librepods.repository.AppDataRepository
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BluetoothPermissionRecoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedOnboardingWithRevokedBluetoothOpensPermissionsWithoutStartingTheService() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(*nearbyDevicesPermissions.toTypedArray())
        val repository = mock(AppDataRepository::class.java)
        `when`(repository.state).thenReturn(MutableStateFlow(AppStateEntity(hasCompletedOnboarding = true)))
        compose.setContent { Main(repository) }
        compose.onNodeWithText("Required Permissions").assertIsDisplayed()
        assertNull(shadowOf(app).nextStartedService)
    }
}
