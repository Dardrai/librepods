/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import me.kavishdevar.librepods.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AtmosVolumeAdmissionTest {
    @Test fun healthyChecksPreserveLeaseAndRepairsRetireBeforeExactOriginalWritesEvenWithoutGuardPolls() {
        for (throwSetter in listOf(false, true)) {
            val previousUid = Process.myUid(); val previousCaller = Binder.getCallingUid()
            try {
                ShadowProcess.setUid(10123)
                val context = mock(Context::class.java); val pm = mock(PackageManager::class.java)
                `when`(context.packageManager).thenReturn(pm)
                `when`(pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)).thenReturn(ApplicationInfo().apply { uid = 10123 })
                `when`(pm.checkPermission(anyString(), eq(BuildConfig.APPLICATION_ID))).thenReturn(PackageManager.PERMISSION_GRANTED)
                val service = ShizukuAudioService(context, false)
                val manager = mock(RootAtmosHeadTracking::class.java)
                val nativeLive = AtomicBoolean(true)
                val serviceLock = ReflectionHelpers.getField<Any>(service, "lock")
                doAnswer { assertTrue(Thread.holdsLock(serviceLock)); nativeLive.set(false); null }.`when`(manager).retireForVolumeRepair()
                ReflectionHelpers.setField(service, "atmosHeadTracking", manager)
                val address = "00:11:22:33:44:55"
                val owner = mock(IPlaybackRecoveryOwner::class.java)
                `when`(owner.asBinder()).thenReturn(Binder()); `when`(owner.isCurrent).thenReturn(true)
                var volume = 13; var behavior = 3; var elapsed = 0L
                val writes = mutableListOf<String>(); val pauses = mutableListOf<Long>()
                fun admission() = ReflectionHelpers.callInstanceMethod<String?>(service, "atmosAdmissionRejection")
                fun beforeDelegate() {
                    assertFalse(nativeLive.get())
                    assertTrue(ReflectionHelpers.getField<Boolean>(service, "volumeChecking"))
                    assertEquals("volume_repairing", admission())
                    // Even between individual setters, no replacement native experiment can be admitted.
                    val caller = Binder.getCallingUid(); ShadowBinder.setCallingUid(10123)
                    val pipe = mock(ParcelFileDescriptor::class.java)
                    try {
                        assertEquals("atmos_busy", assertThrows(IllegalStateException::class.java) {
                            service.openAtmosHeadTracking(address, owner, Binder(), Bundle(), Bundle(), pipe)
                        }.message)
                        verify(pipe).close()
                    } finally { ShadowBinder.setCallingUid(caller) }
                }
                val delegate = object : BluetoothVolumeSync.Audio {
                    override fun syncEnabled(address: String) = true
                    override fun snapshot() = BluetoothVolumeSync.Snapshot(AudioManager.MODE_NORMAL, volume, 25,
                        listOf(BluetoothVolumeSync.Route(address, 2, 8, behavior, this)))
                    override fun setVolume(index: Int) {
                        beforeDelegate(); writes += "volume:$index"
                        if (throwSetter) error("setter_failed")
                        volume = index
                    }
                    override fun setAbsolute(route: BluetoothVolumeSync.Route) {
                        beforeDelegate(); assertEquals(address, route.address); writes += "absolute"; behavior = 3
                    }
                }
                val wrapped = ReflectionHelpers.callInstanceMethod<BluetoothVolumeSync.Audio>(service, "volumeRepairAudio",
                    ReflectionHelpers.ClassParameter.from(BluetoothVolumeSync.Audio::class.java, delegate))
                val sync = BluetoothVolumeSync(context, wrapped, { true }, { elapsed }, { pauses += it; elapsed += it })
                ReflectionHelpers.setField(service, "volumeSync\$delegate", lazy { sync })
                ShadowProcess.setUid(0); ShadowBinder.setCallingUid(10123)
                repeat(2) { assertFalse(service.checkVolumeSync(address, owner)) }
                assertTrue(nativeLive.get()); verify(manager, never()).retireForVolumeRepair()
                assertTrue(writes.isEmpty()); assertNull(admission())
                behavior = 0
                assertFalse(service.checkVolumeSync(address, owner)); elapsed += 500
                assertEquals(!throwSetter, service.checkVolumeSync(address, owner))
                verify(manager, times(1)).retireForVolumeRepair()
                assertEquals(if (throwSetter) listOf("volume:0") else listOf("volume:0", "absolute") + (1..11).map { "volume:$it" }, writes)
                assertEquals(if (throwSetter) emptyList<Long>() else List(11) { 40L }, pauses)
                assertFalse(ReflectionHelpers.getField<Boolean>(service, "volumeChecking"))
                assertFalse(ReflectionHelpers.getField<Boolean>(service, "volumeRepairing"))
                assertNull(admission())
                assertFalse(mockingDetails(manager).invocations.any { it.method.name == "open" })
            } finally { ShadowProcess.setUid(previousUid); ShadowBinder.setCallingUid(previousCaller) }
        }
    }
}
