/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep

/** JNI field names/types are fixed. Constructed by the root bridge, never deserialized from a frame. */
@Keep
internal data class AtmosNativeSnapshot(
    val rootUid: Int,
    val rootPid: Int,
    val sourceUid: Int,
    val sourcePid: Int,
    val targetUid: Int,
    val bootCount: Int,
    val address: String,
    val mac: ByteArray,
    val fullRole: ByteArray,
    val model: String,
    val firmware: String,
    val profileSchema: Int,
    val profileDigest: ByteArray,
    val binderOwner: IBinder,
    val socket: IBinder,
    val sourceEpoch: Long,
    val referenceEpoch: Long,
    val routeEpoch: Long,
    val discontinuity: Int,
    val decoderEpoch: Long,
    val bufferIssuedBoottimeMs: Long,
    val preparedBoottimeNs: Long,
    val deadlineBoottimeNs: Long,
    val directionalMapping: Int = 0,
)

/** All offers/retirement/status are bounded local native operations; native owns the blocking C2 worker. */
@Keep
internal object AtmosNativeClient {
    private var loaded = false
    @Synchronized fun load(): Boolean {
        if (Process.myUid() != 0) return false
        if (!loaded) loaded = runCatching { System.loadLibrary("pixel_atmos_client"); true }.getOrDefault(false)
        return loaded
    }

    @JvmStatic external fun nativeOpen(snapshot: AtmosNativeSnapshot): Long
    @JvmStatic external fun nativePreparationNonce(handle: Long): ByteArray
    @JvmStatic external fun nativeOffer(handle: Long, frame: ByteArray, publishAllowed: Boolean): Boolean
    @JvmStatic external fun nativeQuiesceDecoder(handle: Long, control: Long, instance: Long, epoch: Long, revision: Long): Boolean
    @JvmStatic external fun nativeAuthorizeDecoderHandoff(handle: Long, control: Long, instance: Long, epoch: Long, revision: Long, teardownBoottimeNs: Long): Boolean
    @JvmStatic external fun nativeHandoffState(handle: Long): LongArray
    @JvmStatic external fun nativeSnapshot(handle: Long): LongArray
    @JvmStatic external fun nativePauseDecoder(handle: Long, control: Long, instance: Long, epoch: Long, revision: Long): Boolean
    @JvmStatic external fun nativeResumeDecoder(handle: Long, control: Long, instance: Long, epoch: Long, revision: Long): Boolean
    @JvmStatic external fun nativeAuthorizeFreshSource(handle: Long, control: Long, instance: Long, epoch: Long, revision: Long): Boolean
    // True also covers stale/duplicate input ignored without refreshing native phone state.
    @JvmStatic external fun nativePhonePose(handle: Long, timestampNs: Long, quaternion: FloatArray, displayRotation: Int): Boolean
    @JvmStatic external fun nativeRetire(handle: Long): Boolean
    @JvmStatic external fun nativeStatus(handle: Long): LongArray
    @JvmStatic external fun nativeDiagnostic(handle: Long): LongArray
}
