package me.kavishdevar.librepods.presentation.activities

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.database.widget.WidgetConfigEntity
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.design.NightTheme
import me.kavishdevar.librepods.services.LibrePodsService

class NoiseControlWidgetConfigurationActivity: ComponentActivity() {
    private var service by mutableStateOf<LibrePodsService?>(null)
    private var bound = false

    private val serviceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as LibrePodsService.LocalBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    val appDataRepository by lazy { (application as LibrePodsApplication).appDataRepository }
    val widgetConfigRepository by lazy { (application as LibrePodsApplication).widgetConfigRepository }

    override fun onStart() {
        Intent(this, LibrePodsService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        super.onStart()
    }

    override fun onStop() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            val settings by appDataRepository.settings.collectAsState()

            val darkTheme = when (settings.nightMode) {
                NightTheme.Dark -> true
                NightTheme.Light -> false
                NightTheme.System -> isSystemInDarkTheme()
            }

            LibrePodsTheme(
                designSystem = settings.designSystem,
                darkTheme = darkTheme
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    service?.let {
                        val devices by it.devices.collectAsState()

                        val widgetConfigs by widgetConfigRepository.widgetConfigs.collectAsState()

                        val widgetConfig = widgetConfigs.find { config -> config.appWidgetId == appWidgetId }
                        var selectedAddress by rememberSaveable { mutableStateOf<String?>(null) }
                        var saving by remember { mutableStateOf(false) }
                        val pickedDevice = selectedAddress?.let(::MacAddress) ?: widgetConfig?.macAddress

                        WidgetDevicePickerContent(
                            devices = devices,
                            pickedDevice = pickedDevice,
                            saving = saving,
                            onDevicePicked = { macAddress ->
                                if (!saving) selectedAddress = macAddress.value
                            },
                            onDoneClicked = {
                                if (pickedDevice != null && !saving) {
                                    saving = true
                                    lifecycleScope.launch {
                                        try {
                                            widgetConfigRepository.setWidgetConfig(WidgetConfigEntity(appWidgetId, pickedDevice))
                                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                            finish()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            Log.w("WidgetConfiguration", "Could not save widget target", failure)
                                            Toast.makeText(this@NoiseControlWidgetConfigurationActivity,
                                                "Could not save the widget. Please try again.", Toast.LENGTH_LONG).show()
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }
                            },
                            onCancelClicked = {
                                finish()
                            }
                        )
                    } ?: run {
                        Text(
                            text = "Service not available. Please ensure LibrePods is running and try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetDevicePickerContent(
    devices: Map<MacAddress, Device<*, *, *>>,
    pickedDevice: MacAddress?,
    saving: Boolean,
    onDevicePicked: (MacAddress) -> Unit,
    onDoneClicked: () -> Unit,
    onCancelClicked: () -> Unit
) {
    StyledScaffold(
        title = stringResource(R.string.configure_widget),
        navigateBack = null
    ) { topPadding, bottomPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.size(topPadding))
            if (devices.isEmpty()) {
                Text(
                    text = "No devices found. Please ensure a compatible device is paired with your phone and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                StyledButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancelClicked
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                StyledList(title = stringResource(R.string.devices)) {
                    devices.forEach { device ->
                        val metadata by device.value.metadata.collectAsState()

                        styledListItem(
                            contentText = metadata.name,
                            supportingText = device.key.value,
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(
                                            if (pickedDevice == device.key) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.secondaryContainer,
                                            MaterialShapes.Circle.normalized().toShape()
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = LocalIcons.current.fromName(metadata.iconName) ?: LocalIcons.current.Headphones,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = if (pickedDevice == device.key) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            },
                            onClick = {
                                onDevicePicked(device.key)
                            },
                            selected = pickedDevice == device.key
                        )
                    }
                }

                StyledButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDoneClicked,
                    enabled = pickedDevice != null && devices.containsKey(pickedDevice) && !saving
                ) {
                    Text(
                        text = "Done",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.size(bottomPadding))
        }
    }
}
