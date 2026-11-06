/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.dongle.appleUiState
import me.kavishdevar.librepods.dongle.canWriteControl
import me.kavishdevar.librepods.dongle.canWriteSetting

internal val DongleState.usesDongleListeningControls: Boolean
    get() = selected && (connected || busy || attached)

/** A picker belongs to one control connection, including across shade/unlock callbacks. */
internal data class DongleListeningTarget private constructor(
    val controller: DongleController,
    val session: Long,
    val generation: Long,
    val address: List<Int>,
) {
    val macAddress get() = MacAddress(address.joinToString(":") { it.toString(16).padStart(2, '0') })

    fun isCurrent(current: DongleController, state: DongleState = current.state.value): Boolean =
        current === controller && capture(current, state) == this

    fun snapshot(): ListeningModeSnapshot? {
        val state = controller.state.value
        if (!isCurrent(controller, state)) return null
        val ui = state.appleUiState(
            listeningMode = controller.liveSettingEditor(0x0d).state.value,
            adaptiveStrength = controller.liveSettingEditor(0x2e).state.value,
        )
        return ListeningModeSnapshot(ui.metadata, ui.state, ui.settings)
    }

    fun write(identifier: ControlCommandIdentifier, value: Int): Boolean {
        val state = controller.state.value
        val id = identifier.value.toInt() and 255
        if (id !in setOf(0x0d, 0x2e) || !isCurrent(controller, state) ||
            !state.canWriteSetting(id, value.toLong())) return false
        val editor = controller.liveSettingEditor(id)
        return editor.edit(editor.state.value.revision, value.toLong())
    }

    companion object {
        fun capture(controller: DongleController, state: DongleState = controller.state.value): DongleListeningTarget? {
            val status = state.status ?: return null
            val metadata = state.metadata ?: return null
            val mode = state.settings[0x0d]?.value
            if (!state.selected || !state.canWriteControl(ControlCommandIdentifier.LISTENING_MODE) ||
                BaseCapability.LISTENING_MODE !in AirPodsSpecs.getSpec(metadata.model).baseCapabilities ||
                mode == null || mode !in 1L..4L || status.address.size != 6 ||
                status.address.any { it !in 0..255 } || status.address.all { it == 0 }) return null
            return DongleListeningTarget(controller, state.controlSession, status.generation, status.address.toList())
        }
    }
}
