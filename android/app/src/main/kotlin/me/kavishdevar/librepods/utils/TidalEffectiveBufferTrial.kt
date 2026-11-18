/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import java.util.WeakHashMap

/** Player-thread-only, one attempt per owned track; never holds an AudioTrack reference. */
internal class TidalEffectiveBufferTrial(
    val originalFrames: Int, private val initialUnderruns: Int, nowMs: Long,
) {
    enum class State { NEW, UNCHANGED, ACTIVE, DECLINED, RESTORED, RESTORE_FAILED, FRAMEWORK_ENLARGED }
    var state = State.NEW; private set
    private var lastPollMs = nowMs

    fun apply(setSize: (Int) -> Int, readSize: () -> Int): State {
        if (state != State.NEW) return state
        if (originalFrames <= 0 || initialUnderruns < 0 || lastPollMs < 0) {
            state = State.DECLINED; return state
        }
        if (originalFrames <= TidalBufferProbePolicy.FRAMES) { state = State.UNCHANGED; return state }
        val result = runCatching { setSize(TidalBufferProbePolicy.FRAMES) }.getOrNull()
        val actual = runCatching(readSize).getOrNull()
        if (result == TidalBufferProbePolicy.FRAMES && actual == result) state = State.ACTIVE
        else restoreFrom(actual, setSize, readSize, State.DECLINED)
        return state
    }

    fun poll(nowMs: Long, enabled: Boolean, readUnderruns: () -> Int, readSize: () -> Int,
        setSize: (Int) -> Int): State {
        if (state != State.ACTIVE) return state
        if (!enabled || nowMs < lastPollMs) return restore(setSize, readSize)
        if (nowMs - lastPollMs < 1000) return state
        lastPollMs = nowMs
        val actual = runCatching(readSize).getOrNull()
        if (actual != TidalBufferProbePolicy.FRAMES) return restoreFrom(actual, setSize, readSize)
        val underruns = runCatching(readUnderruns).getOrNull()
        if (underruns == null || underruns != initialUnderruns) return restoreFrom(actual, setSize, readSize)
        return state
    }

    fun restore(setSize: (Int) -> Int, readSize: () -> Int): State {
        if (state != State.ACTIVE) return state
        return restoreFrom(runCatching(readSize).getOrNull(), setSize, readSize)
    }

    private fun restoreFrom(actual: Int?, setSize: (Int) -> Int, readSize: () -> Int,
        unchanged: State = State.RESTORED): State {
        state = when {
            actual == null || actual <= 0 -> State.RESTORE_FAILED
            actual > originalFrames -> State.FRAMEWORK_ENLARGED
            actual == originalFrames -> unchanged
            else -> {
                // A failed setter can still mutate the limit; verify restoration once, without retrying.
                runCatching { setSize(originalFrames) }
                val restored = runCatching(readSize).getOrNull()
                when {
                    restored == originalFrames -> State.RESTORED
                    restored != null && restored > originalFrames -> State.FRAMEWORK_ENLARGED
                    else -> State.RESTORE_FAILED
                }
            }
        }
        return state
    }
}

/** Keep terminal trials until their AudioTrack identity is collected, including retained-track flushes. */
internal class TidalEffectiveBufferTrials {
    private val trials = WeakHashMap<Any, TidalEffectiveBufferTrial>()

    @Synchronized fun claim(track: Any, create: () -> TidalEffectiveBufferTrial): TidalEffectiveBufferTrial? {
        if (trials.containsKey(track)) return null
        return create().also { trials[track] = it }
    }

    @Synchronized operator fun get(track: Any): TidalEffectiveBufferTrial? = trials[track]
}

/** A synchronous configure witness; preserving eligibility never resizes or revives a retired trial. */
internal class TidalRetainedBufferScope(private val requested: Boolean, private val track: Any?,
    private val renderer: Any?, private val provider: Any?, private val trial: TidalEffectiveBufferTrial?) {
    fun permits(actualTrack: Any?, actualRenderer: Any?, actualProvider: Any?, mode: TidalBufferProbePolicy.Mode,
        compatible: Boolean, admitted: Boolean, hostQuiet: Boolean): Boolean =
        requested && mode != TidalBufferProbePolicy.Mode.NONE && compatible && admitted && hostQuiet &&
            track != null && track === actualTrack && renderer != null && renderer === actualRenderer &&
            provider != null && provider === actualProvider && (trial?.state == TidalEffectiveBufferTrial.State.ACTIVE ||
                trial?.state == TidalEffectiveBufferTrial.State.UNCHANGED && trial.originalFrames == TidalBufferProbePolicy.FRAMES)
}
