package me.kavishdevar.librepods.core.bluetooth.att

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ATTManagerTest {
    private fun transparency(): ByteArray = ByteArray(104)

    private class Transport(override val receiveMtu: Int = 256, override val transmitMtu: Int = 256) : ATTTransport {
        @Volatile override var connected = false
        val requests = mutableListOf<ByteArray>()
        val replies = LinkedBlockingQueue<ByteArray>()
        val closed = CountDownLatch(1)
        var connectAction: (() -> Unit)? = null
        var reply: (ByteArray) -> ByteArray? = { null }
        override fun connect() { connectAction?.invoke(); if (closed.count == 0L) error("closed"); connected = true }
        override fun read(buffer: ByteArray): Int {
            val pdu = replies.take()
            if (pdu.isEmpty()) return -1
            require(pdu.size <= buffer.size)
            pdu.copyInto(buffer)
            return pdu.size
        }
        override fun write(pdu: ByteArray) {
            check(connected)
            synchronized(requests) { requests += pdu.copyOf() }
            reply(pdu)?.let(replies::put)
        }
        override fun close() { connected = false; closed.countDown(); replies.offer(byteArrayOf()) }
    }

    private fun settingPage(handle: Int = 0x35, properties: Int = 0x0a): ByteArray =
        byteArrayOf(9, 21, 0x30, 0, properties.toByte(), handle.toByte(), (handle ushr 8).toByte()) + ATTHandle.TRANSPARENCY.uuid!!.attBytes()

    private fun script(transport: Transport, initial: ByteArray = transparency(), properties: Int = 0x0a): AtomicReference<ByteArray> {
        val value = AtomicReference(initial.copyOf())
        transport.reply = { request -> when (request[0].toInt() and 255) {
            6 -> byteArrayOf(7, 0x20, 0, 0x40, 0)
            8 -> if (request.u16(1) <= 0x30) settingPage(properties = properties) else byteArrayOf(1, 8, 0x40, 0, 0x0a)
            0x0a -> { assertEquals(0x35, request.u16(1)); byteArrayOf(0x0b) + value.get() }
            0x12 -> { assertEquals(0x35, request.u16(1)); value.set(request.copyOfRange(3, request.size)); byteArrayOf(0x13) }
            0x52 -> { value.set(request.copyOfRange(3, request.size)); null }
            else -> error("Unexpected ATT opcode")
        } }
        return value
    }

    @Test fun dynamicDiscoveryRequiresFullFreshReadBeforeVerifiedWrite() = runBlocking {
        val socket = Transport()
        script(socket)
        val owner = ATTOwner(Any(), 1)
        val manager = ATTManager({ owner }, { socket })
        try {
            assertFalse(manager.available)
            assertFalse(manager.writeCharacteristic(ATTHandle.TRANSPARENCY, transparency(), transparency()) { true })
            val baseline = manager.readCharacteristic(ATTHandle.TRANSPARENCY)!!
            assertTrue(manager.available)
            val token = manager.sessionToken()
            val next = baseline.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(36, .25f) }
            assertTrue(manager.writeCharacteristic(ATTHandle.TRANSPARENCY, next, baseline) { manager.ownsSession(token) })
            assertArrayEquals(next, manager.readCharacteristic(ATTHandle.TRANSPARENCY))
            assertTrue(socket.requests.filter { it[0] == 0x12.toByte() }.all { it.u16(1) == 0x35 })
            assertFalse(socket.requests.any { it[0] in byteArrayOf(2, 0x16, 0x18) })
        } finally { manager.disconnect() }
    }

    @Test fun aBusyReadCannotConsumeAnAttemptBeforeAttAdmission() = runBlocking {
        val socket = Transport().also { script(it) }
        val owner = ATTOwner(Any(), 1)
        var attempts = 0
        var starts = 0
        val manager = ATTManager({ owner }, { starts++; socket })
        try {
            assertNull(manager.readCharacteristic(ATTHandle.TRANSPARENCY, { false }) { attempts++ })
            assertEquals(0, attempts)
            assertEquals(0, starts)
            assertNotNull(manager.readCharacteristic(ATTHandle.TRANSPARENCY, { true }) { attempts++ })
            assertEquals(1, attempts)
            assertEquals(1, starts)
        } finally { manager.disconnect() }
    }

    @Test fun commandOnlyPropertyStillRequiresActualReadbackAndReadOnlyPropertyNeverWrites() = runBlocking {
        for (properties in listOf(2, 6)) {
            val socket = Transport()
            script(socket, properties = properties)
            val owner = ATTOwner(Any(), 1)
            val manager = ATTManager({ owner }, { socket })
            try {
                val baseline = manager.readCharacteristic(ATTHandle.TRANSPARENCY)!!
                val next = baseline.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(100, .5f) }
                assertEquals(properties == 6, manager.available)
                assertEquals(properties == 6, manager.writeCharacteristic(ATTHandle.TRANSPARENCY, next, baseline) { true })
                assertEquals(properties == 6, socket.requests.any { it[0] == 0x52.toByte() })
            } finally { manager.disconnect() }
        }
    }

    @Test fun protectedReadErrorDoesNotRetryOrClaimAvailability() = runBlocking {
        val socket = Transport()
        script(socket)
        val base = socket.reply
        socket.reply = { request -> if (request[0] == 0x0a.toByte()) byteArrayOf(1, 0x0a, 0x35, 0, 0x0e) else base(request) }
        val starts = AtomicInteger()
        val owner = ATTOwner(Any(), 1)
        val manager = ATTManager({ owner }, { starts.incrementAndGet(); socket })
        assertNull(manager.readCharacteristic(ATTHandle.TRANSPARENCY))
        assertFalse(manager.available)
        assertTrue(socket.closed.await(1, TimeUnit.SECONDS))
        repeat(3) { assertNull(manager.readCharacteristic(ATTHandle.TRANSPARENCY)) }
        assertEquals(1, starts.get())
        assertFalse(socket.requests.any { it[0] in byteArrayOf(0x12, 0x52) })
    }

    @Test fun negotiatedMtuNeverSplitsAWriteOrPadsATruncatedRead() = runBlocking {
        for ((rx, tx) in listOf(47 to 256, 256 to 47, 48 to 256, 256 to 106)) {
            val socket = Transport(rx, tx)
            script(socket, initial = if (minOf(rx, tx) == 48) ByteArray(47) else transparency())
            val owner = ATTOwner(Any(), 1)
            val manager = ATTManager({ owner }, { socket })
            try {
                val baseline = manager.readCharacteristic(ATTHandle.TRANSPARENCY)
                if (minOf(rx, tx) < 105) assertNull(baseline)
                assertFalse(manager.available)
                assertFalse(manager.writeCharacteristic(ATTHandle.TRANSPARENCY, transparency(), transparency()) { true })
                assertFalse(socket.requests.any { it[0] in byteArrayOf(0x12, 0x52) })
                assertTrue(socket.requests.all { it.size <= minOf(rx, tx) })
            } finally { manager.disconnect() }
        }
    }

    @Test fun cancellationClosesOnlyPendingAttSocketAndRetiredOwnerCannotPublishOrWrite() = runBlocking {
        val old = Transport()
        val entered = CountDownLatch(1)
        old.connectAction = { entered.countDown(); old.closed.await(2, TimeUnit.SECONDS) }
        val owner = AtomicReference(ATTOwner(Any(), 1))
        val next = Transport().also { script(it) }
        val starts = AtomicInteger()
        val manager = ATTManager({ owner.get() }, { if (starts.getAndIncrement() == 0) old else next })
        val read = launch { assertNull(manager.readCharacteristic(ATTHandle.TRANSPARENCY)) }
        while (entered.count != 0L) kotlinx.coroutines.yield()
        read.cancelAndJoin()
        assertTrue(old.closed.await(1, TimeUnit.SECONDS))
        assertFalse(manager.available)
        owner.set(ATTOwner(Any(), 2))
        try {
            assertNotNull(manager.readCharacteristic(ATTHandle.TRANSPARENCY))
            val token = manager.sessionToken()
            owner.set(ATTOwner(Any(), 3))
            assertFalse(manager.ownsSession(token))
            assertFalse(manager.writeCharacteristic(ATTHandle.TRANSPARENCY, transparency(), transparency()) { true })
            assertFalse(next.requests.any { it[0] in byteArrayOf(0x12, 0x52) })
        } finally { manager.disconnect() }
    }

    @Test fun admissionChangingDuringFreshReadPreventsWrite() = runBlocking {
        val socket = Transport()
        script(socket)
        val owner = ATTOwner(Any(), 1)
        val manager = ATTManager({ owner }, { socket })
        var admitted = true
        try {
            val baseline = manager.readCharacteristic(ATTHandle.TRANSPARENCY)!!
            val base = socket.reply
            socket.reply = { request -> base(request).also { if (request[0] == 0x0a.toByte()) admitted = false } }
            assertFalse(manager.writeCharacteristic(ATTHandle.TRANSPARENCY, baseline, baseline) { admitted })
            assertFalse(socket.requests.any { it[0] in byteArrayOf(0x12, 0x52) })
        } finally { manager.disconnect() }
    }
}
