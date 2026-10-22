package me.kavishdevar.librepods.companion

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CompanionProtocolTest {
    private val device = "AA:BB:CC:DD:EE:FF"
    private val sid = "00".repeat(32)
    private val tx = "0123456789abcdef"

    @Test fun multipleFramesRemainDistinctAndUseTheExactSchema() {
        val frame = CompanionProtocol.frame("prepare", device, tx, sid).put("target", "android").put("purpose", "manual").put("episode", "0".repeat(32)).put("call_gen", CompanionProtocol.HANDSHAKE_TX).put("origin_token", "0".repeat(32))
        val stream = ByteArrayOutputStream()
        repeat(2) { CompanionProtocol.write(stream, frame) }
        val input = ByteArrayInputStream(stream.toByteArray())
        repeat(2) { CompanionProtocol.validate(CompanionProtocol.read(input), device, sid) }
        assertEquals(-1, input.read())
    }

    @Test fun duplicateFieldsExtensionsAndNonIntegerVersionsAreRejected() {
        for (text in listOf("{\"v\":2,\"v\":2}", "{\"v\":2.0}", "{\"v\":2,}",
            "{'v':1}", "{\"v\":null}", "{\"v\":{}}", "\uFEFF{\"v\":2}")) {
            assertThrows("Must reject $text", Exception::class.java) { CompanionProtocol.parse(text) }
        }
        val extra = CompanionProtocol.frame("ping", device, sid = sid).put("extra", true)
        assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validate(extra, device, sid) }
    }

    @Test fun malformedFramesAreBoundedBeforeParsing() {
        for (bytes in listOf("\n".toByteArray(), "{}\r\n".toByteArray(),
            ByteArray(8193) { ' '.code.toByte() }, byteArrayOf(0xc0.toByte(), 0xaf.toByte(), 10))) {
            assertThrows(Exception::class.java) { CompanionProtocol.read(ByteArrayInputStream(bytes)) }
        }
    }

    @Test fun receiptsMustMatchTheirExactPhaseAndBooleanType() {
        val result = CompanionProtocol.frame("result", device, tx, sid).put("phase", "release").put("ok", true).put("code", "released")
        CompanionProtocol.validate(result, device, sid)
        for (code in listOf("busy", "acquired", "ready")) {
            result.put("code", code)
            assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validate(result, device, sid) }
        }
        result.put("code", "released").put("ok", "true")
        assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validate(result, device, sid) }
    }

    @Test fun wrongDeviceSessionAndTransactionKindsCannotCrossSessions() {
        val ping = CompanionProtocol.frame("ping", device, sid = sid)
        assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validate(ping, "AA:BB:CC:DD:EE:00", sid) }
        assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validate(ping, device, "ff".repeat(32)) }
        ping.put("tx", tx)
        assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validate(ping, device, sid) }
        val acquire = CompanionProtocol.frame("acquire", device, sid = sid)
        assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validate(acquire, device, sid) }
    }
}
