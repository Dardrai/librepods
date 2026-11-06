/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.os.Bundle
import java.lang.reflect.InvocationTargetException

internal enum class UsbVolumeActivationStage { PREFLIGHT, FRAMEWORK_REGISTRATION, CALLBACK_OWNERSHIP, NATIVE_UNITY }

/** Per-attempt diagnostics contain fixed stages and exception classes, never device data. */
internal fun usbVolumeActivationFailure(stage: UsbVolumeActivationStage, error: Throwable) = Bundle().apply {
    val cause = if (error is InvocationTargetException) error.targetException ?: error else error
    putString("failure_stage", stage.name.lowercase(java.util.Locale.ROOT))
    putString("exception_type", cause.javaClass.simpleName.take(80))
}
