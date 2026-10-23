package me.kavishdevar.librepods.core.devices

fun noiseModeCycleValue(raw: ByteArray?): Int? {
    if (raw == null || raw.isEmpty() || raw.size > 4 || raw.drop(1).any { it != 0.toByte() }) return null
    return (raw[0].toInt() and 255).takeIf { it in 1..15 && it.countOneBits() >= 2 }
}

fun AppleMetadata.noiseCycleRequiresAllowOff(): Boolean =
    BaseCapability.LOUD_SOUND_REDUCTION in AirPodsSpecs.getSpec(model).baseCapabilities &&
        (firmwareMajor ?: 8) >= 8

fun AppleMetadata.supportedNoiseModeCycleMask(allowOff: Boolean?): Int {
    if (model == AirPodsModel.UNKNOWN) return 0
    val caps = AirPodsSpecs.getSpec(model).baseCapabilities
    if (BaseCapability.LISTENING_MODE !in caps) return 0
    return 2 or 4 or
        (if (allowOff == true || !noiseCycleRequiresAllowOff() && allowOff != false) 1 else 0) or
        (if (BaseCapability.ADAPTIVE_AUDIO in caps && (firmwareMajor ?: 0) >= 6) 8 else 0)
}

fun validNoiseModeCycle(mask: Int, supported: Int): Boolean =
    mask in 1..15 && mask.countOneBits() >= 2 && mask and supported.inv() == 0
