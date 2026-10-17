package me.kavishdevar.librepods.presentation.components.apple

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import me.kavishdevar.librepods.audio.ExperimentalAudioController

/** A permission result belongs only to the device whose action opened the prompt. */
@Composable
internal fun rememberMicrophonePermission(
    controller: ExperimentalAudioController,
    onDenied: () -> Unit,
): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember(controller) { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (action != null) {
            if (granted && controller.isConnected) action() else onDenied()
        }
    }
    DisposableEffect(controller) { onDispose { pending = null } }
    return { action ->
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pending = action; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
}
