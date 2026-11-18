/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import me.kavishdevar.librepods.audio.SpatialPose

/** One captured output lease. Implementations must submit/close promptly without Binder or waits. */
internal interface AtmosPoseOutput : AutoCloseable {
    fun submit(pose: SpatialPose, arrivalNs: Long)
}

/** Private app/root pipe framing, not the Codec2 command ABI; no transport identity is app-minted. */
internal object AtmosCanonicalFrame {
    const val BYTES = 48
    private const val VERSION = 1
    data class Value(val sensorNs: Long, val arrivalNs: Long, val discontinuity: Int,
        val rotation: List<Float>, val angularVelocity: List<Float>)

    fun encode(pose: SpatialPose, arrivalNs: Long): ByteArray {
        val value = Value(pose.timestampNs, arrivalNs, pose.discontinuity, pose.rotationVector, pose.angularVelocity)
        require(valid(value)) { "invalid_atmos_pose" }
        return ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(VERSION); putInt(value.discontinuity); putLong(value.sensorNs); putLong(value.arrivalNs)
            value.rotation.forEach(::putFloat); value.angularVelocity.forEach(::putFloat)
        }.array()
    }

    fun decode(bytes: ByteArray): Value? {
        if (bytes.size != BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (input.int != VERSION) return null
        val discontinuity = input.int
        return Value(input.long, input.long, discontinuity, List(3) { input.float }, List(3) { input.float })
            .takeIf(::valid)
    }

    private fun valid(value: Value) = value.sensorNs > 0 && value.arrivalNs > 0 && value.discontinuity in 0..255 &&
        value.rotation.size == 3 && value.angularVelocity.size == 3 &&
        value.rotation.all(Float::isFinite) && value.angularVelocity.all(Float::isFinite)
}

/** Consumes only this pipe's write end. A stalled reader fails this producer instead of blocking it. */
internal class AtmosCanonicalPipe(private val output: ParcelFileDescriptor) : AtmosPoseOutput {
    private var closed = false
    init {
        try {
            val fd = output.fileDescriptor
            check(OsConstants.S_ISFIFO(Os.fstat(fd).st_mode)) { "atmos_fd_not_pipe" }
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            check(flags and OsConstants.O_ACCMODE == OsConstants.O_WRONLY) { "atmos_pipe_not_writable" }
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        } catch (error: Exception) { output.close(); throw error }
    }

    @Synchronized override fun submit(pose: SpatialPose, arrivalNs: Long) {
        check(!closed) { "atmos_output_retired" }
        val frame = AtmosCanonicalFrame.encode(pose, arrivalNs)
        check(Os.write(output.fileDescriptor, frame, 0, frame.size) == frame.size) { "atmos_pipe_stalled" }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        output.close()
    }
}
