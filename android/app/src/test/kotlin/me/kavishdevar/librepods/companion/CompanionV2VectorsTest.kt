package me.kavishdevar.librepods.companion

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CompanionV2VectorsTest {
    private fun vectors(): JSONObject = JSONObject(javaClass.getResource("/companion-v2.json")!!.readText())

    @Test fun strictV2FramesMatchTheWindowsFixtures() {
        val fixture = vectors()
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val authenticated = case.getString("name") !in setOf("hello", "capability_subset", "unknown_capability")
            val actual = runCatching {
                val frame = CompanionProtocol.parse(case.getString("frame"))
                CompanionProtocol.validate(frame, fixture.getString("device"), if (authenticated) fixture.getString("sid") else null)
            }.isSuccess
            assertEquals(case.getString("name"), case.getBoolean("accepted"), actual)
        }
    }

    @Test fun bothAuthenticationRolesBindTheV2Capabilities() {
        val fixture = vectors()
        val key = CompanionProtocol.hexBytes(fixture.getString("key"), 32)
        val auth = fixture.getJSONArray("auth")
        for (index in 0 until auth.length()) {
            val case = auth.getJSONObject(index)
            val transcript = CompanionProtocol.transcript(fixture.getString("device"), fixture.getString("client_nonce"), fixture.getString("server_nonce"), case.getString("client_caps"), case.getString("server_caps"))
            assertEquals(case.getString("transcript"), transcript.toString(Charsets.UTF_8))
            assertEquals(case.getString("sid"), CompanionProtocol.sid(transcript))
            for (role in listOf("server", "client")) {
                assertEquals(case.getString("${role}_proof"), CompanionProtocol.proof(key, role, transcript))
            }
        }
    }
}
