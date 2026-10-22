package me.kavishdevar.librepods.dongle

import java.util.UUID

/** UUID discovery mirrors the native NTPods parser; handles are never guessed. */
internal class DongleAtt(private val exchange: suspend (ByteArray) -> ByteArray) {
    data class Characteristic(val handle: Int, val writeOpcode: Int?)
    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
    private fun uuid(value: UUID): ByteArray = java.nio.ByteBuffer.allocate(16)
        .putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array().reversedArray()

    suspend fun discover(id: UUID): Characteristic? {
        val service = byteArrayOf(6, 1, 0, -1, -1, 0, 0x28) + uuid(UUID.fromString("7798082b-b7b7-45a6-9933-563492efe04e"))
        val range = exchange(service)
        if (absent(range, service)) return null
        require(range.size == 5 && range[0] == 7.toByte()) { "AirPods service discovery failed" }
        var start = DongleProtocol.u16(range, 1)
        val end = DongleProtocol.u16(range, 3)
        require(start > 0 && start < end)
        var previousValue = 0
        var found: Characteristic? = null
        repeat(16) {
            val request = byteArrayOf(8) + le(start) + le(end) + byteArrayOf(3, 0x28)
            val response = exchange(request)
            if (absent(response, request)) return found
            require(response.size >= 2 && response[0] == 9.toByte()) { "AirPods characteristic discovery failed" }
            val each = response[1].toInt() and 255
            require(each in setOf(7, 21) && response.size > 2 && (response.size - 2) % each == 0)
            var previous = start - 1
            for (at in 2 until response.size step each) {
                val declaration = DongleProtocol.u16(response, at)
                val handle = DongleProtocol.u16(response, at + 3)
                require(declaration > previous && declaration > previousValue && declaration <= end && handle > declaration && handle <= end)
                if (each == 21 && response.copyOfRange(at + 5, at + each).contentEquals(uuid(id))) {
                    val properties = response[at + 2].toInt()
                    require(found == null && properties and 2 != 0) { "AirPods setting is not uniquely readable" }
                    found = Characteristic(handle, when { properties and 8 != 0 -> 0x12; properties and 4 != 0 -> 0x52; else -> null })
                }
                previous = declaration
                previousValue = handle
            }
            if (previous == end) return found
            start = previous + 1
        }
        error("AirPods characteristic discovery exceeded its limit")
    }

    suspend fun read(characteristic: Characteristic): ByteArray {
        val response = exchange(byteArrayOf(0x0a) + le(characteristic.handle))
        require(response.isNotEmpty() && response[0] == 0x0b.toByte()) { "AirPods setting read failed" }
        return response.copyOfRange(1, response.size)
    }
    suspend fun read(id: UUID): Pair<Characteristic, ByteArray>? = discover(id)?.let { it to read(it) }
    suspend fun write(characteristic: Characteristic, value: ByteArray) {
        val opcode = checkNotNull(characteristic.writeOpcode) { "This AirPods setting is read-only" }
        val response = exchange(byteArrayOf(opcode.toByte()) + le(characteristic.handle) + value)
        require(if (opcode == 0x12) response.contentEquals(byteArrayOf(0x13)) else response.isEmpty()) { "AirPods setting write failed" }
    }

    private fun absent(response: ByteArray, request: ByteArray): Boolean = response.size == 5 &&
        response[0] == 1.toByte() && response[1] == request[0] && response[4] == 0x0a.toByte() &&
        DongleProtocol.u16(response, 2) in DongleProtocol.u16(request, 1)..DongleProtocol.u16(request, 3)
}

/** A new session/report gap or manual media change always retires its previous pause. */
internal class DongleEarClaim {
    private var session: Long? = null
    private var inEars: Boolean? = null
    var paused = false
        private set
    enum class Action { NONE, PAUSE, RESUME, CANCEL }
    fun observe(generation: Long, fresh: Boolean?, enabled: Boolean): Action {
        if (session != generation || fresh == null || !enabled) {
            val old = paused
            session = generation; inEars = fresh; paused = false
            return if (old) Action.CANCEL else Action.NONE
        }
        val previous = inEars
        inEars = fresh
        return when {
            previous == true && !fresh -> Action.PAUSE
            previous == false && fresh && paused -> { paused = false; Action.RESUME }
            else -> Action.NONE
        }
    }
    fun acquired(value: Boolean) { paused = value }
    fun cancel() { session = null; inEars = null; paused = false }
}
