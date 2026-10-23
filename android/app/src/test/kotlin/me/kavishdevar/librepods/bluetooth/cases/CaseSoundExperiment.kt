package me.kavishdevar.librepods.bluetooth.cases

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The deadline includes waiting for the Start write. Cancellation also attempts exactly one Stop. */
internal suspend fun runCaseSoundWindow(isSafe: () -> Boolean, start: suspend () -> Unit, stop: suspend () -> Unit) {
    currentCoroutineContext().ensureActive()
    check(isSafe()) { "audio-active" }
    var issued = false
    try {
        withTimeoutOrNull(2_000) {
            currentCoroutineContext().ensureActive()
            issued = true
            start()
            while (isSafe()) delay(100)
        }
    } finally {
        if (issued) withContext(NonCancellable) { stop() }
    }
}
