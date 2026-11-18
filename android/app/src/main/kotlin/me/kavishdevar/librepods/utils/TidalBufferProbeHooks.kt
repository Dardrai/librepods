/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Buffer and claim commits may be observed separately; a foreign window grants nothing. */
internal fun tidalComponentClaimForWindow(candidate: TidalComponentClaimPermit?,
    buffer: TidalBufferProbePolicy.Permit, nowMs: Long, boot: Int, uid: Int): TidalComponentClaimPermit? {
    val matching = candidate?.takeIf { it.bufferIssuedMs == buffer.issuedMs } ?: return null
    check(matching.live(buffer, nowMs, boot, uid)) { "component_claim_invalid" }
    return matching
}

/** Debug-only new-player test. A separate root permit can tag a component, never send poses. */
internal object TidalBufferProbeHooks {
    const val TAG = "LibrePodsTidalBuffer"
    const val PREFERENCES = "me.kavishdevar.librepods"

    fun permit(preferences: SharedPreferences): TidalBufferProbePolicy.Permit? = runCatching {
        val values = preferences.all
        fun long(key: String) = values[TidalBufferProbePolicy.PREFIX + key] as? Long ?: 0L
        fun int(key: String) = values[TidalBufferProbePolicy.PREFIX + key] as? Int ?: -1
        TidalBufferProbePolicy.Permit(long("issued_ms"), long("deadline_ms"), int("boot"), int("route_id"),
            values[TidalBufferProbePolicy.PREFIX + "peer"] as? String ?: "")
    }.getOrNull()

    fun claimPermit(preferences: SharedPreferences): TidalComponentClaimPermit? = runCatching {
        TidalComponentClaimPermit.fromValues(preferences.all)
    }.getOrNull()

    fun retained(preferences: SharedPreferences): Boolean = runCatching {
        preferences.getBoolean(TidalBufferProbePolicy.RETAINED_KEY, true)
    }.getOrDefault(false)

    fun observation(preferences: SharedPreferences): TidalBufferProbePolicy.Observation? = runCatching {
        val values = preferences.all; val prefix = TidalBufferProbePolicy.OBSERVE_PREFIX
        TidalBufferProbePolicy.Observation(values[prefix + "issued_ms"] as Long,
            values[prefix + "deadline_ms"] as Long, values[prefix + "boot"] as Int,
            values[prefix + "choice_epoch"] as Long)
    }.getOrNull()

    fun observationLive(preferences: SharedPreferences, value: TidalBufferProbePolicy.Observation?,
        nowMs: Long, boot: Int, uid: Int): Boolean = runCatching {
        value != null && value.live(permit(preferences), claimPermit(preferences), nowMs, boot, uid,
            preferences.getBoolean(TidalBufferProbePolicy.PREFIX + "production", false),
            preferences.getLong(TidalBufferProbePolicy.PREFIX + "choice_epoch", 0))
    }.getOrDefault(false)

    fun install(module: XposedInterface, loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37) return
        val started = AtomicBoolean()
        // Application context/package identity is available after the normal attach returns.
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        module.hook(attach).intercept { chain ->
            val result = chain.proceed()
            val context = chain.args.firstOrNull() as? Context
            if (context?.packageName == TidalBufferProbePolicy.PACKAGE && started.compareAndSet(false, true)) {
                runCatching {
                    val prefs = module.getRemotePreferences(PREFERENCES)
                    val permission = permit(prefs)
                    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                    val diagnostic = permission?.takeIf { it.live(SystemClock.elapsedRealtime(), boot) &&
                        !prefs.getBoolean(TidalBufferProbePolicy.PREFIX + "production", false) }
                    val keepSmallBuffer = retained(prefs)
                    Probe(module, loader, context, prefs, diagnostic, boot, keepSmallBuffer).install()
                }.onFailure { failure -> runCatching { module.log(Log.WARN, TAG, "unavailable: ${failure.javaClass.simpleName}") } }
            }
            result
        }
    }

    private class Probe(val module: XposedInterface, loader: ClassLoader, val context: Context,
        val prefs: SharedPreferences, val permission: TidalBufferProbePolicy.Permit?, val boot: Int,
        keepSmallBuffer: Boolean) {
        val enabled = AtomicBoolean()
        val ready = AtomicBoolean()
        val installed = AtomicBoolean()
        val observation = observation(prefs)
        val observationChoiceEpoch = AtomicLong(runCatching {
            prefs.getLong(TidalBufferProbePolicy.PREFIX + "choice_epoch", 0)
        }.getOrDefault(0))
        val observationProduction = AtomicBoolean(runCatching {
            prefs.getBoolean(TidalBufferProbePolicy.PREFIX + "production", false)
        }.getOrDefault(false))
        val observationEnabled = AtomicBoolean(observation?.windowLive(SystemClock.elapsedRealtime(), boot) == true &&
            (observationChoiceEpoch.get() == 0L || observationChoiceEpoch.get() == observation?.choiceEpoch))
        val lateRenderUntil = AtomicLong(0)
        val retainedEnabled = AtomicBoolean(keepSmallBuffer)
        val scope = TidalBufferProbeScope()
        val attributeScope = TidalBufferProbeScope()
        val binauralOwner = TidalBinauralTrackOwner()
        val requestedSinks = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
        val effectiveTrials = TidalEffectiveBufferTrials()
        val handles = mutableListOf<XposedInterface.HookHandle>()
        val renderer = Class.forName("androidx.media3.exoplayer.audio.MediaCodecAudioRenderer", false, loader)
        val base = Class.forName("androidx.media3.exoplayer.mediacodec.MediaCodecRenderer", false, loader)
        val baseRenderer = Class.forName("androidx.media3.exoplayer.BaseRenderer", false, loader)
        val sink = Class.forName("androidx.media3.exoplayer.audio.DefaultAudioSink", false, loader)
        val sinkInterface = Class.forName("androidx.media3.exoplayer.audio.AudioSink", false, loader)
        val provider = Class.forName("androidx.media3.exoplayer.audio.DefaultAudioTrackProvider", false, loader)
        val providerInterface = Class.forName("androidx.media3.exoplayer.audio.DefaultAudioSink\$AudioTrackProvider", false, loader)
        val configurationType = Class.forName("androidx.media3.exoplayer.audio.DefaultAudioSink\$Configuration", false, loader)
        val mediaAttributes = Class.forName("androidx.media3.common.AudioAttributes", false, loader)
        val format = Class.forName("androidx.media3.common.Format", false, loader)
        val codec = Class.forName("androidx.media3.exoplayer.mediacodec.MediaCodecInfo", false, loader)
        val counters = Class.forName("androidx.media3.exoplayer.DecoderCounters", false, loader)
        val adapter = Class.forName("androidx.media3.exoplayer.mediacodec.MediaCodecAdapter", false, loader)
        val asyncAdapter = Class.forName("androidx.media3.exoplayer.mediacodec.AsynchronousMediaCodecAdapter", false, loader)
        val syncAdapter = Class.forName("androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter", false, loader)
        val periodId = Class.forName("androidx.media3.exoplayer.source.MediaSource\$MediaPeriodId", false, loader)
        val int = Int::class.javaPrimitiveType!!
        val bool = Boolean::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        fun field(type: Class<*>, name: String, expected: Class<*>): Field =
            type.getDeclaredField(name).also { require(it.type == expected); it.isAccessible = true }
        fun method(type: Class<*>, name: String, result: Class<*>, vararg args: Class<*>): Method =
            exactEldMethod(type, name, result, args.toList())
        val rendererSink = field(renderer, "audioSink", sinkInterface)
        val trackProvider = field(sink, "audioTrackProvider", providerInterface)
        val configuration = field(sink, "configuration", configurationType)
        val outputMode = field(configurationType, "outputMode", int)
        val outputEncoding = field(configurationType, "outputEncoding", int)
        val outputRate = field(configurationType, "outputSampleRate", int)
        val outputChannels = field(configurationType, "outputChannelConfig", int)
        val track = field(sink, "audioTrack", AudioTrack::class.java)
        val floatEnabled = field(sink, "enableFloatOutput", bool)
        val tunneling = field(sink, "tunneling", bool)
        val codecName = field(codec, "name", String::class.java)
        val mime = field(format, "sampleMimeType", String::class.java)
        val encoding = field(format, "pcmEncoding", int)
        val channels = field(format, "channelCount", int)
        val rate = field(format, "sampleRate", int)
        val codecInfo = method(base, "getCodecInfo", codec)
        val currentCodec = method(base, "getCodec", adapter)
        val asyncCodec = field(asyncAdapter, "codec", MediaCodec::class.java)
        val syncCodec = field(syncAdapter, "codec", MediaCodec::class.java)
        val decoderCounters = field(base, "decoderCounters", counters)
        val queuedCount = field(counters, "queuedInputBufferCount", int)
        val inputHighWater = field(base, "largestQueuedPresentationTimeUs", long)
        val feed = method(base, "feedInputBuffer", bool)
        val render = method(base, "render", Void.TYPE, long, long)
        val inputOffset = method(baseRenderer, "getStreamOffsetUs", long)
        val outputOffset = method(base, "getOutputStreamOffsetUs", long)
        val rendererStarted = field(renderer, "isStarted", bool)
        val drainState = field(base, "codecDrainState", int)
        val inputEnded = field(base, "inputStreamEnded", bool)
        val outputEnded = field(base, "outputStreamEnded", bool)
        val pendingEnd = field(base, "pendingOutputEndOfStream", bool)
        val adaptation = field(base, "codecNeedsAdaptationWorkaroundBuffer", bool)
        val reconfiguration = field(base, "codecReconfigurationState", int)
        val output = method(renderer, "processOutputBuffer", bool, long, long, adapter,
            java.nio.ByteBuffer::class.java, int, int, int, long, bool, bool, format)
        val codecReset = method(base, "resetCodecStateForFlush", Void.TYPE)
        val positionReset = method(renderer, "onPositionReset", Void.TYPE, long, bool)
        val streamChange = method(base, "onStreamChanged", Void.TYPE,
            java.lang.reflect.Array.newInstance(format, 0).javaClass, long, long, periodId)
        val outputFormat = method(renderer, "onOutputFormatChanged", Void.TYPE, format, android.media.MediaFormat::class.java)
        val configure = method(sink, "configure", Void.TYPE, format, int, IntArray::class.java)
        val initialize = method(sink, "initializeAudioTrack", bool)
        val trackAttributes = method(provider, "getAudioTrackAttributesV21", android.media.AudioAttributes::class.java,
            mediaAttributes, bool)
        val buffer = method(sink, "handleBuffer", bool, java.nio.ByteBuffer::class.java, Long::class.javaPrimitiveType!!, int)
        val flush = method(sink, "flush", Void.TYPE)
        val written = method(sink, "getWrittenFrames", Long::class.javaPrimitiveType!!)
        var appliedSink: Any? = null // Accessed only on the player's thread.
        var appliedTrack: AudioTrack? = null
        var lastReadMs = 0L
        var firstUnderruns: Int? = null
        var reads = 0
        var lastLeadReadMs = 0L
        var leadReads = 0
        val lead = TidalBufferProbeLead()
        val claim = TidalBufferProbeClaim()
        val selectedRenderer = AtomicReference<Any?>()
        val componentPermission = AtomicReference<TidalComponentClaimPermit?>()
        val componentBuffer = AtomicReference<TidalBufferProbePolicy.Permit?>(permission)
        val componentRetired = AtomicBoolean()
        val lastComponentGrant = AtomicReference<TidalComponentClaimPermit?>()
        val handoffReady = AtomicReference<String?>()
        class ComponentOwner(renderer: Any, sink: Any, codec: MediaCodec, val handler: Handler, val epoch: Long) {
            val renderer = WeakReference(renderer); val sink = WeakReference(sink); val codec = WeakReference(codec)
        }
        val componentOwner = AtomicReference<ComponentOwner?>()
        val lastGrantedOwner = AtomicReference<ComponentOwner?>()
        val componentLoopers = WeakHashMap<MediaCodec, Looper>()
        val componentEpoch = AtomicLong()
        val inputGate = TidalDecoderInputLeadGate()
        data class InputScope(val owner: ComponentOwner, val grant: TidalComponentClaimPermit,
            val track: AudioTrack, val late: Boolean, val window: TidalDecoderInputLeadGate.Window?)
        val inputScope = ThreadLocal<InputScope?>()
        val lateOutput = TidalLateRenderReserve()
        var lateLogged = false
        var playbackProbeMs = -1L
        var playbackProbeFrame = -1L
        var playbackProbeTrack: AudioTrack? = null
        val playbackTimestamp = AudioTimestamp()
        var observedOwner: ComponentOwner? = null // Accessed only on the player's thread.
        val observedLead = TidalBufferProbeLead()
        var lastObservedMs = -1L
        var observationReads = 0
        val componentWorker = ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS, ArrayBlockingQueue(4),
            { action -> Thread(action, "atmos-claim-prefs").apply { isDaemon = true } })
        val refreshQueued = AtomicBoolean()
        val componentClaims = TidalComponentClaims<ComponentOwner>(
            post = { owner, action -> runCatching { owner.handler.post { action() } }.getOrDefault(false) },
            valid = { owner, grant -> componentCurrent() && componentOwner.get() === owner &&
                componentBuffer.get()?.let { grant.live(it, SystemClock.elapsedRealtime(), boot, Process.myUid()) } == true && validComponent(owner, requireTrack = true) &&
                (grant.resumeFromNonce == null || grant.matchesResume(lastComponentGrant.get(), Process.myPid(),
                    (track.get(owner.sink.get()) as? AudioTrack)?.audioSessionId ?: -1, owner === lastGrantedOwner.get())) },
            send = { owner, nonce -> checkNotNull(owner.codec.get()).setParameters(
                Bundle().apply { putString(TidalComponentClaimPermit.PARAMETER, nonce) }) },
            report = ::log, accepted = { owner, grant -> lastGrantedOwner.set(owner); lastComponentGrant.set(grant) })
        var monitoring = true
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TidalBufferProbePolicy.LATE_RENDER_KEY) readLateRenderRequest()
            if (key == null || key.startsWith(TidalBufferProbePolicy.PREFIX)) {
                observationChoiceEpoch.set(runCatching { prefs.getLong(TidalBufferProbePolicy.PREFIX + "choice_epoch", 0) }.getOrDefault(0))
                observationProduction.set(runCatching { prefs.getBoolean(TidalBufferProbePolicy.PREFIX + "production", false) }.getOrDefault(false))
            }
            if (observationEnabled.get() && (key == null || key.startsWith(TidalBufferProbePolicy.PREFIX) ||
                    key.startsWith(TidalComponentClaimPermit.PREFIX)) &&
                (observation(prefs) != observation || observation?.windowLive(SystemClock.elapsedRealtime(), boot) != true ||
                    observationChoiceEpoch.get().let { it > 0 && it != observation?.choiceEpoch })) observationEnabled.set(false)
            if (key?.startsWith(TidalBufferProbePolicy.OBSERVE_PREFIX) == true) return@OnSharedPreferenceChangeListener
            // Enabling requires a fresh process; revocation cannot be overwritten by a stale startup read.
            if ((key == null || key == TidalBufferProbePolicy.RETAINED_KEY) && !retained(prefs)) retainedEnabled.set(false)
            if (key == null || key.startsWith(TidalBufferProbePolicy.PREFIX)) {
                if (permit(prefs) != permission && enabled.getAndSet(false))
                    log("disarmed reason=preferences_changed existing_track_requires_teardown=true")
                refreshComponentPermit()
            } else if (key.startsWith(TidalComponentClaimPermit.PREFIX)) refreshComponentPermit()
        }
        fun log(message: String) { runCatching { Log.i(TAG, message) } }
        fun disarm(reason: String) {
            if (TidalBufferProbePolicy.retireComponentOnDisarm(reason) && componentBuffer.get() == permission) retireComponent()
            if (enabled.getAndSet(false)) log("disarmed reason=$reason existing_track_requires_teardown=true")
        }
        fun retireComponent() {
            if (componentRetired.getAndSet(true)) return
            componentClaims.end()
        }
        fun componentCurrent() = !componentRetired.get() &&
            componentBuffer.get()?.live(SystemClock.elapsedRealtime(), boot) == true
        fun componentTask(action: () -> Unit) {
            runCatching { componentWorker.execute { runCatching(action).onFailure { retireComponent(); log("component_preferences_failed") } } }
                .onFailure { retireComponent(); log("component_preferences_dispatch_failed") }
        }
        fun refreshComponentPermit() {
            if (!refreshQueued.compareAndSet(false, true)) return
            componentTask {
                refreshQueued.set(false)
                val now = SystemClock.elapsedRealtime()
                val provided = permit(prefs)
                val production = prefs.getBoolean(TidalBufferProbePolicy.PREFIX + "production", false) &&
                    prefs.getLong(TidalBufferProbePolicy.PREFIX + "choice_epoch", 0) > 0
                val buffer = provided?.takeIf { it.live(now, boot) && (production || it == permission) }
                if (buffer != componentBuffer.get()) {
                    componentBuffer.set(buffer)
                    componentClaims.renew(componentOwner.get())
                    componentPermission.set(null); lastComponentGrant.set(null); lastGrantedOwner.set(null); handoffReady.set(null)
                    componentRetired.set(false)
                }
                if (buffer == null || componentRetired.get()) { componentClaims.permit(null); return@componentTask }
                val next = tidalComponentClaimForWindow(claimPermit(prefs), buffer, now, boot, Process.myUid())
                val previous = lastComponentGrant.get()
                if (next != null && (next != componentPermission.get() && previous != null && next.nonce != previous.nonce &&
                    !(next.resumeFromNonce == previous.nonce && next.resumePid == Process.myPid()) &&
                    !handoffReady.compareAndSet(previous.nonce, null))) { retireComponent(); return@componentTask }
                componentPermission.set(next)
                componentClaims.permit(next)
            }
        }
        fun validComponent(owner: ComponentOwner, requireTrack: Boolean = false): Boolean = runCatching {
            val renderer = owner.renderer.get() ?: return false
            val sink = owner.sink.get() ?: return false
            val codec = owner.codec.get() ?: return false
            if (Looper.myLooper() !== owner.handler.looper || rendererSink.get(renderer) !== sink ||
                actualCodec(renderer) !== codec || codec.name != "c2.pixel.eac3.float.decoder" ||
                tunneling.getBoolean(sink) || !floatEnabled.getBoolean(sink)) return false
            val config = configuration.get(sink) ?: return false
            if (outputMode.getInt(config) != 0 || outputEncoding.getInt(config) != AudioFormat.ENCODING_PCM_FLOAT ||
                outputRate.getInt(config) != 48000 || outputChannels.getInt(config) != AudioFormat.CHANNEL_OUT_STEREO ||
                trackProvider.get(sink)?.javaClass != provider) return false
            val actual = track.get(sink) as? AudioTrack
            if (requireTrack && actual == null) return false
            actual == null || TidalBufferProbePolicy.bufferResult(actual.bufferSizeInFrames, actual.bufferCapacityInFrames,
                actual.startThresholdInFrames, actual.sampleRate, actual.channelCount, actual.audioFormat) != TidalBufferProbePolicy.BufferResult.INVALID
        }.getOrDefault(false)
        fun rememberComponent(target: Any, owner: Any, actual: MediaCodec) {
            val looper = Looper.myLooper() ?: return
            val sameLooper = synchronized(componentLoopers) {
                val previous = componentLoopers[actual]
                if (previous != null && previous !== looper) false
                else { componentLoopers[actual] = looper; true }
            }
            if (!sameLooper) { retireComponent(); return }
            val captured = ComponentOwner(owner, target, actual, Handler(looper), componentEpoch.incrementAndGet())
            val selected = componentClaims.selectOwner(captured) { old, next ->
                old.renderer.get() === next.renderer.get() && old.sink.get() === next.sink.get() &&
                    old.codec.get() === next.codec.get() && old.handler.looper === next.handler.looper && validComponent(old)
            }
            componentOwner.set(selected)
        }
        fun actualCodec(owner: Any): MediaCodec? {
            val wrapper = currentCodec.invoke(owner) ?: return null
            return when (wrapper.javaClass) {
                asyncAdapter -> asyncCodec.get(wrapper)
                syncAdapter -> syncCodec.get(wrapper)
                else -> null
            } as? MediaCodec
        }

        fun binauralProvider(target: Any?): Any? = runCatching {
            val owner = binauralOwner.get(target) ?: return null
            if (rendererSink.get(owner.renderer) !== target || actualCodec(owner.renderer) !== owner.codec ||
                track.get(target) != null || tunneling.getBoolean(target) || !floatEnabled.getBoolean(target)) return null
            val config = configuration.get(target) ?: return null
            if (outputMode.getInt(config) != 0 || outputEncoding.getInt(config) != AudioFormat.ENCODING_PCM_FLOAT ||
                outputRate.getInt(config) != 48000 || outputChannels.getInt(config) != AudioFormat.CHANNEL_OUT_STEREO) return null
            trackProvider.get(target)?.takeIf { it.javaClass == provider }
        }.getOrNull()
        // The immutable permit's route/boot/window were validated at installation.
        fun current(): Boolean = permission != null && ready.get() && enabled.get() &&
            SystemClock.elapsedRealtime() in permission.issuedMs until permission.deadlineMs
        fun retainedCurrent(): Boolean = installed.get() && retainedEnabled.get()
        fun observing(owner: Any?): Boolean = owner != null && owner === selectedRenderer.get() &&
            appliedSink != null && monitoring && current()
        fun readLateRenderRequest() {
            lateRenderUntil.set(runCatching {
                prefs.getLong(TidalBufferProbePolicy.LATE_RENDER_KEY, 0)
            }.getOrDefault(0))
        }
        fun lateCurrent(now: Long, actual: AudioTrack): Boolean {
            val observing = observationEnabled.get() && observation?.windowLive(now, boot) == true
            return TidalBufferProbePolicy.paceRetainedTrack(retainedCurrent(),
                observationProduction.get() && observationChoiceEpoch.get() > 0, lateOutput.failed(actual), observing,
                observing && lateRenderUntil.get() in (now + 1)..checkNotNull(observation).deadlineMs)
        }

        fun inputOwnerCurrent(value: InputScope): Boolean = componentCurrent() &&
            (if (value.late) lateCurrent(SystemClock.elapsedRealtime(), value.track) && requestedSinks[value.owner.sink.get()] == true
             else observing(value.owner.renderer.get())) &&
            componentOwner.get() === value.owner && componentPermission.get() == value.grant &&
            componentClaims.granted(value.owner) == value.grant &&
            componentBuffer.get()?.let { value.grant.live(it, SystemClock.elapsedRealtime(), boot, Process.myUid()) } == true &&
            Looper.myLooper() === value.owner.handler.looper &&
            rendererSink.get(value.owner.renderer.get()) === value.owner.sink.get() &&
            actualCodec(checkNotNull(value.owner.renderer.get())) === value.owner.codec.get() &&
            track.get(value.owner.sink.get()) === value.track

        fun lateReserve(owner: ComponentOwner, actual: AudioTrack, now: Long): Boolean {
            if (!lateCurrent(now, actual) || actual.playState != AudioTrack.PLAYSTATE_PLAYING) return false
            if (!lateOutput.owns(actual)) lateLogged = false
            return lateOutput.check(actual, now, read = {
                val underruns = actual.underrunCount
                val frames = actual.bufferSizeInFrames
                val ready = if (frames == TidalBufferProbePolicy.FRAMES) {
                    val writtenFrames = written.invoke(owner.sink.get()) as Long
                    val played = actual.playbackHeadPosition.toLong() and 0xffffffffL
                    (writtenFrames - played).coerceIn(0, frames.toLong())
                } else null
                TidalLateRenderReserve.Sample(underruns, ready)
            }, onTrip = { log("late_render_fallback=underrun_change delta=$it") })
        }
        fun retireTiming(reason: String) { stopMonitoring(); disarm(reason) }
        fun stopMonitoring() {
            monitoring = false
            ready.set(false)
            // shortcut: timing and input gating cover the first track; rearm only after per-track lead validation.
            // The same listener must still revoke retained buffering on later tracks.
        }

        fun resetObservation() {
            observedOwner = null; observedLead.resetObservation()
        }

        fun observationCurrent(nowMs: Long): Boolean = observationEnabled.get() && observation?.live(
            componentBuffer.get(), componentPermission.get(), nowMs, boot, Process.myUid(),
            production = observationProduction.get(), currentChoiceEpoch = observationChoiceEpoch.get()) == true

        fun recordPlaybackBlock(sink: Any, actual: AudioTrack, frame: Long, bytesWritten: Int, ptsUs: Long) {
            val count = written.invoke(sink) as Long
            if (track.get(sink) !== actual || actual.playState != AudioTrack.PLAYSTATE_PLAYING ||
                count <= frame || count - frame > 1536 || bytesWritten.toLong() != (count - frame) * 8 ||
                count > 0xffffffffL || actual.playbackParams.speed != 1f) return
            playbackProbeMs = SystemClock.elapsedRealtime()
            if (!actual.getTimestamp(playbackTimestamp)) return
            val before = System.nanoTime()
            val bootNs = SystemClock.elapsedRealtimeNanos()
            val after = System.nanoTime()
            if (after - before !in 0..100_000 || after - playbackTimestamp.nanoTime !in -500_000_000..1_000_000_000) return
            if (actual !== playbackProbeTrack) { playbackProbeTrack = actual; playbackProbeFrame = -1L }
            val position = playbackTimestamp.framePosition and 0xffffffffL
            if (position <= playbackProbeFrame || position > count || frame - position !in -4800..12000) return
            playbackProbeFrame = position
            val due = playbackTimestamp.nanoTime + (bootNs - before - (after - before) / 2) +
                (frame - position) * 1_000_000_000 / 48000
            log("late_playout pts_us=$ptsUs frame=$frame due_ns=$due observed_ns=$bootNs " +
                "pid=${Process.myPid()} session=${actual.audioSessionId} size=${actual.bufferSizeInFrames} " +
                "underruns=${actual.underrunCount} pacing=${lateCurrent(playbackProbeMs, actual)} blocked=${inputGate.blocked}")
        }

        fun observeLead(target: Any?, args: List<Any?>, result: Any?) {
            val now = SystemClock.elapsedRealtime()
            if (!observationCurrent(now)) { if (observedOwner != null) resetObservation(); return }
            if (target == null || componentOwner.get()?.renderer?.get() !== target) return
            if (lastObservedMs >= 0 && now < lastObservedMs) { observationEnabled.set(false); resetObservation(); return }
            if (lastObservedMs >= 0 && now - lastObservedMs < 1000 ||
                observationReads >= TidalBufferProbePolicy.MAX_READBACKS) return
            lastObservedMs = now; ++observationReads
            runCatching {
                val owner = componentOwner.get()
                val grant = owner?.let(componentClaims::granted)
                if (owner == null || grant == null || owner.renderer.get() !== target ||
                    componentPermission.get() != grant || !validComponent(owner, requireTrack = true)) {
                    resetObservation(); log("passive_lead elapsed_ms=$now sample_valid=false reason=not_owned"); return
                }
                val actual = track.get(owner.sink.get()) as? AudioTrack
                if (actual == null || actual.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    resetObservation(); log("passive_lead elapsed_ms=$now sample_valid=false reason=control_or_position"); return
                }
                observedOwner = owner
                val counters = decoderCounters.get(target)
                val count = queuedCount.getInt(counters)
                val sample = observedLead.current(owner, actual, grant, inputOffset.invoke(target) as Long,
                    outputOffset.invoke(target) as Long, args[0] as Long,
                    args[8] != false || (args[5] as Int and 6) != 0 || drainState.getInt(target) != 0 ||
                        inputEnded.getBoolean(target) || outputEnded.getBoolean(target) || pendingEnd.getBoolean(target) ||
                        adaptation.getBoolean(target) || reconfiguration.getInt(target) != 0,
                    count, inputHighWater.getLong(target), args[7] as Long)
                if (!observationCurrent(SystemClock.elapsedRealtime()) || componentOwner.get() !== owner ||
                    componentClaims.granted(owner) != grant || track.get(owner.sink.get()) !== actual) {
                    resetObservation(); return
                }
                log("passive_lead elapsed_ms=$now sample_valid=${sample != null} pid=${Process.myPid()} " +
                    "session=${actual.audioSessionId} ownership_epoch=${owner.epoch} observation_epoch=${observedLead.observationEpoch} " +
                    "buffer_issued_ms=${grant.bufferIssuedMs} queued_input_count=$count " +
                    "input_high_water_us=${sample?.inputHighWaterUs} output_pts_us=${sample?.outputUs} " +
                    "renderer_position_us=${sample?.rendererPositionUs} input_minus_output_us=${sample?.inputMinusOutputUs} " +
                    "output_minus_position_us=${sample?.outputMinusPositionUs} input_minus_position_us=${sample?.inputMinusPositionUs} " +
                    "output_accepted=$result late_render_requested=${lateCurrent(now, actual)} blocked=${inputGate.blocked} " +
                    "feed_lead_us=${inputGate.lastLeadUs} reason=${inputGate.reason} reserve_frames=${lateOutput.frames} acoustic=false")
            }.onFailure {
                resetObservation(); log("passive_lead elapsed_ms=$now sample_valid=false reason=readback_failed")
            }
        }

        fun logEffectiveBuffer(trial: TidalEffectiveBufferTrial, actual: AudioTrack, teardown: Boolean = false) {
            val size = runCatching { actual.bufferSizeInFrames }.getOrNull()
            log("effective_resize=${trial.state} original_frames=${trial.originalFrames} requested_frames=4800 " +
                "effective_frames=$size requested100ms_verified=${size == 4800 &&
                    (trial.state == TidalEffectiveBufferTrial.State.ACTIVE || trial.state == TidalEffectiveBufferTrial.State.UNCHANGED)} " +
                "teardown=$teardown acoustic=false")
        }

        fun resizeEffectiveBuffer(target: Any) {
            if (requestedSinks[target] != true || !current() && !retainedCurrent()) return
            runCatching {
                val actual = track.get(target) as? AudioTrack ?: return
                if (actual.state != AudioTrack.STATE_INITIALIZED || actual.playState != AudioTrack.PLAYSTATE_STOPPED ||
                    TidalBufferProbePolicy.bufferResult(actual.bufferSizeInFrames, actual.bufferCapacityInFrames,
                        actual.startThresholdInFrames, actual.sampleRate, actual.channelCount, actual.audioFormat) ==
                    TidalBufferProbePolicy.BufferResult.INVALID) return
                val trial = effectiveTrials.claim(actual) {
                    TidalEffectiveBufferTrial(actual.bufferSizeInFrames, actual.underrunCount,
                        SystemClock.elapsedRealtime())
                } ?: return
                trial.apply(actual::setBufferSizeInFrames, actual::getBufferSizeInFrames)
                logEffectiveBuffer(trial, actual)
            }.onFailure { log("effective_resize=unavailable existing_playback_preserved=true") }
        }

        fun pollEffectiveBuffer(target: Any) {
            runCatching {
                val actual = track.get(target) as? AudioTrack ?: return
                val trial = effectiveTrials[actual] ?: return
                val previous = trial.state
                val state = trial.poll(SystemClock.elapsedRealtime(),
                    requestedSinks[target] == true && (current() || retainedCurrent()),
                    actual::getUnderrunCount, actual::getBufferSizeInFrames, actual::setBufferSizeInFrames)
                if (state != previous) logEffectiveBuffer(trial, actual)
            }
        }

        fun restoreEffectiveBuffer(target: Any) {
            runCatching {
                val actual = track.get(target) as? AudioTrack ?: return
                // Keep its terminal weak entry so a retained identity cannot restart a failed/restored trial.
                val trial = effectiveTrials[actual] ?: return
                val previous = trial.state
                val state = trial.restore(actual::setBufferSizeInFrames, actual::getBufferSizeInFrames)
                if (state != previous) logEffectiveBuffer(trial, actual, teardown = true)
            }
        }

        fun install() {
            val info = context.packageManager.getPackageInfo(TidalBufferProbePolicy.PACKAGE, 0)
            require(info.versionName == TidalBufferProbePolicy.VERSION && info.longVersionCode == TidalBufferProbePolicy.VERSION_CODE)
            val hash = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
            }
            require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == TidalBufferProbePolicy.APK_SHA256)
            val audio = context.getSystemService(AudioManager::class.java)
            val minimum = if (permission != null)
                AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT) else 0
            if (permission != null) {
                // This is a route/attributes-blind estimate; actual creation may clamp our request.
                log("minimum_bytes=$minimum minimum_is_estimate=true requested_bytes=${TidalBufferProbePolicy.BYTES}")
                require(audio.mode == AudioManager.MODE_NORMAL)
            }
            try {
                handles += module.hook(outputFormat).intercept { chain ->
                    scope.within(chain.thisObject) { chain.proceed() }
                }
                handles += module.hook(configure).intercept { chain ->
                    val args = chain.args.toTypedArray()
                    val retainedScope = runCatching {
                        val target = chain.thisObject ?: return@runCatching null
                        val owner = binauralOwner.get(target)
                        val actual = track.get(target) as? AudioTrack
                        TidalRetainedBufferScope(requestedSinks[target] == true, actual, owner?.renderer,
                            trackProvider.get(target), actual?.let { effectiveTrials[it] })
                    }.getOrNull()
                    binauralOwner.forget(chain.thisObject)
                    requestedSinks.remove(chain.thisObject)
                    val mode = TidalBufferProbePolicy.mode(current(), retainedCurrent())
                    var componentCandidate: Any? = null
                    val proposed = runCatching {
                        val owner = scope.renderer()
                        val target = chain.thisObject
                        val value = args[0]
                        if (owner == null || target == null || value == null ||
                            rendererSink.get(owner) !== target || !sink.isInstance(target)) null
                        else {
                            val details = codecInfo.invoke(owner)
                            val candidate = TidalBufferProbePolicy.Format(details?.let { codecName.get(it) as? String },
                                mime.get(value) as? String, encoding.getInt(value), channels.getInt(value), rate.getInt(value),
                                args[1] as Int, track.get(target) != null, floatEnabled.getBoolean(target), tunneling.getBoolean(target))
                            if (TidalBufferProbePolicy.supportedComponent(Build.VERSION.SDK_INT,
                                    info.versionName, info.longVersionCode, candidate)) componentCandidate = owner
                            if (mode == TidalBufferProbePolicy.Mode.DIAGNOSTIC && candidate.codec == "c2.pixel.eac3.float.decoder") {
                                selectedRenderer.compareAndSet(null, owner)
                                if (selectedRenderer.get() !== owner) disarm("ambiguous_renderer")
                            }
                            if (mode == TidalBufferProbePolicy.Mode.DIAGNOSTIC) {
                                if (!current()) null else TidalBufferProbePolicy.bytes(permission, SystemClock.elapsedRealtime(), boot, Build.VERSION.SDK_INT,
                                    info.versionName, info.longVersionCode, candidate, minimum, claim.used())
                            } else if (mode == TidalBufferProbePolicy.Mode.RETAINED &&
                                TidalBufferProbePolicy.supported(Build.VERSION.SDK_INT, info.versionName, info.longVersionCode, candidate)) {
                                // Configuration only: no platform control queries in buffer callbacks.
                                TidalBufferProbePolicy.retainedBytes(retainedCurrent(), Build.VERSION.SDK_INT, info.versionName,
                                    info.longVersionCode, candidate, AudioTrack.getMinBufferSize(48000,
                                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT),
                                    audio.mode == AudioManager.MODE_NORMAL, audio.activeRecordingConfigurations.isEmpty())
                            } else null
                        }
                    }.getOrElse { disarm("guard_failed"); null }
                    val diagnostic = mode == TidalBufferProbePolicy.Mode.DIAGNOSTIC
                    val bytes = proposed?.takeIf { if (diagnostic) current() && claim.take() else retainedCurrent() }
                    // Consume before the call: a throwing configure must not become a retry loop.
                    if (bytes != null && diagnostic) { appliedSink = chain.thisObject; log("configured requested_frames=4800 diagnostic_only=true") }
                    try {
                        val result = tidalBufferProbeProceed(args, bytes, { chain.proceed(it) }) {
                            if (bytes != null || componentCandidate != null) runCatching {
                                val owner = scope.renderer() ?: return@runCatching
                                val actual = actualCodec(owner) ?: return@runCatching
                                if (actual.name == "c2.pixel.eac3.float.decoder") {
                                    val target = checkNotNull(chain.thisObject)
                                    binauralOwner.remember(target, owner, actual)
                                    if (bytes != null) requestedSinks[target] = true
                                    else runCatching {
                                        val actualTrack = track.get(target) as? AudioTrack
                                        val config = configuration.get(target)
                                        val compatible = componentCandidate === owner && actualTrack != null && config != null &&
                                            actualTrack.state == AudioTrack.STATE_INITIALIZED &&
                                            !tunneling.getBoolean(target) && floatEnabled.getBoolean(target) &&
                                            outputMode.getInt(config) == 0 && outputEncoding.getInt(config) == AudioFormat.ENCODING_PCM_FLOAT &&
                                            outputRate.getInt(config) == 48000 && outputChannels.getInt(config) == AudioFormat.CHANNEL_OUT_STEREO &&
                                            trackProvider.get(target)?.javaClass == provider &&
                                            TidalBufferProbePolicy.bufferResult(actualTrack.bufferSizeInFrames, actualTrack.bufferCapacityInFrames,
                                                actualTrack.startThresholdInFrames, actualTrack.sampleRate, actualTrack.channelCount,
                                                actualTrack.audioFormat) != TidalBufferProbePolicy.BufferResult.INVALID
                                        if (retainedScope?.permits(actualTrack, owner, trackProvider.get(target), mode,
                                                compatible, current() || retainedCurrent(), audio.mode == AudioManager.MODE_NORMAL &&
                                                    audio.activeRecordingConfigurations.isEmpty()) == true)
                                            requestedSinks[target] = true
                                    }
                                    if (componentCandidate === owner) rememberComponent(target, owner, actual)
                                }
                            }
                            componentOwner.get()?.takeIf { it.sink.get() === chain.thisObject }?.let { old ->
                                if (scope.renderer() !== old.renderer.get() || !validComponent(old)) {
                                    if (componentOwner.compareAndSet(old, null)) componentClaims.owner(null)
                                } else if (componentCurrent()) runCatching {
                                    binauralOwner.remember(chain.thisObject!!, checkNotNull(old.renderer.get()), checkNotNull(old.codec.get()))
                                }
                            }
                        }
                        result
                    }
                    catch (failure: Throwable) {
                        if (componentOwner.get()?.sink?.get() === chain.thisObject) retireComponent()
                        if (bytes != null && diagnostic) disarm("configure_failed")
                        throw failure
                    }
                }
                handles += module.hook(initialize).intercept { chain ->
                    val ownedProvider = binauralProvider(chain.thisObject)
                    attributeScope.within(ownedProvider) {
                        val result = chain.proceed()
                        if (result == true && ownedProvider != null && chain.thisObject != null)
                            resizeEffectiveBuffer(chain.thisObject!!)
                        if (ready.get() && result == true && chain.thisObject === appliedSink) observe("created", chain.thisObject!!)
                        else if (result == true && ownedProvider != null) runCatching {
                            val audioTrack = checkNotNull(track.get(chain.thisObject) as? AudioTrack)
                            val size = audioTrack.bufferSizeInFrames
                            val capacity = audioTrack.bufferCapacityInFrames
                            val threshold = audioTrack.startThresholdInFrames
                            val state = TidalBufferProbePolicy.bufferResult(size, capacity, threshold,
                                audioTrack.sampleRate, audioTrack.channelCount, audioTrack.audioFormat)
                            log("retained_created effective_frames=$size capacity_frames=$capacity start_threshold=$threshold " +
                                "buffer_result=$state requested100ms_verified=${state.effective100Ms} acoustic=false")
                            if (state == TidalBufferProbePolicy.BufferResult.INVALID) retireComponent()
                        }.onFailure { retireComponent(); log("retained_created readback_failed=true requested100ms_verified=false") }
                        if (result == true) componentOwner.get()?.takeIf {
                            it.sink.get() === chain.thisObject && componentCurrent() && validComponent(it, requireTrack = true)
                        }?.let(componentClaims::retryOwner)
                        result
                    }
                }
                handles += module.hook(trackAttributes).intercept { chain ->
                    val result = chain.proceed()
                    val original = result as? android.media.AudioAttributes
                    if (original != null && chain.args[1] == false)
                        runCatching { tidalBinauralAttributes(original, attributeScope.renderer(), chain.thisObject, false) }
                            .getOrDefault(original)
                    else result
                }
                handles += module.hook(buffer).intercept { chain ->
                    chain.thisObject?.let(::pollEffectiveBuffer)
                    val block = chain.args[0] as? java.nio.ByteBuffer
                    val now = SystemClock.elapsedRealtime()
                    val probe = if (block?.position() == 0 && block.remaining() == 1536 * 8 &&
                        now - playbackProbeMs >= 1000 && observationCurrent(now)) runCatching {
                        val owner = componentOwner.get() ?: return@runCatching null
                        val sink = owner.sink.get()?.takeIf { it === chain.thisObject } ?: return@runCatching null
                        val actual = track.get(sink) as? AudioTrack ?: return@runCatching null
                        if (componentClaims.granted(owner) != componentPermission.get() ||
                            !validComponent(owner, requireTrack = true) || actual.playState != AudioTrack.PLAYSTATE_PLAYING)
                            return@runCatching null
                        val frame = written.invoke(sink) as Long
                        Triple(sink, actual, frame)
                    }.getOrNull() else null
                    val result = chain.proceed()
                    if (probe != null && block != null) runCatching {
                        recordPlaybackBlock(probe.first, probe.second, probe.third, block.position(), chain.args[1] as Long)
                    }
                    if (ready.get() && monitoring && chain.thisObject === appliedSink &&
                        SystemClock.elapsedRealtime() - lastReadMs >= 1000) observe("playing", chain.thisObject!!)
                    result
                }
                tidalLeadHooks(permission != null, observationEnabled.get(), observe = {
                    runCatching {
                        handles += module.hook(output).intercept { chain ->
                            val result = chain.proceed()
                            observeLead(chain.thisObject, chain.args, result)
                            result
                        }
                        for (boundary in listOf(codecReset, streamChange, positionReset)) {
                            handles += module.hook(boundary).intercept { chain ->
                                if (componentOwner.get()?.renderer?.get() === chain.thisObject) resetObservation()
                                chain.proceed()
                            }
                        }
                    }.onFailure { observationEnabled.set(false); log("passive_lead unavailable=hook_install_failed") }
                }, gate = {})
                if (permission != null || retainedEnabled.get()) {
                    handles += module.hook(render).intercept { chain ->
                        val previous = inputScope.get()
                        val value = runCatching {
                            val now = SystemClock.elapsedRealtime()
                            val owner = componentOwner.get()?.takeIf { it.renderer.get() === chain.thisObject }
                            val grant = owner?.let { componentClaims.granted(it) }
                            val actual = owner?.sink?.get()?.let { track.get(it) as? AudioTrack }
                            if (owner == null || grant == null || actual == null) null else {
                                val late = lateCurrent(now, actual)
                                if (!late && permission == null) return@runCatching null
                                val captured = InputScope(owner, grant, actual, late, null)
                                if (!inputOwnerCurrent(captured)) null else captured.copy(window = inputGate.begin(
                                    owner, actual, grant, inputOffset.invoke(chain.thisObject) as Long,
                                    outputOffset.invoke(chain.thisObject) as Long, chain.args[0] as Long,
                                    now, rendererStarted.getBoolean(chain.thisObject) &&
                                        actual.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                    // Bounded local AudioTrack readback, at most 10 Hz; never wait or query AudioManager.
                                    if (!validComponent(owner) || actual.playState != AudioTrack.PLAYSTATE_PLAYING) null
                                    else actual.playbackHeadPosition.toLong() and 0xffffffffL
                                })
                            }
                        }.getOrNull()
                        if (value == null && (chain.thisObject === selectedRenderer.get() ||
                                chain.thisObject === componentOwner.get()?.renderer?.get())) inputGate.reset("not_owned")
                        inputScope.set(value)
                        try { chain.proceed() }
                        finally { if (previous == null) inputScope.remove() else inputScope.set(previous) }
                    }
                    handles += module.hook(feed).intercept { chain ->
                        var beforeCounters: Any? = null
                        var beforeCount = -1
                        if (observing(chain.thisObject)) runCatching {
                            beforeCounters = decoderCounters.get(chain.thisObject)
                            beforeCount = queuedCount.getInt(beforeCounters)
                        }.onFailure { retireTiming("input_readback_failed") }
                        val captured = inputScope.get()?.takeIf { it.owner.renderer.get() === chain.thisObject }
                        val gateState = runCatching {
                            captured?.takeIf { inputOwnerCurrent(it) }?.let {
                                Triple(it.window, inputHighWater.getLong(chain.thisObject),
                                    drainState.getInt(chain.thisObject) != 0 || inputEnded.getBoolean(chain.thisObject) ||
                                        outputEnded.getBoolean(chain.thisObject) || pendingEnd.getBoolean(chain.thisObject) ||
                                        adaptation.getBoolean(chain.thisObject) || reconfiguration.getInt(chain.thisObject) != 0)
                            }
                        }.getOrNull()
                        val result = if (gateState == null) {
                            if (captured != null) inputGate.reset("not_owned")
                            chain.proceed()
                        } else {
                            val now = SystemClock.elapsedRealtime()
                            val scope = checkNotNull(captured)
                            val reserve = !scope.late || runCatching {
                                lateReserve(scope.owner, scope.track, now)
                            }.getOrDefault(false)
                            val blockedBefore = inputGate.blocked
                            val result = inputGate.feed(gateState.first, gateState.second, now,
                                current = !scope.late || lateCurrent(now, scope.track), controlPending = gateState.third,
                                budgetUs = if (scope.late) TidalDecoderInputLeadGate.LATE_RENDER_BUDGET_US else TidalDecoderInputLeadGate.BUDGET_US,
                                hasReserve = { reserve }) { chain.proceed() as Boolean }
                            if (scope.late && !lateLogged && inputGate.blocked > blockedBefore) {
                                lateLogged = true
                                log("late_render_active buffer_frames=${TidalBufferProbePolicy.FRAMES} " +
                                    "underruns=${lateOutput.underruns} budget_us=${TidalDecoderInputLeadGate.LATE_RENDER_BUDGET_US}")
                            }
                            result
                        }
                        if (beforeCounters != null && observing(chain.thisObject)) runCatching {
                            // The pinned implementation increments only after the codec queue call returns.
                            val afterCounters = decoderCounters.get(chain.thisObject)
                            if (afterCounters !== beforeCounters || !lead.queued(beforeCount,
                                    queuedCount.getInt(afterCounters), inputHighWater.getLong(chain.thisObject)))
                                retireTiming("input_epoch_changed")
                        }.onFailure { retireTiming("input_readback_failed") }
                        result
                    }
                    if (permission != null) handles += module.hook(output).intercept { chain ->
                        val result = chain.proceed()
                        val now = SystemClock.elapsedRealtime()
                        if (observing(chain.thisObject) && now - lastLeadReadMs >= 1000 &&
                            leadReads < TidalBufferProbePolicy.MAX_READBACKS) runCatching {
                            lastLeadReadMs = now
                            leadReads++
                            val sample = if (chain.args[8] == false && ((chain.args[5] as Int) and 2) == 0)
                                lead.sample(chain.args[7] as Long, chain.args[0] as Long) else null
                            log("lead elapsed_ms=$now sample_valid=${sample != null} " +
                                "input_high_water_us=${sample?.inputHighWaterUs} output_pts_us=${sample?.outputUs} " +
                                "renderer_position_us=${sample?.rendererPositionUs} " +
                                "input_minus_output_us=${sample?.inputMinusOutputUs} " +
                                "output_minus_position_us=${sample?.outputMinusPositionUs} " +
                                "input_minus_position_us=${sample?.inputMinusPositionUs} output_accepted=$result acoustic=false")
                            log("input_gate budget_us=${TidalDecoderInputLeadGate.BUDGET_US} blocked=${inputGate.blocked} " +
                                "allowed=${inputGate.allowed} last_lead_us=${inputGate.lastLeadUs} max_lead_us=${inputGate.maxLeadUs} " +
                                "render_age_ms=${inputGate.renderAgeMs} head_progress_age_ms=${inputGate.progressAgeMs(now)} reason=${inputGate.reason}")
                        }.onFailure { retireTiming("output_readback_failed") }
                        result
                    }
                    for (boundary in listOf(codecReset, streamChange)) {
                        handles += module.hook(boundary).intercept { chain ->
                            if (chain.thisObject === selectedRenderer.get() || chain.thisObject === componentOwner.get()?.renderer?.get())
                                inputGate.reset("decoder_or_stream_changed")
                            if (observing(chain.thisObject)) retireTiming("decoder_or_stream_changed")
                            chain.proceed()
                        }
                    }
                    handles += module.hook(positionReset).intercept { chain ->
                        if (chain.thisObject === selectedRenderer.get() || chain.thisObject === componentOwner.get()?.renderer?.get())
                            inputGate.reset("position_reset")
                        chain.proceed()
                    }
                }
                    handles += module.hook(flush).intercept { chain ->
                        if (observedOwner != null && observedOwner?.sink?.get() === chain.thisObject) resetObservation()
                        chain.thisObject?.let(::restoreEffectiveBuffer)
                        if (chain.thisObject === appliedSink || chain.thisObject === componentOwner.get()?.sink?.get())
                            inputGate.reset("flush")
                        val flushedTrack = runCatching { track.get(chain.thisObject) as? AudioTrack }.getOrNull()
                        if (flushedTrack === playbackProbeTrack) { playbackProbeTrack = null; playbackProbeFrame = -1L }
                        flushedTrack?.let(lateOutput::flush)
                        val owned = componentOwner.get()?.takeIf { it.sink.get() === chain.thisObject && validComponent(it) }
                        val marker = runCatching { owned?.let { owner ->
                            val grant = componentClaims.granted(owner)
                            val audioTrack = runCatching { track.get(chain.thisObject) as? AudioTrack }.getOrNull()
                            if (grant == null || audioTrack == null) null else TidalComponentTransition(grant.nonce,
                                grant.bufferIssuedMs, Process.myPid(), audioTrack.audioSessionId, owner.epoch, SystemClock.elapsedRealtimeNanos())
                        } }.getOrNull()
                        if (owned != null && componentOwner.compareAndSet(owned, null)) componentClaims.owner(null)
                        if (ready.get() && chain.thisObject === appliedSink) {
                            observe("teardown", chain.thisObject!!); stopMonitoring(); disarm("track_teardown")
                        }
                        try {
                            val result = chain.proceed()
                            if (owned != null) componentClaims.restoreOwner(owned) {
                                validComponent(owned) && componentOwner.compareAndSet(null, owned)
                            }
                            if (marker != null && componentCurrent()) {
                                handoffReady.set(marker.nonce)
                                componentTask { check(marker.publish(context)); log("component_teardown_observed=true") }
                            }
                            result
                        } catch (failure: Throwable) { retireComponent(); throw failure }
                    }
                enabled.set(permission != null && permit(prefs) == permission)
                prefs.registerOnSharedPreferenceChangeListener(listener)
                readLateRenderRequest()
                if (permit(prefs) != permission) enabled.set(false)
                if (observation(prefs) != observation || observation?.windowLive(SystemClock.elapsedRealtime(), boot) != true ||
                    observationChoiceEpoch.get().let { it > 0 && it != observation?.choiceEpoch }) observationEnabled.set(false)
                refreshComponentPermit()
                if (permission != null) {
                    Handler(Looper.getMainLooper()).postDelayed({ if (componentBuffer.get() == permission) retireComponent() },
                        (permission.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                }
                if (!retained(prefs)) retainedEnabled.set(false)
                ready.set(permission != null)
                installed.set(true)
                if (observationEnabled.get()) log("passive_lead ready=true deadline_ms=${observation?.deadlineMs} " +
                    "pacing_requires_trial_arm=true new_process_required=true")
                if (permission != null)
                    log("ready deadline_ms=${permission.deadlineMs} expected_route=${permission.routeId} minimum_bytes=$minimum coordinator_attested=true pose_authorized=false diagnostic_one_track_per_process=true claim_per_decoder=true")
            } catch (failure: Throwable) {
                retireComponent()
                runCatching { componentWorker.shutdown() }
                installed.set(false); ready.set(false); enabled.set(false)
                handles.asReversed().forEach { runCatching { it.unhook() } }
                runCatching { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
                throw failure
            }
        }

        fun observe(stage: String, target: Any) {
            runCatching {
                lastReadMs = SystemClock.elapsedRealtime()
                if (!componentCurrent()) retireComponent()
                val audioTrack = checkNotNull(track.get(target) as? AudioTrack)
                if (appliedTrack != null && appliedTrack !== audioTrack) { stopMonitoring(); disarm("track_replaced"); return }
                appliedTrack = audioTrack
                val underruns = audioTrack.underrunCount
                if (firstUnderruns == null) firstUnderruns = underruns
                val writtenFrames = written.invoke(target) as Long
                val played = audioTrack.playbackHeadPosition.toLong() and 0xffffffffL
                val pending = (writtenFrames - played).takeIf { it >= 0 } ?: -1L
                val size = audioTrack.bufferSizeInFrames
                val capacity = audioTrack.bufferCapacityInFrames
                val threshold = audioTrack.startThresholdInFrames
                val bufferResult = TidalBufferProbePolicy.bufferResult(size, capacity, threshold,
                    audioTrack.sampleRate, audioTrack.channelCount, audioTrack.audioFormat)
                // Actual route/call/recording state is verified by the coordinator's
                // bounded snapshots, never an AudioManager/port-cache query here.
                log("$stage session=${audioTrack.audioSessionId} expected_route=${permission?.routeId} actual_route_check=external " +
                    "effective_frames=$size capacity_frames=$capacity start_threshold=$threshold " +
                    "buffer_result=$bufferResult requested100ms_verified=${bufferResult.effective100Ms} " +
                    "written_frames=$writtenFrames playback_head_frames=$played " +
                    "write_minus_head_frames=$pending underruns=$underruns underrun_delta=${underruns - firstUnderruns!!} acoustic=false")
                reads++
                if (bufferResult == TidalBufferProbePolicy.BufferResult.INVALID) disarm("track_configuration_mismatch")
                if (!current() || reads >= TidalBufferProbePolicy.MAX_READBACKS) { stopMonitoring(); disarm("deadline_or_disarmed") }
            }.onFailure { stopMonitoring(); disarm("readback_failed") }
        }
    }
}
