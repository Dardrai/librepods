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

package me.kavishdevar.librepods.core.bluetooth.att.types

import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.devices.AccommodationsPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.max

data class TransparencySettings(
    val enabled: Boolean,
    val leftEQ: FloatArray,
    val rightEQ: FloatArray,
    val leftAmplification: Float,
    val rightAmplification: Float,
    val leftTone: Float,
    val rightTone: Float,
    val leftConversationBoost: Boolean,
    val rightConversationBoost: Boolean,
    val leftAmbientNoiseReduction: Float,
    val rightAmbientNoiseReduction: Float,
    val netAmplification: Float,
    val balance: Float,
    val ownVoiceAmplification: Float? = null
) {
    fun withAmplification(amplification: Float = netAmplification, balance: Float = this.balance) = copy(
        leftAmplification = (amplification - min(balance, 0f)).coerceIn(-1f, 2f),
        rightAmplification = (amplification + max(balance, 0f)).coerceIn(-1f, 2f),
        netAmplification = amplification,
        balance = balance
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransparencySettings

        if (enabled != other.enabled) return false
        if (leftAmplification != other.leftAmplification) return false
        if (rightAmplification != other.rightAmplification) return false
        if (leftTone != other.leftTone) return false
        if (rightTone != other.rightTone) return false
        if (leftConversationBoost != other.leftConversationBoost) return false
        if (rightConversationBoost != other.rightConversationBoost) return false
        if (leftAmbientNoiseReduction != other.leftAmbientNoiseReduction) return false
        if (rightAmbientNoiseReduction != other.rightAmbientNoiseReduction) return false
        if (!leftEQ.contentEquals(other.leftEQ)) return false
        if (!rightEQ.contentEquals(other.rightEQ)) return false
        if (ownVoiceAmplification != other.ownVoiceAmplification) return false

        return true
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + leftAmplification.hashCode()
        result = 31 * result + rightAmplification.hashCode()
        result = 31 * result + leftTone.hashCode()
        result = 31 * result + rightTone.hashCode()
        result = 31 * result + leftConversationBoost.hashCode()
        result = 31 * result + rightConversationBoost.hashCode()
        result = 31 * result + leftAmbientNoiseReduction.hashCode()
        result = 31 * result + rightAmbientNoiseReduction.hashCode()
        result = 31 * result + leftEQ.contentHashCode()
        result = 31 * result + rightEQ.contentHashCode()
        result = 31 * result + (ownVoiceAmplification?.hashCode() ?: 0)
        return result
    }
}

fun parseTransparencySettingsResponse(data: ByteArray): TransparencySettings? {
    if (data.size != 100 && data.size != 104) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    if ((data.indices step 4).any { !buffer.getFloat(it).isFinite() }) return null
    if (listOf(0, 44, 92).any { buffer.getFloat(it) != 0f && buffer.getFloat(it) != 1f }) return null
    if (listOf(36, 84).any { buffer.getFloat(it) !in -1f..2f } ||
        listOf(40, 88).any { buffer.getFloat(it) !in -1f..1f } ||
        listOf(48, 96).any { buffer.getFloat(it) !in 0f..1f }) return null
    if (data.size == 104 && buffer.getFloat(100) !in 0f..1f) return null

    val enabled = buffer.float

    val leftEQ = FloatArray(8)
    for (i in 0..7) {
        leftEQ[i] = buffer.float
    }
    val leftAmplification = buffer.float
    val leftTone = buffer.float
    val leftConvFloat = buffer.float
    val leftConversationBoost = leftConvFloat == 1f
    val leftAmbientNoiseReduction = buffer.float

    val rightEQ = FloatArray(8)
    for (i in 0..7) {
        rightEQ[i] = buffer.float
    }

    val rightAmplification = buffer.float
    val rightTone = buffer.float
    val rightConvFloat = buffer.float
    val rightConversationBoost = rightConvFloat == 1f
    val rightAmbientNoiseReduction = buffer.float

    val ownVoiceAmplification = if (buffer.remaining() >= 4) {
        buffer.float
    } else {
        null
    }

    val amplification = min(leftAmplification, rightAmplification)
    val diff = rightAmplification - leftAmplification
    if (!diff.isFinite()) return null

    return TransparencySettings(
        enabled = enabled == 1f,
        leftEQ = leftEQ,
        rightEQ = rightEQ,
        leftAmplification = leftAmplification,
        rightAmplification = rightAmplification,
        leftTone = leftTone,
        rightTone = rightTone,
        leftConversationBoost = leftConversationBoost,
        rightConversationBoost = rightConversationBoost,
        leftAmbientNoiseReduction = leftAmbientNoiseReduction,
        rightAmbientNoiseReduction = rightAmbientNoiseReduction,
        netAmplification = amplification,
        balance = diff,
        ownVoiceAmplification = ownVoiceAmplification
    )
}

fun customTransparencyEnabled(data: ByteArray): Boolean? =
    if (data.contentEquals(byteArrayOf(2, 0, 0, 0))) false
    else parseTransparencySettingsResponse(data)?.enabled

/** Captured stock presets map their two voice arrays to Custom Transparency. */
fun accommodationsTransparencyPreset(baseline: ByteArray, preset: AccommodationsPreset): ByteArray? {
    if (baseline.size != 104 || parseTransparencySettingsResponse(baseline) == null) return null
    val curves = preset.curves()
    return baseline.copyOf().also {
        curves.copyInto(it, 4, 0, 32)
        curves.copyInto(it, 52, 64, 96)
    }
}

fun sendTransparencySettings(
    currentData: ByteArray,
    transparencySettings: TransparencySettings,
    writer: (ATTHandle, ByteArray, ByteArray) -> Unit
) {
    val baseline = currentData.copyOf()
    if (parseTransparencySettingsResponse(baseline) == null) return
    if (transparencySettings.leftEQ.size != 8 || transparencySettings.rightEQ.size != 8 ||
        (transparencySettings.ownVoiceAmplification != null && baseline.size < 104)
    ) return
    val buffer = ByteBuffer.wrap(baseline.copyOf()).order(ByteOrder.LITTLE_ENDIAN)

    // Numeric equality keeps untouched signed-zero fields byte-for-byte intact.
    fun patch(offset: Int, value: Float) {
        if (buffer.getFloat(offset) != value) buffer.putFloat(offset, value)
    }
    patch(0, if (transparencySettings.enabled) 1f else 0f)
    for (index in 0 until 8) {
        patch(4 + index * 4, transparencySettings.leftEQ[index])
        patch(52 + index * 4, transparencySettings.rightEQ[index])
    }
    patch(36, transparencySettings.leftAmplification)
    patch(40, transparencySettings.leftTone)
    patch(44, if (transparencySettings.leftConversationBoost) 1f else 0f)
    patch(48, transparencySettings.leftAmbientNoiseReduction)
    patch(84, transparencySettings.rightAmplification)
    patch(88, transparencySettings.rightTone)
    patch(92, if (transparencySettings.rightConversationBoost) 1f else 0f)
    patch(96, transparencySettings.rightAmbientNoiseReduction)
    transparencySettings.ownVoiceAmplification?.let {
        patch(100, it)
    }

    val data = buffer.array()
    if (parseTransparencySettingsResponse(data) != null) writer(ATTHandle.TRANSPARENCY, data, baseline)
}

fun disableCustomizedTransparency(currentData: ByteArray, writer: (ATTHandle, ByteArray, ByteArray) -> Unit) {
    val settings = parseTransparencySettingsResponse(currentData) ?: return
    if (settings.enabled) sendTransparencySettings(currentData, settings.copy(enabled = false), writer)
}
