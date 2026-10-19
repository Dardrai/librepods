package me.kavishdevar.librepods.shizuku

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.IInterface
import me.kavishdevar.librepods.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowProcess
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [BackgroundAudioAppOpsStubShadow::class])
class BackgroundAudioAccessTest {
    private val context = mock(Context::class.java)
    private val packages = mock(PackageManager::class.java)
    private val appOps = mock(AppOpsManager::class.java)
    private val uid = 10_123.also(ShadowProcess::setUid)
    private val packageName = BuildConfig.APPLICATION_ID
    private var mode = AppOpsManager.MODE_FOREGROUND
    private var granted = true
    private var grantChecks = 0
    private val writes = mutableListOf<Pair<Int, String>>()

    init {
        `when`(context.packageName).thenReturn(packageName)
        `when`(context.packageManager).thenReturn(packages)
        @Suppress("DEPRECATION")
        `when`(packages.getApplicationInfo(packageName, 0)).thenReturn(ApplicationInfo().also { it.uid = uid })
        `when`(context.getSystemService(AppOpsManager::class.java)).thenReturn(appOps)
        @Suppress("DEPRECATION")
        `when`(appOps.unsafeCheckOpRawNoThrow("android:control_audio", uid, packageName)).thenAnswer { mode }
    }

    private fun access(write: (Int, String) -> Unit = { targetUid, targetPackage ->
        writes += targetUid to targetPackage
        mode = AppOpsManager.MODE_ALLOWED
    }) = BackgroundAudioAccess(context, { grantChecks++; granted }, write)

    @Test @Config(sdk = [36]) fun olderAndroidNeverQueriesOrChangesAppOps() {
        assertTrue(access().ensureAllowed())
        assertEquals(0, grantChecks)
        assertTrue(writes.isEmpty())
        verifyNoInteractions(packages, appOps)
    }

    @Test fun alreadyAllowedNeedsNeitherShizukuNorAMutation() {
        mode = AppOpsManager.MODE_ALLOWED
        granted = false
        assertTrue(access().ensureAllowed())
        assertEquals(0, grantChecks)
        assertTrue(writes.isEmpty())
    }

    @Test fun foregroundOnlyAccessIsChangedForTheExactOwnerAndVerifiedOnce() {
        val access = access()
        assertTrue(access.ensureAllowed())
        assertEquals(listOf(uid to packageName), writes)
        assertEquals(1, grantChecks)
        assertTrue(access.ensureAllowed())
        assertEquals(1, writes.size)
        assertEquals(1, grantChecks)
        @Suppress("DEPRECATION")
        verify(appOps, times(3)).unsafeCheckOpRawNoThrow("android:control_audio", uid, packageName)
        verifyNoMoreInteractions(appOps)
    }

    @Test fun theShizukuAdapterWritesOnlyControlAudioAllowedForTheVerifiedOwner() {
        val binder = mock(IBinder::class.java)
        val calls = mutableListOf<List<Any?>>()
        val serviceType = Class.forName("com.android.internal.app.IAppOpsService")
        val service = Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType)) { _, method, arguments ->
            check(method.name == "setMode") { "Unexpected AppOps call: ${method.name}" }
            calls += checkNotNull(arguments).toList()
            mode = AppOpsManager.MODE_ALLOWED
            null
        } as IInterface
        mockStatic(SystemServiceHelper::class.java).use { services ->
            services.`when`<IBinder> { SystemServiceHelper.getSystemService(Context.APP_OPS_SERVICE) }.thenReturn(binder)
            mockConstruction(ShizukuBinderWrapper::class.java) { wrapper, construction ->
                assertEquals(listOf(binder), construction.arguments())
                `when`(wrapper.queryLocalInterface("com.android.internal.app.IAppOpsService")).thenReturn(service)
            }.use { wrappers ->
                val allowed = BackgroundAudioAccess(context, { true }).ensureAllowed()
                assertTrue(ShadowLog.getLogsForTag("BackgroundAudioAccess").joinToString("\n") {
                    "${it.msg}: ${it.throwable?.stackTraceToString()}"
                }, allowed)
                assertEquals(1, wrappers.constructed().size)
            }
        }
        val operation = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
            .invoke(null, "android:control_audio") as Int
        assertEquals(listOf(listOf(operation, uid, packageName, AppOpsManager.MODE_ALLOWED)), calls)
    }

    @Test fun aMissingShizukuGrantDoesNotWriteAndCanBeRetriedAfterAuthorization() {
        val access = access()
        granted = false
        assertFalse(access.ensureAllowed())
        assertTrue(writes.isEmpty())
        granted = true
        assertTrue(access.ensureAllowed())
        assertEquals(listOf(uid to packageName), writes)
        assertEquals(2, grantChecks)
    }

    @Test fun anotherPackageOrUidCannotReceiveTheAllowance() {
        `when`(context.packageName).thenReturn("another.app")
        assertFalse(access().ensureAllowed())
        `when`(context.packageName).thenReturn(packageName)
        @Suppress("DEPRECATION")
        `when`(packages.getApplicationInfo(packageName, 0)).thenReturn(ApplicationInfo().also { it.uid = uid + 1 })
        assertFalse(access().ensureAllowed())
        assertTrue(writes.isEmpty())
        assertEquals(0, grantChecks)
        verifyNoInteractions(appOps)
    }

    @Test fun aRejectedWriteOrUnchangedReadbackIsReportedAsFailure() {
        assertFalse(access { _, _ -> throw SecurityException("Test shell denial") }.ensureAllowed())
        assertFalse(access { targetUid, targetPackage -> writes += targetUid to targetPackage }.ensureAllowed())
        assertEquals(listOf(uid to packageName), writes)
        assertEquals(AppOpsManager.MODE_FOREGROUND, mode)
        assertTrue(ShadowLog.getLogsForTag("BackgroundAudioAccess").any {
            it.msg == "Could not allow LibrePods background volume control" && it.throwable != null
        })
    }
}

// Robolectric's default Stub shadow discards the binder and returns a no-op proxy.
@Implements(className = "com.android.internal.app.IAppOpsService\$Stub", isInAndroidSdk = false)
class BackgroundAudioAppOpsStubShadow {
    companion object {
        @JvmStatic @Implementation
        fun asInterface(binder: IBinder): IInterface? =
            binder.queryLocalInterface("com.android.internal.app.IAppOpsService")
    }
}
