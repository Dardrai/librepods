/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import java.util.concurrent.TimeUnit

/** Root's package ContextResolver keeps the APK attribution. Query once per lease, outside its guard loop. */
internal object AtmosRootBootCount {
    const val READER_STARTUP_WAIT_MS = 3_000L
    private const val READ_TIMEOUT_NS = 2_000_000_000L

    fun read(live: () -> Boolean,
        start: () -> Process = { ProcessBuilder("/system/bin/settings", "get", "global", "boot_count")
            .redirectErrorStream(true).start() },
        nowNs: () -> Long = System::nanoTime): Int {
        check(live()) { "owner_changed" }
        val deadline = nowNs() + READ_TIMEOUT_NS
        val process = start()
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                check(live()) { "owner_changed" }
                check(nowNs() < deadline) { "boot_count_timeout" }
            }
            check(live()) { "owner_changed" }
            check(nowNs() < deadline) { "boot_count_timeout" }
            check(process.exitValue() == 0) { "boot_count_exit" }
            // An exited settings command has at most one short decimal line. Never drain arbitrary output.
            val count = process.inputStream.available()
            check(count in 1..32) { "boot_count_invalid" }
            val bytes = ByteArray(count)
            check(process.inputStream.read(bytes) == count) { "boot_count_invalid" }
            val text = String(bytes, 0, count, Charsets.US_ASCII).trim()
            check(text.matches(Regex("[0-9]{1,10}"))) { "boot_count_invalid" }
            return checkNotNull(text.toIntOrNull()?.takeIf { it >= 0 }) { "boot_count_invalid" }
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}

internal fun atmosFailureMessage(error: Throwable): String = error.message.orEmpty()
    .take(240).map { if (it.isISOControl()) ' ' else it }.joinToString("")
