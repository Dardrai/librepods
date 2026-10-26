package me.kavishdevar.librepods.presentation.screens.settings

import androidx.compose.runtime.MutableState
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendHearingAidSettings
import me.kavishdevar.librepods.data.LiveRecordEditor
import me.kavishdevar.librepods.data.LiveRecordSource
import me.kavishdevar.librepods.data.LiveValueEditor
import me.kavishdevar.librepods.data.LiveValueSource
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.dongle.DongleState
import me.kavishdevar.librepods.dongle.DongleAudioIntent
import me.kavishdevar.librepods.dongle.validLiveSetting
import org.mockito.Answers
import org.mockito.Mockito.mock

internal class DongleControlTestEditors(private val current: MutableState<DongleState>,
    onAudio: (DongleAudioRequest) -> Unit = {}, onHearing: (HearingAidSettings) -> Unit = {},
    onGame: (Boolean) -> Unit = {}) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ordinary = mutableMapOf<Int, LiveValueEditor<Long>>()
    private val audio = LiveValueEditor(scope, { LiveValueSource(owner(), current.value.audio?.let {
        DongleAudioIntent(it.requestedCodec, it.rateMode, it.requestedBitrate) }, true) },
        { _: DongleAudioIntent -> true }, write = { _, value -> onAudio(DongleAudioRequest(value.codec, value.rateMode, value.bitrate)); true })
    private val game = LiveValueEditor(scope, { LiveValueSource(owner(), current.value.audio?.gameRequested, true) },
        { _: Boolean -> true }, write = { _, value ->
            onGame(value)
            current.value = current.value.copy(audio = current.value.audio?.copy(gameRequested = value)); true
        })
    private fun owner(): Any = current.value.status?.let { Triple(it.generation, it.address, current.value.controlSession) } ?: Unit
    private fun profile(): ByteArray {
        val value = current.value.hearingProfile ?: return byteArrayOf()
        val baseline = ByteArray(104).also { it[0] = 2; it[1] = 2; it[2] = 0x60 }
        var result = baseline
        sendHearingAidSettings(baseline, value) { _, data, _ -> result = data.copyOf().also { it[2] = 0x60 } }
        return result
    }
    private val hearing = LiveRecordEditor(scope,
        read = { LiveRecordSource(owner(), profile(), true) }, valid = ::hearingAidSettingsWritable,
        matches = { a, b -> a.size == 104 && b.size == 104 && a.copyOfRange(4, 104).contentEquals(b.copyOfRange(4, 104)) },
        write = { _, _, requested ->
            val parsed = checkNotNull(parseHearingAidSettingsResponse(requested))
            current.value = current.value.copy(hearingProfile = parsed)
            onHearing(parsed)
            true
        })
    val controller: DongleController = mock(DongleController::class.java) { call -> when (call.method.name) {
        "liveHearingEditor" -> hearing.also { it.observe() }
        "liveAudioEditor" -> audio.also { it.observe() }
        "liveGameModeEditor" -> game.also { it.observe() }
        "liveSettingEditor" -> ordinary.getOrPut(call.getArgument(0)) {
            val id = call.getArgument<Int>(0)
            LiveValueEditor(scope, { LiveValueSource(owner(), current.value.requested[id] ?: current.value.settings[id]?.value, true) },
                { validLiveSetting(id, it) }, write = { _, value ->
                    current.value = current.value.copy(requested = current.value.requested + (id to value)); true
                })
        }.also { it.observe() }
        "audioProfile" -> { onAudio(DongleAudioRequest(call.getArgument(0), call.getArgument(1), call.getArgument(2))); null }
        else -> Answers.RETURNS_DEFAULTS.answer(call)
    } }
    override fun close() { scope.cancel() }
}
