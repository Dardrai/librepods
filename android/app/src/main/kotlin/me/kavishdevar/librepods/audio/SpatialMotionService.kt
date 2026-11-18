/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.kavishdevar.librepods.LibrePodsApplication

class SpatialMotionService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent): IBinder = object : ISpatialMotionSource.Stub() {
        override fun open(owner: IBinder): ISpatialMotionLease? {
            enforceCallingPermission(PERMISSION, "Calibrated spatial motion requires a LibrePods-signed client")
            val uid = Binder.getCallingUid()
            val controllers = (application as LibrePodsApplication).nativeHeadTrackingControllers.values
            return controllers.singleOrNull { it.offersSpatialMotion() }?.openSpatialMotion(scope, owner, uid)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PERMISSION = "me.kavishdevar.librepods.permission.SPATIAL_MOTION"
    }
}
