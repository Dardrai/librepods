package me.kavishdevar.librepods.dongle

import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.noiseCycleRequiresAllowOff
import me.kavishdevar.librepods.core.devices.supportedNoiseModeCycleMask

internal data class DongleAudioIntent(val codec: Int, val rateMode: Int, val bitrate: Long)

internal fun validLiveSetting(id: Int, value: Long): Boolean = when (id) {
    0x01, 0x17, 0x18, 0x23 -> value in 0L..2L
    0x0d -> value in 1L..4L
    0x0a, 0x1b, 0x1c, 0x20, 0x25, 0x26, 0x28, 0x31, 0x34, 0x35, 0x36, 0x3b -> value in 1L..2L
    0x16 -> value in setOf(0x0101L, 0x0105L, 0x0501L, 0x0505L)
    0x1a -> value in 0L..15L && java.lang.Long.bitCount(value) >= 2
    0x24 -> value in setOf(0x0200L, 0x0300L)
    0x1f -> value in 0x5000L..0x5064L
    0x2e, 0x40 -> value in 0L..100L
    else -> false
}

internal val dongleSavedChoiceIds = setOf(0x01, 0x0a, 0x16, 0x1a, 0x23, 0x31, 0x34, 0x40)
private val settingIds = listOf(0x01, 0x0a, 0x0d, 0x16, 0x17, 0x18, 0x1a, 0x1b, 0x1c, 0x1f,
    0x20, 0x23, 0x24, 0x25, 0x26, 0x28, 0x2e, 0x31, 0x34, 0x35, 0x36, 0x3b, 0x40)

/** The firmware's reviewed control catalog; the core model still owns semantic capabilities. */
private fun controlMask(model: AirPodsModel): Int = when (model) {
    AirPodsModel.AIRPODS_1 -> 0x00000203
    AirPodsModel.AIRPODS_2 -> 0x00000603
    AirPodsModel.AIRPODS_3 -> 0x0020163b
    AirPodsModel.AIRPODS_4 -> 0x0028563b
    AirPodsModel.AIRPODS_4_ANC -> 0x006bd6ff
    AirPodsModel.AIRPODS_5 -> 0x0029d6ff
    AirPodsModel.AIRPODS_5_WCC -> 0x006bfeff
    AirPodsModel.AIRPODS_PRO_1 -> 0x002016ff
    AirPodsModel.AIRPODS_PRO_2_LIGHTNING, AirPodsModel.AIRPODS_PRO_2_USBC, AirPodsModel.AIRPODS_PRO_3 -> 0x007ffeff
    AirPodsModel.AIRPODS_MAX_LIGHTNING, AirPodsModel.AIRPODS_MAX_USBC -> 0x00201746
    AirPodsModel.AIRPODS_MAX_2 -> 0x0029d746
    AirPodsModel.UNKNOWN -> 0
}

internal fun currentDongleSetting(id: Int, raw: Long): Long = if (id == 0x16) raw and 0xffff else raw

internal fun DongleState.canEditSetting(id: Int): Boolean {
    val info = metadata ?: return false
    val index = settingIds.indexOf(id)
    if (!connected || status?.controlsReady != true || status.microphoneActive || status.capabilities and 0x18 != 0x18 ||
        index < 0 || info.model == AirPodsModel.UNKNOWN || info.model != AirPodsModel.fromModelNumber(info.modelNumber) ||
        controlMask(info.model) and (1 shl index) == 0) return false
    val caps = AirPodsSpecs.getSpec(info.model).baseCapabilities
    if ((id == 0x2e && (BaseCapability.ADAPTIVE_AUDIO !in caps || (info.firmwareMajor ?: 0) < 6 || settings[0x0d]?.value != 4L)) ||
        (id == 0x35 && (BaseCapability.SLEEP_DETECTION !in caps || (info.firmwareMajor ?: 0) < 8))) return false
    val actual = settings[id]?.value
    if (actual != null) return validLiveSetting(id, currentDongleSetting(id, actual))
    return id in dongleSavedChoiceIds && (id != 0x34 || info.model == AirPodsModel.AIRPODS_PRO_3)
}

internal fun DongleState.canWriteSetting(id: Int, value: Long): Boolean {
    if (!canEditSetting(id) || !validLiveSetting(id, value)) return false
    val info = checkNotNull(metadata)
    val caps = AirPodsSpecs.getSpec(info.model).baseCapabilities
    val adaptive = BaseCapability.ADAPTIVE_AUDIO in caps && (info.firmwareMajor ?: 0) >= 6
    val offValue = requested[0x34] ?: settings[0x34]?.value ?: savedChoices[0x34]
    val allowOff = when (offValue) {
        1L -> true
        2L -> false
        else -> if (!info.noiseCycleRequiresAllowOff() || settings[0x0d]?.value == 1L) true else null
    }
    return when (id) {
        0x0d -> (value != 4L || adaptive) && (value != 1L || allowOff == true)
        0x16 -> BaseCapability.LISTENING_MODE in caps || value and 0xff != 5L && value shr 8 and 0xff != 5L
        0x1a -> value.toInt() and info.supportedNoiseModeCycleMask(allowOff).inv() == 0
        else -> true
    }
}
