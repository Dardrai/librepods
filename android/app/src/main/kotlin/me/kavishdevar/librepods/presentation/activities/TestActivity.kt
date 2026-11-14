/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.activities

import android.os.Bundle
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.audio.SpatialMotionCapture
import me.kavishdevar.librepods.audio.SpatialMotionCaptureRequest
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.services.LibrePodsService
import java.io.File

class TestActivity: ComponentActivity() {
    private var captureRequest: SpatialMotionCaptureRequest? = null
    private var captureJob: Job? = null
    private var attempted = false
    private var bound = false
    private var statusView: TextView? = null
    @Volatile private var capturePath = ""
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            val service = (binder as? LibrePodsService.LocalBinder)?.getService() ?: return
            val request = captureRequest ?: return
            val device = service.devices.value.values.filterIsInstance<AppleDevice>().singleOrNull {
                it.macAddress.value.equals(request.expectedMac, ignoreCase = true) && it.connectionState.value == ConnectionState.CONNECTED
            }
            val socket = device?.aacp?.captureAudioSocket()
            if (device == null || socket == null) {
                showStatus("The expected AirPods are not connected. No capture started.")
                return
            }
            fun current(): Boolean {
                val connected = service.devices.value.values.filterIsInstance<AppleDevice>()
                    .filter { it.connectionState.value == ConnectionState.CONNECTED }
                val inCase = listOf(DeviceComponent.LEFT, DeviceComponent.RIGHT).all { side ->
                    device.state.value.componentState.any { it.component == side && it.status == ComponentStatus.IN_CASE }
                }
                val app = application as? LibrePodsApplication ?: return false
                val audioManager = getSystemService(AudioManager::class.java)
                val audioIdle = audioManager?.mode == AudioManager.MODE_NORMAL && audioManager.activeRecordingConfigurations.isEmpty() &&
                    app.experimentalAudioControllers.values.all { it.microphone.state.value.phase in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR) } &&
                    app.communicationAudioControllers.values.none { it.ownsSession || it.hasPreparedRoute }
                return connected.size == 1 && connected.single() === device && device.aacp.ownsAudioSocket(socket) && !inCase && audioIdle
            }
            captureJob = lifecycleScope.launch {
                showStatus("Capture phase: ${request.phase}\nKeep this screen open for ${request.durationSeconds} seconds.")
                val result = SpatialMotionCapture(File(filesDir, "spatial-motion-captures")).capture(request, device, ::current) { file ->
                    capturePath = file.absolutePath
                    showStatus("Capturing ${request.phase} for ${request.durationSeconds} seconds.\n${file.absolutePath}")
                }
                showStatus("${request.phase}: ${result.reason}\n${result.samples} samples\n${result.file?.absolutePath.orEmpty()}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureJob?.cancel()
            showStatus("Motion capture stopped: LibrePods disconnected.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        if (!BuildConfig.DEBUG || !runCatching { intent.getBooleanExtra("spatial_motion_capture", false) }.getOrDefault(false)) {
            setContent { }
            return
        }
        statusView = TextView(this).apply { textSize = 20f; setPadding(32, 64, 32, 32) }
        setContentView(statusView)
        if (savedInstanceState != null) {
            showStatus("Capture stopped. Start a new explicit request to record another phase.")
            return
        }
        captureRequest = runCatching {
            SpatialMotionCaptureRequest(
                requireNotNull(intent.getStringExtra("expected_mac")) { "An expected MAC is required." },
                requireNotNull(intent.getStringExtra("phase")) { "A capture phase is required." },
                intent.getIntExtra("duration_seconds", 10),
            )
        }.getOrElse { showStatus("Invalid capture request: ${it.message}"); null }
    }

    override fun onStart() {
        super.onStart()
        if (captureRequest == null || attempted) return
        attempted = true
        // Bind only the already running service; a diagnostic never starts or reconnects audio.
        bound = runCatching { bindService(Intent(this, LibrePodsService::class.java), connection, 0) }.getOrDefault(false)
        if (!bound) showStatus("Start LibrePods and connect the expected AirPods first. No capture started.")
    }

    override fun onStop() {
        if (captureJob?.isActive == true) showStatus("Capture stopped when the screen closed.\n$capturePath")
        captureJob?.cancel()
        if (bound) { runCatching { unbindService(connection) }; bound = false }
        super.onStop()
    }

    private fun showStatus(message: String) {
        runOnUiThread { statusView?.text = message }
    }
}
