package me.kavishdevar.librepods.dongle

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DongleProtocolTest {
    private fun status(generation: Long = 3) = DongleProtocol.header(2).also {
        it[7] = 3; DongleProtocol.put32(it, 8, generation)
        it[12] = 1; it[19] = 91; it[20] = 92; it[21] = -1; it[42] = 0x1f
    }
    @Test fun fragmented_and_coalesced_frames_keep_boundaries() {
        val response = byteArrayOf(65, 80, 68, 71, 1, 0x81.toByte(), 2, 64) + status()
        val bytes = ByteArrayInputStream(response + response)
        val fragmented = object : InputStream() {
            override fun read() = bytes.read()
            override fun read(target: ByteArray, offset: Int, length: Int) = bytes.read(target, offset, minOf(length, 1))
        }
        val output = ByteArrayOutputStream()
        repeat(2) { assertEquals(3L, DongleProtocol.status(DongleProtocol.exchange(fragmented, output, 1, 2, byteArrayOf())).generation) }
        assertEquals(16, output.size())
        assertEquals(-1, bytes.read())
    }
    @Test fun malformed_wrong_report_and_truncated_frames_are_rejected() {
        val good = byteArrayOf(65, 80, 68, 71, 1, 0x81.toByte(), 2, 64) + status()
        for (at in listOf(0, 4, 5, 6, 7)) {
            val bad = good.copyOf().also { it[at] = 0 }
            assertThrows(Exception::class.java) { DongleProtocol.exchange(ByteArrayInputStream(bad), ByteArrayOutputStream(), 1, 2, byteArrayOf()) }
        }
        for (length in listOf(0, 7, 8, 71)) assertThrows(IOException::class.java) {
            DongleProtocol.exchange(ByteArrayInputStream(good.copyOf(length)), ByteArrayOutputStream(), 1, 2, byteArrayOf())
        }
    }
    @Test fun status_unknown_battery_and_reserved_fields() {
        val parsed = DongleProtocol.status(status())
        assertEquals(listOf(91, 92, null), parsed.batteries)
        for ((at, value) in listOf(19 to 101, 23 to 4, 40 to 7, 41 to 5, 63 to 1))
            assertThrows(IllegalArgumentException::class.java) { DongleProtocol.status(status().also { it[at] = value.toByte() }) }
    }
    @Test fun case_batteries_retain_independent_ages_and_absence_without_becoming_a_connection() {
        val bytes = status().also {
            it[7] = 0; it[21] = 61; it[22] = 2; it[42] = 0x5f
            it[44] = 6; it[45] = 7
            DongleProtocol.put32(it, 24, 12_000)
            DongleProtocol.put32(it, 28, 100)
            DongleProtocol.put32(it, 32, 89_999)
        }
        val parsed = DongleProtocol.status(bytes)
        assertEquals(listOf(91, 92, 61), parsed.batteries)
        assertEquals(listOf(12_000L, 100L, 89_999L), parsed.batteryAgeMs)
        assertEquals(7, parsed.batteryCaseSource)
        assertFalse(parsed.connected); assertFalse(parsed.controlsReady)
        assertFalse(parsed.batteryLive(0)); assertFalse(parsed.batteryCharging(0))
        assertTrue(parsed.batteryLive(1)); assertTrue(parsed.batteryCharging(1))
        assertTrue(parsed.batteryLive(2)); assertFalse(parsed.batteryLive(2, 1))
        assertFalse(parsed.batteryCharging(1, 90_000)); assertFalse(parsed.batteryLive(1, Long.MAX_VALUE))
        assertTrue(parsed.sameConnection(DongleProtocol.status(status())))
        assertEquals(parsed.batteryAgeMs, DongleProtocol.status(bytes).batteryAgeMs)
    }
    @Test fun battery_observation_extension_is_capability_gated_and_validated() {
        val good = status().also { it[42] = 0x5f; it[44] = 3; it[45] = 7 }
        DongleProtocol.status(good)
        for ((at, value) in listOf(42 to 0x1f, 44 to 8, 45 to 8, 46 to 1, 22 to 4, 44 to 7))
            assertThrows(IllegalArgumentException::class.java) {
                DongleProtocol.status(good.copyOf().also { it[at] = value.toByte() })
            }
        assertThrows(IllegalArgumentException::class.java) {
            DongleProtocol.status(good.copyOf().also { DongleProtocol.put32(it, 24, 0xffff_ffffL) })
        }
        val legacy = DongleProtocol.status(status())
        assertEquals(3, legacy.batteryPresent); assertEquals(0, legacy.batteryCaseSource)
        assertTrue(legacy.batteryLive(0))
    }
    @Test fun busy_transport_error_is_complete_and_distinct_from_a_broken_stream() {
        val response = byteArrayOf(65, 80, 68, 71, 1, -1, 3, 1, 2)
        assertThrows(DongleBusyException::class.java) {
            DongleProtocol.exchange(ByteArrayInputStream(response), ByteArrayOutputStream(), 1, 3, byteArrayOf())
        }
    }
    @Test fun settings_are_device_revisions_not_requests() {
        val bytes = ByteArray(15).also {
            it[0] = 1; it[1] = 1; it[2] = 0x28
            DongleProtocol.put32(it, 3, 500); DongleProtocol.put32(it, 7, 4); DongleProtocol.put32(it, 11, 1)
        }
        assertEquals(DongleProtocol.Setting(1, 500, 4), DongleProtocol.settings(bytes)[0x28])
        assertThrows(IllegalArgumentException::class.java) { DongleProtocol.settings(bytes.copyOf(14)) }
        assertThrows(IllegalArgumentException::class.java) { DongleProtocol.settings(bytes.copyOf().also { DongleProtocol.put32(it, 7, 0) }) }
        assertThrows(IllegalArgumentException::class.java) { DongleProtocol.settings(byteArrayOf(1, 2) + bytes.copyOfRange(2, 15) + bytes.copyOfRange(2, 15)) }
    }
    @Test fun nearby_identity_is_not_a_distance_or_anonymous_fallback() {
        val bytes = ByteArray(24).also {
            it[0] = 1; it[1] = 0x82.toByte(); it[2] = -52; it[3] = 1
            DongleProtocol.put32(it, 4, 900); DongleProtocol.put32(it, 16, 1)
            DongleProtocol.put32(it, 20, (-53 * 256).toLong() and 0xffff_ffffL)
        }
        val value = DongleProtocol.nearby(bytes)
        assertTrue(value.identityAvailable); assertEquals(2, value.state); assertEquals(-53.0, value.smoothedRssi!!, 0.0)
        assertThrows(IllegalArgumentException::class.java) { DongleProtocol.nearby(bytes.copyOf().also { it[1] = 0x88.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { DongleProtocol.nearby(bytes.copyOf().also { it[1] = 2 }) }
        assertThrows(IllegalArgumentException::class.java) { DongleProtocol.nearby(bytes.copyOf().also { it[1] = 0 }) }
        assertThrows(IllegalArgumentException::class.java) { DongleProtocol.nearby(bytes.copyOf().also { it[2] = -127 }) }
        assertThrows(IllegalArgumentException::class.java) { DongleProtocol.nearby(bytes.copyOf().also {
            DongleProtocol.put32(it, 20, (-127 * 256).toLong() and 0xffff_ffffL) }) }
        val unavailable = bytes.copyOf().also { it[1] = 0; it[2] = 127; DongleProtocol.put32(it, 20, 0x8000_0000) }
        assertFalse(DongleProtocol.nearby(unavailable).identityAvailable)
    }
    @Test fun impossible_performance_counters_are_rejected_but_unknown_values_survive() {
        val bytes = ByteArray(64).also { it[0] = 1
            DongleProtocol.put32(it, 12, 4096); DongleProtocol.put32(it, 48, 10000) }
        DongleProtocol.performance(bytes)
        for ((offset, value) in listOf(8 to 4097L, 16 to 4097L, 52 to 10001L, 56 to 10001L))
            assertThrows(IllegalArgumentException::class.java) { DongleProtocol.performance(bytes.copyOf().also {
                DongleProtocol.put32(it, offset, value) }) }
        DongleProtocol.performance(bytes.copyOf().also { DongleProtocol.put32(it, 56, 0xffff_ffffL) })
    }
    @Test fun ear_ownership_requires_a_transition_and_a_successful_pause() {
        val claim = DongleEarClaim()
        assertEquals(DongleEarClaim.Action.NONE, claim.observe(1, false, true))
        assertEquals(DongleEarClaim.Action.NONE, claim.observe(1, true, true))
        assertEquals(DongleEarClaim.Action.PAUSE, claim.observe(1, false, true))
        claim.acquired(false)
        assertEquals(DongleEarClaim.Action.NONE, claim.observe(1, true, true))
        assertEquals(DongleEarClaim.Action.PAUSE, claim.observe(1, false, true))
        claim.acquired(true)
        // Repeated unchanged reports do not consume the claim, even after a long removal.
        repeat(100) { assertEquals(DongleEarClaim.Action.NONE, claim.observe(1, false, true)) }
        assertEquals(DongleEarClaim.Action.RESUME, claim.observe(1, true, true))
        assertEquals(DongleEarClaim.Action.PAUSE, claim.observe(1, false, true))
        claim.acquired(true)
        assertEquals(DongleEarClaim.Action.CANCEL, claim.observe(2, true, true))
        assertEquals(DongleEarClaim.Action.NONE, claim.observe(2, true, true))
    }
    @Test fun either_single_bud_is_ready_only_when_the_other_is_explicitly_in_its_case() {
        for (first in 0..3) for (second in 0..3) {
            val ready = listOf(first, second) in listOf(listOf(0, 0), listOf(0, 2), listOf(2, 0))
            assertEquals("Ear report $first/$second", ready, DongleProtocol.ears(ears(first, second)))
        }
        assertNull(DongleProtocol.ears(byteArrayOf()))
        assertNull(DongleProtocol.ears(ears(0, 4)))
    }
    @Test fun case_transitions_preserve_removal_pauses_and_resume_only_owned_current_claims() {
        val claim = DongleEarClaim()
        fun observe(first: Int, second: Int, generation: Long = 1) =
            claim.observe(generation, DongleProtocol.ears(ears(first, second)), true)
        assertEquals(DongleEarClaim.Action.NONE, observe(0, 0))
        assertEquals(DongleEarClaim.Action.NONE, observe(0, 2))
        assertEquals(DongleEarClaim.Action.NONE, observe(2, 0))
        assertEquals(DongleEarClaim.Action.PAUSE, observe(1, 0))
        claim.acquired(true)
        assertEquals(DongleEarClaim.Action.RESUME, observe(2, 0))
        assertEquals(DongleEarClaim.Action.PAUSE, observe(2, 2))
        claim.acquired(false)
        assertEquals(DongleEarClaim.Action.NONE, observe(0, 2))
        assertEquals(DongleEarClaim.Action.PAUSE, observe(0, 3))
        claim.acquired(true)
        assertEquals(DongleEarClaim.Action.CANCEL, observe(0, 2, generation = 2))
        assertEquals(DongleEarClaim.Action.NONE, observe(0, 2, generation = 2))
        assertEquals(DongleEarClaim.Action.PAUSE, observe(2, 2, generation = 2))
        claim.acquired(true)
        assertEquals(DongleEarClaim.Action.CANCEL, claim.observe(2, null, true))
        assertEquals(DongleEarClaim.Action.NONE, observe(2, 0, generation = 2))
    }
    private fun ears(first: Int, second: Int) = byteArrayOf(4, 0, 4, 0, 6, 0, first.toByte(), second.toByte())
    @Test fun malformed_ears_policy_change_and_radio_loss_cancel_claims() {
        for (kind in 0..2) {
            val claim = DongleEarClaim()
            claim.observe(1, true, true); claim.observe(1, false, true); claim.acquired(true)
            assertEquals(DongleEarClaim.Action.CANCEL, claim.observe(if (kind == 2) 2 else 1,
                if (kind == 0) null else false, kind != 1))
            assertFalse(claim.paused)
        }
        assertNull(DongleProtocol.ears(byteArrayOf(4, 0, 4, 0, 6, 0, 0)))
        assertNull(DongleProtocol.ears(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 4)))
    }

    private class Firmware(private val output: ByteArray = ByteArray(91) { it.toByte() }) {
        var generation = 3L
            set(value) { if (field != value) leased = false; field = value }
        var pair = 1
            set(value) { if (field != value) leased = false; field = value }
        var sequence = 0L
        var report = DongleProtocol.header(3).also { DongleProtocol.put32(it, 8, generation) }
        val received = ByteArrayOutputStream()
        var cancelled = false
        var commits = 0
        var fail = false
        private var leased = false
        private var operation = 0
        private val staged = ByteArrayOutputStream()
        private var response = output
        suspend fun exchange(operation: Int, id: Int, bytes: ByteArray): ByteArray {
            if (id == 2) return DongleProtocol.header(2).also {
                it[7] = 3; DongleProtocol.put32(it, 8, generation); it[12] = pair.toByte()
                it[19] = -1; it[20] = -1; it[21] = -1
                it[42] = 0x20
            }
            if (operation == 1) return report.copyOf()
            val command = bytes[7].toInt()
            sequence = DongleProtocol.u32(bytes, 12)
            report = DongleProtocol.header(3).also { DongleProtocol.put32(it, 8, generation); DongleProtocol.put32(it, 12, sequence) }
            when (command) {
                1, 2, 3, 7 -> {
                    this.operation = command
                    if (DongleProtocol.u16(bytes, 18) == 0) staged.reset()
                    staged.write(bytes, 22, bytes[20].toInt() and 255)
                    if (command != 7 || staged.toByteArray()[0] != 10.toByte()) {
                        received.reset(); received.write(staged.toByteArray())
                    }
                    report[21] = 1; report[18] = staged.size().toByte(); report[19] = (staged.size() shr 8).toByte()
                }
                6 -> {
                    val request = staged.toByteArray()
                    if (this.operation == 7 && request[0] == 10.toByte()) {
                        assertEquals(6, request.size)
                        when (request[1].toInt()) {
                            0 -> { assertFalse(leased); leased = true; response = byteArrayOf(1, 42, 0, 0, 0) }
                            1 -> { assertTrue(leased); assertEquals(42L, DongleProtocol.u32(request, 2)); response = byteArrayOf() }
                            2 -> { assertTrue(leased); leased = false; response = byteArrayOf() }
                            else -> error("Unknown lease action")
                        }
                        page(0)
                    } else {
                        if (this.operation in 1..2) assertTrue(leased)
                        commits++; response = output
                        if (fail) { report[21] = 4; report[22] = 7 } else page(0)
                    }
                }
                4 -> page(DongleProtocol.u16(bytes, 18))
                5 -> { cancelled = true; report[21] = 4; report[22] = 8 }
            }
            return report.copyOf()
        }
        private fun page(offset: Int) {
            report[21] = 3; report[16] = response.size.toByte(); report[17] = (response.size shr 8).toByte()
            report[18] = offset.toByte(); report[19] = (offset shr 8).toByte()
            val size = minOf(42, response.size - offset); report[20] = size.toByte()
            response.copyInto(report, 22, offset, offset + size)
        }
    }
    @Test fun staged_requests_and_paginated_responses_execute_once_and_release_owner() = runBlocking {
        val firmware = Firmware()
        val request = ByteArray(140) { (it * 2).toByte() }
        val client = DongleSession(firmware::exchange)
        val response = client.withTarget(client.refresh()) { client.transact(2, request) }
        assertArrayEquals(request, firmware.received.toByteArray())
        assertArrayEquals(ByteArray(91) { it.toByte() }, response)
        assertEquals(1, firmware.commits); assertFalse(firmware.cancelled)
    }
    @Test fun failed_writes_are_not_retried_and_client_releases_its_transaction() = runBlocking {
        val firmware = Firmware().also { it.fail = true }
        try { DongleSession(firmware::exchange).transact(7, byteArrayOf(1, 0x28, 1, 0, 0, 0)); fail() }
        catch (_: IOException) { }
        assertEquals(1, firmware.commits); assertTrue(firmware.cancelled)
    }
    @Test fun hearing_action_never_writes_an_old_profile_after_pair_or_connection_changes() = runBlocking {
        for (change in 0..2) {
            val firmware = Firmware(byteArrayOf(0x0b, 10, 11, 12))
            val client = DongleSession(firmware::exchange)
            val target = client.refresh()
            val att = DongleAtt { client.transact(1, it) }
            try {
                client.withTarget(target) {
                    val characteristic = DongleAtt.Characteristic(0x20, 0x12)
                    val baseline = att.read(characteristic)
                    if (change != 0) firmware.generation++
                    if (change != 1) firmware.pair++
                    att.write(characteristic, baseline)
                }
                fail("A changed hearing target must be rejected before the write")
            } catch (_: IllegalStateException) { }
            assertEquals(1, firmware.commits)
            assertArrayEquals(byteArrayOf(0x0a, 0x20, 0), firmware.received.toByteArray())
            // Rejection releases the action fence; a deliberate new read still works.
            client.withTarget(client.refresh()) { client.transact(1, byteArrayOf(0x0a, 0x20, 0)) }
            assertEquals(2, firmware.commits)
        }
    }
    @Test fun queued_action_rejects_a_stale_visible_target_before_any_packet_is_staged() = runBlocking {
        val firmware = Firmware()
        val client = DongleSession(firmware::exchange)
        val target = client.refresh()
        firmware.generation++
        try {
            client.withTarget(target) { client.setting(0x28, 1) }
            fail("A stale selection must not stage a request")
        } catch (_: IllegalStateException) { }
        assertEquals(0, firmware.commits)
        assertEquals(0, firmware.received.size())
    }
    @Test fun completed_codec_request_can_restart_only_the_same_pair_without_retrying() = runBlocking {
        val output = ByteArray(20).also { it[0] = 1; it[18] = 100 }
        for (request in listOf(byteArrayOf(3, 3, 0, 0, 0, 4, 0), byteArrayOf(9))) {
            val firmware = Firmware(output)
            val client = DongleSession({ op, id, bytes ->
                val result = firmware.exchange(op, id, bytes)
                if (id == 3 && op == 1 && firmware.commits > 0) firmware.generation = 4
                result
            })
            if (request[0] == 3.toByte()) assertArrayEquals(output, client.transact(7, request))
            else try { client.transact(7, request); fail() } catch (_: IllegalStateException) { }
            assertEquals(1, firmware.commits); assertEquals(request[0] != 3.toByte(), firmware.cancelled)
        }
    }
    @Test fun erased_codec_receipt_is_reconciled_read_only_and_never_replayed() = runBlocking {
        val output = ByteArray(20).also { it[0] = 1; it[1] = 3; it[3] = 1; it[15] = 1; it[18] = 100
            DongleProtocol.put32(it, 4, 174000); DongleProtocol.put32(it, 8, 173600) }
        val firmware = Firmware(output)
        val requests = mutableListOf<Byte>()
        val client = DongleSession({ op, id, bytes ->
            if (id == 3 && op == 2 && bytes[7] == 7.toByte() && DongleProtocol.u16(bytes, 18) == 0)
                requests += bytes[22]
            val result = firmware.exchange(op, id, bytes)
            if (id == 3 && op == 2 && bytes[7] == 6.toByte() && firmware.commits == 1) {
                firmware.generation = 4
                DongleProtocol.put32(firmware.report, 8, 4)
            }
            result
        })
        val profile = client.requestAudio(3, 1, 174000)
        assertEquals(listOf<Byte>(3, 9), requests)
        assertEquals(2, firmware.commits)
        assertEquals(0, profile.actualCodec)
        assertEquals(173600L, profile.actualBitrate)
        assertEquals(4L, client.status?.generation)
    }
    @Test fun uncertain_codec_request_cannot_be_confirmed_by_a_different_pair() = runBlocking {
        val output = ByteArray(20).also { it[0] = 1; it[1] = 3; it[18] = 100; DongleProtocol.put32(it, 4, 256000) }
        val firmware = Firmware(output)
        val client = DongleSession({ op, id, bytes ->
            firmware.exchange(op, id, bytes).also {
                if (id == 2 && firmware.commits > 0) it[12] = 42
            }
        })
        try { client.requestAudio(3, 0, 256000); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, firmware.commits)
    }
    @Test fun stale_codec_selection_is_rejected_without_a_write_or_reconciliation_query() = runBlocking {
        val firmware = Firmware()
        val client = DongleSession(firmware::exchange)
        val visible = client.refresh()
        firmware.generation++
        try { client.requestAudio(3, 0, 256000, visible); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, firmware.commits)
        assertEquals(0, firmware.received.size())
    }
    @Test fun codec_readback_cannot_adopt_a_new_pair_after_the_identity_check() = runBlocking {
        val output = ByteArray(20).also { it[0] = 1; it[1] = 3; it[18] = 100; DongleProtocol.put32(it, 4, 256000) }
        val firmware = Firmware(output)
        var afterCommitReads = 0
        val client = DongleSession({ op, id, bytes ->
            firmware.exchange(op, id, bytes).also {
                if (id == 2 && firmware.commits == 1 && ++afterCommitReads == 2) firmware.pair++
            }
        })
        try { client.requestAudio(3, 0, 256000); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, firmware.commits)
        assertEquals(3, firmware.received.toByteArray()[0].toInt())
    }
    @Test fun changed_generations_and_timeouts_cannot_return_a_stale_result() = runBlocking {
        var clock = 0L
        val firmware = Firmware()
        var writes = 0
        val client = DongleSession({ op, id, data ->
            val response = firmware.exchange(op, id, data)
            if (id == 3 && op == 1) DongleProtocol.put32(response, 8, 4)
            if (op == 2) writes++
            response
        }, { clock }, { clock += 1000 })
        try { client.withTarget(client.refresh()) { client.transact(2, byteArrayOf(4)) }; fail() } catch (_: IOException) { }
        assertTrue(writes <= 2); assertTrue(firmware.cancelled)
    }
    @Test fun timeout_is_bounded_and_cancels_without_a_second_write_attempt() = runBlocking {
        var clock = 0L
        val firmware = Firmware()
        var writes = 0
        val client = DongleSession({ op, id, data ->
            val response = firmware.exchange(op, id, data)
            if (id == 3 && op == 1) DongleProtocol.put32(response, 12, 0)
            if (op == 2) writes++
            response
        }, { clock }, { clock += 1000 })
        try { client.withTarget(client.refresh()) { client.transact(2, byteArrayOf(4)) }; fail() } catch (_: IOException) { }
        assertEquals(7000L, clock); assertEquals(2, writes); assertEquals(0, firmware.commits)
        assertTrue(firmware.cancelled)
    }
    @Test fun att_discovers_uuid_and_rejects_overlapping_pages() = runBlocking {
        val uuid = UUID.fromString("a4120005-95c5-4d6f-9098-0f0b41457e0a")
        val wireUuid = java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array().reversedArray()
        val replies = ArrayDeque(listOf(byteArrayOf(7, 0x12, 0, 0x30, 0), byteArrayOf(9, 21, 0x13, 0, 0x0a, 0x20, 0) + wireUuid,
            byteArrayOf(1, 8, 0x14, 0, 0x0a)))
        assertEquals(DongleAtt.Characteristic(0x20, 0x12), DongleAtt { replies.removeFirst() }.discover(uuid))
        val bad = ArrayDeque(listOf(byteArrayOf(7, 0x12, 0, 0x30, 0), byteArrayOf(9, 21, 0x13, 0, 2, 0x20, 0) + wireUuid,
            byteArrayOf(9, 21, 0x15, 0, 2, 0x22, 0) + wireUuid))
        try { DongleAtt { bad.removeFirst() }.discover(uuid); fail() } catch (_: IllegalArgumentException) { }
    }
}
