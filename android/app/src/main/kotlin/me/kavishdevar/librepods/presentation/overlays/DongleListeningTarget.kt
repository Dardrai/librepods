/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.HearingMode
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
        return ListeningModeSnapshot(ui.metadata, ui.state, ui.settings, dongleQuickSettings(state,
            listOf(0x0a, 0x26, 0x28).associateWith { controller.liveSettingEditor(it).state.value },
            controller.liveGameModeEditor().state.value))
    }

    fun write(identifier: ControlCommandIdentifier, value: Int): Boolean {
        val state = controller.state.value
        val id = identifier.value.toInt() and 255
        if (id !in setOf(0x0d, 0x2e) || !isCurrent(controller, state) ||
            !state.canWriteSetting(id, value.toLong())) return false
        val editor = controller.liveSettingEditor(id)
        return editor.edit(editor.state.value.revision, value.toLong())
    }

    fun write(setting: QuickSetting, enabled: Boolean): Boolean {
        val current = snapshot() ?: return false
        val toggle = current.quickSettings.first { it.setting == setting }
        if (!toggle.enabled || toggle.checked == null || toggle.checked == enabled) return false
        setting.control?.let { id ->
            val number = id.value.toInt() and 255
            val value = id.encodeBoolean(enabled).toLong() and 255
            if (!controller.state.value.canWriteSetting(number, value)) return false
            val editor = controller.liveSettingEditor(number)
            return editor.edit(editor.state.value.revision, value)
        }
        return when (setting) {
            QuickSetting.GAME_MODE -> controller.liveGameModeEditor().let { it.edit(it.state.value.revision, enabled) }
            QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST -> {
                if (!isCurrent(controller)) false else {
                    controller.hearingMode(if (setting == QuickSetting.HEARING_AID) HearingMode.HEARING_AID else HearingMode.MEDIA_ASSIST, enabled)
                    true
                }
            }
            else -> false
        }
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
