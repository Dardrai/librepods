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
    val writes = mutableListOf<Pair<Int, Long>>()
    val controller = mock(DongleController::class.java)

    init {
        `when`(controller.state).thenReturn(state)
        for ((id, values) in listOf(0x0d to mode, 0x2e to strength)) {
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
        LibrePodsService::class.java.getDeclaredField("dongleControls\$delegate")
            .apply { isAccessible = true }.set(service, lazyOf(controller))
    }

    fun connect(modeValue: Long = 3) {
        mode.value = LiveValueState(modeValue)
        strength.value = LiveValueState(50L)
        state.value = DongleState(selected = true, connected = true, controlSession = 7,
            status = DongleProtocol.Status(3, listOf(0, 1, 2, 3, 4, 5), 3, modeValue.toInt(),
                listOf(90, 90, 70), 3, 511),
            metadata = AppleMetadata(name = "Dongle AirPods", model = AirPodsModel.AIRPODS_PRO_3,
                modelNumber = "A3063", version1 = "90.3431000025000000.6807", version3 = "9442752"),
            settings = mapOf(0x0d to DongleProtocol.Setting(modeValue, 0, 1),
                0x2e to DongleProtocol.Setting(50L, 0, 1), 0x34 to DongleProtocol.Setting(2L, 0, 1)))
    }
}
