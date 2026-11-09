/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.data.LiveValueEditor
import me.kavishdevar.librepods.data.LiveValueState
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.dongle.DongleProtocol
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.services.LibrePodsService
import org.mockito.Mockito.*

internal class DongleListeningFixture(service: LibrePodsService) {
    val state = MutableStateFlow(DongleState())
    val mode = MutableStateFlow(LiveValueState<Long>(null))
    val strength = MutableStateFlow(LiveValueState<Long>(null))
    val earDetection = MutableStateFlow(LiveValueState<Long>(null))
    val conversationAwareness = MutableStateFlow(LiveValueState<Long>(null))
    val personalizedVolume = MutableStateFlow(LiveValueState<Long>(null))
    val game = MutableStateFlow(LiveValueState<Boolean>(null))
    val writes = mutableListOf<Pair<Int, Long>>()
    val gameWrites = mutableListOf<Boolean>()
    val controller = mock(DongleController::class.java)

    init {
        `when`(controller.state).thenReturn(state)
        for ((id, values) in listOf(0x0d to mode, 0x2e to strength, 0x0a to earDetection,
            0x26 to personalizedVolume, 0x28 to conversationAwareness)) {
            @Suppress("UNCHECKED_CAST")
            val editor = mock(LiveValueEditor::class.java) as LiveValueEditor<Long>
            `when`(editor.state).thenReturn(values)
            `when`(editor.edit(anyLong(), anyLong(), anyBoolean())).thenAnswer { call ->
                val value = call.getArgument<Long>(1)
                writes += id to value
                values.value = values.value.copy(requested = value, pending = true)
                true
            }
            `when`(controller.liveSettingEditor(id)).thenReturn(editor)
        }
        @Suppress("UNCHECKED_CAST")
        val gameEditor = mock(LiveValueEditor::class.java) as LiveValueEditor<Boolean>
        `when`(gameEditor.state).thenReturn(game)
        `when`(gameEditor.edit(anyLong(), anyBoolean(), anyBoolean())).thenAnswer { call ->
            val value = call.getArgument<Boolean>(1)
            gameWrites += value
            game.value = game.value.copy(requested = value, pending = true)
            true
        }
        `when`(controller.liveGameModeEditor()).thenReturn(gameEditor)
        LibrePodsService::class.java.getDeclaredField("dongleControls\$delegate")
            .apply { isAccessible = true }.set(service, lazyOf(controller))
    }

    fun connect(modeValue: Long = 3) {
        mode.value = LiveValueState(modeValue)
        strength.value = LiveValueState(50L)
        earDetection.value = LiveValueState(2L)
        conversationAwareness.value = LiveValueState(2L)
        personalizedVolume.value = LiveValueState(2L)
        game.value = LiveValueState(false)
        state.value = DongleState(selected = true, connected = true, controlSession = 7,
            status = DongleProtocol.Status(3, listOf(0, 1, 2, 3, 4, 5), 3, modeValue.toInt(),
                listOf(90, 90, 70), 3, 511),
            metadata = AppleMetadata(name = "Dongle AirPods", model = AirPodsModel.AIRPODS_PRO_3,
                modelNumber = "A3063", version1 = "90.3431000025000000.6807", version3 = "9442752"),
            audio = DongleProtocol.AudioProfile(3, 3, 0, 256000, 256000, false, 1, 0, 8, null),
            settings = mapOf(0x0a to DongleProtocol.Setting(2L, 0, 1),
                0x26 to DongleProtocol.Setting(2L, 0, 1), 0x28 to DongleProtocol.Setting(2L, 0, 1),
                0x0d to DongleProtocol.Setting(modeValue, 0, 1),
                0x2e to DongleProtocol.Setting(50L, 0, 1), 0x34 to DongleProtocol.Setting(2L, 0, 1)))
    }
}
