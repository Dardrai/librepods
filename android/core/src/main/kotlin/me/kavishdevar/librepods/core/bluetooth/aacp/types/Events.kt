package me.kavishdevar.librepods.core.bluetooth.aacp.types

import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.core.data.StemPressBud
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.core.data.audio.MicrophoneFrame

sealed interface AppleEvent {
    data class ConversationAwareness(
        val stage: Int,
        val sourceSocket: BluetoothSocket
    ): AppleEvent

    data class StemPress(
        val pressType: StemPressType,
        val bud: StemPressBud
    ): AppleEvent

    data class ShowNearbyUi(
        val sender: String
    ): AppleEvent

    data class OwnershipToFalseRequest(
        val sender: String,
        val reverseTapped: Boolean
    ): AppleEvent

    data class MicrophoneFrameEvent(
        val frame: MicrophoneFrame
    )
}
