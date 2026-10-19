package me.kavishdevar.librepods.shizuku

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reads native AVRCP readiness because Android hides its backing property. */
internal class BluetoothVolumeSupport(
    private val binder: () -> IBinder = {
        ShizukuBinderWrapper(checkNotNull(SystemServiceHelper.getSystemService("bluetooth_manager")))
    },
) {
    fun isEnabled(): Boolean = try {
        val dump = readDump(binder())
        dump != null && parse(dump)
    } catch (_: Exception) {
        false
    }

    private fun readDump(service: IBinder): String? {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        val (input, output) = ParcelFileDescriptor.createPipe()
        input.use {
            val accepted = output.use {
                val request = Parcel.obtain()
                try {
                    request.writeFileDescriptor(output.fileDescriptor)
                    request.writeStringArray(emptyArray())
                    service.transact(IBinder.DUMP_TRANSACTION, request, null, IBinder.FLAG_ONEWAY)
                } finally {
                    request.recycle()
                }
            }
            if (!accepted) return null
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            val poll = StructPollfd().apply {
                fd = input.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0 || Thread.currentThread().isInterrupted) return null
                poll.revents = 0
                if (Os.poll(arrayOf(poll), remaining.toInt()) == 0) return null
                val events = poll.revents.toInt()
                if (events and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0 ||
                    events and (OsConstants.POLLIN or OsConstants.POLLHUP) == 0) return null
                val count = Os.read(input.fileDescriptor, buffer, 0, buffer.size)
                if (count == 0) {
                    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                }
                if (count < 0 || bytes.size() + count > MAX_BYTES) return null
                bytes.write(buffer, 0, count)
            }
        }
    }

    internal companion object {
        private const val MAX_BYTES = 512 * 1_024
        private const val HEADING = "AVRCP Target Native Service: 1 devices"
        private val activeDevice = Regex("(?:(?:[0-9A-Fa-f]{2}|[xX]{2}):){5}(?:[0-9A-Fa-f]{2}|[xX]{2}) <Active>")
        private val currentVolume = Regex("Current Volume: ([0-9]{1,3})")

        // shortcut: Unknown dump formats disable repair; replace this when Android exposes AVRCP readiness.
        fun parse(dump: String): Boolean {
            if ('\u0000' in dump) return false
            val lines = dump.lineSequence().toList()
            val headings = lines.indices.filter { lines[it].trim().startsWith("AVRCP Target Native Service:") }
            if (headings.size != 1) return false
            val start = headings.single()
            if (lines[start].trim() != HEADING) return false
            val indentation = lines[start].indexOfFirst { !it.isWhitespace() }
            val end = (start + 1 until lines.size).firstOrNull {
                lines[it].isNotBlank() && lines[it].indexOfFirst { character -> !character.isWhitespace() } <= indentation
            } ?: return false
            val flags = lines.indices.filter { lines[it].trim().startsWith("Absolute Volume Enabled:") }
            if (flags.size != 1 || flags.single() !in start + 1 until end ||
                lines[flags.single()].trim() != "Absolute Volume Enabled: true") return false
            val active = (start + 1 until end).filter { "<Active>" in lines[it] }
            if (active.size != 1 || !activeDevice.matches(lines[active.single()].trim())) return false
            val volumes = (start + 1 until end).filter { lines[it].trim().startsWith("Current Volume:") }
            if (volumes.size != 1 || volumes.single() <= active.single()) return false
            val volume = currentVolume.matchEntire(lines[volumes.single()].trim())
                ?.groupValues?.get(1)?.toIntOrNull() ?: return false
            return volume in 0..127
        }
    }
}
