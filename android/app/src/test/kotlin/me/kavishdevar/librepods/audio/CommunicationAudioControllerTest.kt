package me.kavishdevar.librepods.audio

import android.app.Application
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CommunicationAudioControllerTest {
    private fun report(kind: Int = 2, uid: Int = 10_123, session: Int = 42) = Bundle().apply {
        putBoolean("eligible", true); putInt("kind", kind); putInt("uid", uid)
        putInt("session", session); putString("package", "cocobo1.pupu.app")
    }

    @Test fun inspectionNeedsPositiveUidAndExactKind() {
        assertNotNull(CommunicationAudioController.inspectedCall(report(), 2))
        assertNull(CommunicationAudioController.inspectedCall(report(uid = 0), 2))
        assertNull(CommunicationAudioController.inspectedCall(report(), 1))
        assertNull(CommunicationAudioController.inspectedCall(report().apply { putBoolean("eligible", false) }, 2))
    }

    @Test fun voipNeedsConcreteSessionAndPackage() {
        assertNull(CommunicationAudioController.inspectedCall(report(session = 0), 2))
        assertNull(CommunicationAudioController.inspectedCall(report(session = -1), 2))
        assertNull(CommunicationAudioController.inspectedCall(report().apply { putString("package", "") }, 2))
        assertEquals(42, CommunicationAudioController.inspectedCall(report(), 2)?.session)
    }

    @Test fun cellularCannotReuseVoipSessionOrUnknownKind() {
        assertNotNull(CommunicationAudioController.inspectedCall(report(kind = 1, session = 0), 1))
        assertNull(CommunicationAudioController.inspectedCall(report(kind = 1, session = 42), 1))
        assertNull(CommunicationAudioController.inspectedCall(report(kind = 3), 3))
    }

    @Test fun inspectionDiagnosticsEmitOnlyChangesIncludingReturningFailures() {
        val diagnostic = ChangedCallDiagnostic()
        val rejected = "ineligible mode=3 kind=2 reason=no-session; kind=2 reason=no-session"
        assertEquals(rejected, diagnostic.changed(rejected))
        repeat(60) { assertNull(diagnostic.changed(rejected)) }
        assertEquals("starting kind=2", diagnostic.changed("starting kind=2"))
        assertEquals(rejected, diagnostic.changed(rejected))
    }

    @Test fun inspectionDiagnosticsKeepValidationReasonWithoutFrameworkPackageDetails() {
        val report = Bundle()
        report.putString("reason", "IllegalStateException: No active selected call")
        assertEquals("No active selected call", CommunicationAudioController.inspectionReason(report))
        report.putString("reason", "NameNotFoundException: private.calling.package")
        assertEquals("NameNotFoundException", CommunicationAudioController.inspectionReason(report))
        report.putString("reason", "")
        assertEquals("invalid-inspection-receipt", CommunicationAudioController.inspectionReason(report))
    }
}
