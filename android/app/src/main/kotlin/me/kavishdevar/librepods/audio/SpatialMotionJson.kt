/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import me.kavishdevar.librepods.core.data.apple.MotionReport
import org.json.JSONArray
import org.json.JSONObject

internal fun motionCaptureSample(report: MotionReport, arrivalNs: Long): JSONObject = JSONObject().apply {
    put("type", "sample")
    put("arrival_ns", arrivalNs)
    put("report", JSONObject()
        .put("timestamp_ns", report.timestampNs ?: JSONObject.NULL)
        .put("attitude_q15", JSONArray(report.attitudeQ15))
        .put("gyro_raw", JSONArray(report.gyroRaw))
        .put("user_acceleration_raw", JSONArray(report.userAccelerationRaw))
        .put("gravity_raw", JSONArray(report.gravityRaw))
        .put("counter", report.counter ?: JSONObject.NULL))
}
