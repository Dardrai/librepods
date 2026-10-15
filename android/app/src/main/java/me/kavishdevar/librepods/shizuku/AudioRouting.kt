package me.kavishdevar.librepods.shizuku

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class AudioRoutingCleanupException(cause: Throwable) : IllegalStateException("Audio resource cleanup failed", cause)

/** All non-SDK reflection stays in the shell process, where Shizuku permits it. */
internal class AudioRouting(private val context: Context) {
    val manager = AudioManager::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(context)
    private val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
    private val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
    private val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")

    class Route(
        val track: AudioTrack? = null,
        val record: AudioRecord? = null,
        private val unregister: () -> Unit = {},
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        @Synchronized override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            var failure = runCatching { track?.release() }.exceptionOrNull()
            runCatching { record?.stop() }
            runCatching { record?.release() }.exceptionOrNull()?.let { if (failure == null) failure = it }
            runCatching { unregister() }.exceptionOrNull()?.let { if (failure == null) failure = it }
            failure?.let { throw AudioRoutingCleanupException(it) }
        }
    }

    fun format(rate: Int, channels: Int, input: Boolean = false) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
        .setChannelMask(if (input) {
            if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        } else {
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        }).build()

    fun injection(rate: Int, channels: Int, targetUid: Int): Route {
        val ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            .getConstructor().newInstance()
        ruleBuilder.javaClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
            .invoke(ruleBuilder, 1) // MIX_ROLE_INJECTOR
        ruleBuilder.javaClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            .invoke(ruleBuilder, 4, targetUid) // RULE_MATCH_UID
        val rule = checkNotNull(ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder))
        return policyRoute(rule, format(rate, channels), injection = true)
    }

    fun playbackCopy(): Route {
        val builder = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            .getConstructor().newInstance()
        val add = builder.javaClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
        for (usage in intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)) {
            add.invoke(builder, AudioAttributes.Builder().setUsage(usage).build(), 1)
        }
        val rule = checkNotNull(builder.javaClass.getMethod("build").invoke(builder))
        return policyRoute(rule, format(48000, 2), injection = false)
    }

    private fun policyRoute(rule: Any, format: AudioFormat, injection: Boolean): Route {
        val mixBuilder = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            .getConstructor(ruleClass).newInstance(rule)
        mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        // Playback is copied and still rendered normally; injection uses an isolated recording mix.
        mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, if (injection) 2 else 3)
        val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)
        val builder = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            .getConstructor(Context::class.java).newInstance(context)
        builder.javaClass.getMethod("addMix", mixClass).invoke(builder, mix)
        val policy = builder.javaClass.getMethod("build").invoke(builder)
        val result = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass).invoke(manager, policy) as Int
        check(result == 0) { "Audio policy registration denied ($result)" }
        val unregister = {
            AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass).invoke(manager, policy)
            check(policyClass.getMethod("getStatus").invoke(policy) == 1) { "Audio policy is still registered" }
            Unit
        }
        try {
            if (injection) {
                val track = policyClass.getMethod("createAudioTrackSource", mixClass).invoke(policy, mix) as? AudioTrack
                if (track?.state != AudioTrack.STATE_INITIALIZED) {
                    track?.release()
                    error("Recording injection track was not initialized")
                }
                return Route(track = track, unregister = unregister)
            }
            val record = policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix) as? AudioRecord
            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                error("Playback capture stream was not initialized")
            }
            return Route(record = record, unregister = unregister)
        } catch (error: Throwable) {
            runCatching { unregister() }.exceptionOrNull()?.let { throw AudioRoutingCleanupException(it) }
            throw error
        }
    }

    fun callInjection(rate: Int, channels: Int): Route {
        check(manager.mode == AudioManager.MODE_IN_CALL || manager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            "Start a deliberate VoIP or cellular call first; LibrePods will not force call mode"
        }
        val track = AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
            .invoke(manager, format(rate, channels)) as AudioTrack
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Call injection track was not initialized")
        }
        return Route(track = track)
    }
}
