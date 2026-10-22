package me.kavishdevar.librepods.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.CodingErrorAction

/** A released pair stays held across service restarts until an explicit acquisition. */
internal class CompanionHoldStore(private val file: AtomicFile) {
    constructor(context: Context) : this(AtomicFile(File(context.noBackupFilesDir, "companion-audio-holds")))

    fun load(): Set<String> {
        val bytes = try {
            file.openRead().use { input ->
                val buffer = ByteArray(MAX_BYTES + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count <= MAX_BYTES) { "Saved audio choices exceed their limit" }
                buffer.copyOf(count)
            }
        } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return emptySet()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val values = JSONArray(text)
        require(values.length() <= MAX_PAIRS) { "Too many saved audio choices" }
        return (0 until values.length()).map { CompanionProtocol.address(values.getString(it)) }.toSet().also {
            require(it.size == values.length()) { "Duplicate saved audio choices" }
        }
    }

    fun save(addresses: Set<String>) {
        require(addresses.size <= MAX_PAIRS) { "Too many saved audio choices" }
        val normalized = addresses.map(CompanionProtocol::address).toSet()
        val bytes = JSONArray(normalized.sorted()).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        check(load() == normalized) { "Saved audio choice could not be verified" }
    }

    private companion object {
        const val MAX_BYTES = 4096
        const val MAX_PAIRS = 64
    }
}
