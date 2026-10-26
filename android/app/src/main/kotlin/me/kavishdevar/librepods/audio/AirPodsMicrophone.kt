/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.utils.calculateLevel
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class MicrophonePhase { IDLE, STARTING, CAPTURING, STOPPING, RECOVERING, ERROR }

data class MicrophoneState(
    val phase: MicrophonePhase = MicrophonePhase.IDLE,
    val generation: Long = 0,
    val message: String = "",
    val format: PcmFormat? = null,
    val file: File? = null,
    val pcmDurationMs: Long = 0,
    val wallDurationMs: Long = 0,
    val level: Float = 0f,
    val receivedPackets: Long = 0,
    val decodedFrames: Long = 0,
)

/** AACP acquisition and session-owned decoding for Recorder or an explicit live consumer. */
class AirPodsMicrophone internal constructor(
    private val aacp: AACPManager,
    private val outputDirectory: File,
    private val onRecoveryRequired: (Long) -> Unit,
    private val decoderFactory: ((PcmFormat) -> Unit, (ByteArray, Long) -> Unit) -> MicrophoneDecoder,
    private val nowNs: () -> Long,
) : AutoCloseable {
    constructor(aacp: AACPManager, outputDirectory: File, onRecoveryRequired: (Long) -> Unit) :
        this(aacp, outputDirectory, onRecoveryRequired, ::EldDecoder, SystemClock::elapsedRealtimeNanos)

    private fun nowMs() = nowNs() / 1_000_000
    private data class ReceivedPacket(val units: List<MicrophonePacket.AccessUnit>, val receivedNs: Long)

    private class Session(val generation: Long, val socket: BluetoothSocket, startedMs: Long, val consumer: LivePcmConsumer?) {
        val packets = ArrayBlockingQueue<ReceivedPacket>(32)
        val stop = AtomicBoolean(false)
        val connected = AtomicBoolean(true)
        val lastPacketMs = AtomicLong(startedMs)
        val receivedPackets = AtomicLong()
        val receivedFrames = AtomicLong()
        val sawAudio = AtomicBoolean(false)
        @Volatile var failure: String? = null
        @Volatile var recovery: Boolean? = null
        @Volatile var quietLiveStop = false
        @Volatile var verifiedQuietStop = false
        @Volatile var stopCompletedMs: Long? = null
        @Volatile var accepting = true
        @Volatile var worker: Thread? = null
        @Volatile var delivery: BoundedPcmDelivery? = null
    }

    private val mutableState = MutableStateFlow(MicrophoneState())
    val state: StateFlow<MicrophoneState> = mutableState.asStateFlow()
    @Volatile private var session: Session? = null
    private data class QuietStopReceipt(val consumer: LivePcmConsumer, val generation: Long, val socket: BluetoothSocket)
    @Volatile private var quietStopReceipt: QuietStopReceipt? = null
    private var generation = 0L
    @Volatile private var closed = false
    private val packetCallback: (BluetoothSocket, ByteArray) -> Unit = ::receive

    init { aacp.microphonePacketCallback = packetCallback }

    fun start(destination: File? = null): Boolean = start(destination, null)

    /** Accepted starts own and close the consumer; rejected starts leave it with the caller. */
    internal fun startLive(consumer: LivePcmConsumer): Boolean = start(null, consumer)

    @Synchronized
    private fun start(destination: File?, consumer: LivePcmConsumer?): Boolean {
        if (closed || session != null) {
            Log.w("AirPodsMicrophone", "Start rejected: closed=$closed busy=${session != null}")
            return false
        }
        val socket = aacp.captureAudioSocket()
        if (socket == null || !socket.isConnected) {
            Log.w("AirPodsMicrophone", "Start rejected: protocol transport disconnected")
            mutableState.value = MicrophoneState(MicrophonePhase.ERROR, message = "Connect your AirPods first.")
            return false
        }
        val next = Session(++generation, socket, nowMs(), consumer)
        quietStopReceipt = null
        session = next
        mutableState.value = MicrophoneState(MicrophonePhase.STARTING, next.generation)
        Log.i("AirPodsMicrophone", "Session ${next.generation}: capture start accepted, live=${consumer != null}")
        if (consumer != null) next.delivery = BoundedPcmDelivery(consumer,
            { session === next && next.accepting && !next.stop.get() && next.connected.get() && aacp.ownsAudioSocket(socket) },
            { fail(next, it) })
        next.worker = Thread({ capture(next, destination, consumer != null) }, "AirPods microphone").apply { start() }
        return true
    }

    fun stop() {
        session?.let {
            it.accepting = false
            it.stop.set(true)
            it.delivery?.close()
        }
    }

    internal fun ownsLiveConsumer(consumer: LivePcmConsumer): Boolean = session?.consumer === consumer

    /** One-shot proof of a quiet Stop or a retired session that never attempted Start or received audio. */
    @Synchronized
    internal fun takeQuietLiveStop(consumer: LivePcmConsumer): Boolean {
        val receipt = quietStopReceipt?.takeIf { it.consumer === consumer } ?: return false
        quietStopReceipt = null
        return !closed && session == null && receipt.generation == generation &&
            receipt.socket.isConnected && aacp.ownsAudioSocket(receipt.socket)
    }

    /** The root call route is already quiesced; only its exact quiet uplink can skip native recovery. */
    @Synchronized
    internal fun completeCallStopRecovery(generation: Long, consumer: LivePcmConsumer): Boolean {
        val active = session?.takeIf { it.generation == generation && it.consumer === consumer } ?: return false
        if (!canConfirmQuietLiveStop(active)) return false
        active.recovery = true
        return true
    }

    /** Exclusive physical-frame boundary, including validated audio still waiting for the decoder. */
    internal fun receivedFrameFloor(consumer: LivePcmConsumer): Long? {
        val active = session ?: return null
        val frames = active.receivedFrames.get()
        return frames.takeIf {
            !closed && session === active && active.consumer === consumer && active.accepting &&
                !active.stop.get() && active.connected.get() && active.failure == null &&
                aacp.ownsAudioSocket(active.socket)
        }
    }

    fun onDisconnected() {
        synchronized(this) { quietStopReceipt = null }
        session?.let {
            it.connected.set(false)
            fail(it, "AirPods disconnected. Reconnect before recording again.")
        }
    }

    fun onPermissionRevoked() {
        synchronized(this) { quietStopReceipt = null }
        session?.let { fail(it, "Microphone access was removed or blocked. Check microphone privacy and Bluetooth access in Settings.") }
    }

    fun completeRecovery(generation: Long, success: Boolean) {
        val active = session?.takeIf { it.generation == generation }
        Log.i("AirPodsMicrophone", "Recovery result: session=$generation accepted=${active != null} success=$success")
        active?.recovery = success
    }

    fun transportFor(generation: Long): BluetoothSocket? = session?.takeIf { it.generation == generation }?.socket

    /** A late packet or changed transport revokes the live-only permission to keep playback running. */
    internal fun canKeepPlayback(generation: Long): Boolean {
        val active = session?.takeIf { it.generation == generation } ?: return false
        return active.quietLiveStop && active.failure == null && canConfirmQuietLiveStop(active)
    }

    private fun canConfirmQuietLiveStop(active: Session): Boolean {
        val stoppedAt = active.stopCompletedMs ?: return false
        return !closed && session === active && active.consumer != null && active.verifiedQuietStop && active.connected.get() &&
            mutableState.value.let { it.generation == active.generation && it.phase == MicrophonePhase.RECOVERING } &&
            aacp.ownsAudioSocket(active.socket) && nowMs() - stoppedAt >= 500 &&
            nowMs() - active.lastPacketMs.get() >= 500
    }

    private fun receive(source: BluetoothSocket, packet: ByteArray) {
        val active = session ?: run {
            if (quietStopReceipt != null) synchronized(this) {
                if (quietStopReceipt?.socket === source && MicrophonePacket.isAudio(packet)) quietStopReceipt = null
            }
            return
        }
        if (active.socket !== source || !aacp.ownsAudioSocket(source)) return
        if (!MicrophonePacket.isAudio(packet)) return
        active.sawAudio.set(true)
        active.lastPacketMs.set(nowMs())
        if (quietStopReceipt != null) synchronized(this) {
            if (quietStopReceipt?.generation == active.generation) quietStopReceipt = null
        }
        if (!active.accepting || active.stop.get()) return
        try {
            val units = MicrophonePacket.parse(packet)
            active.receivedPackets.incrementAndGet()
            // Publish before queueing: a mute boundary must include pending decoder output too.
            active.receivedFrames.addAndGet(units.size.toLong() * AirPodsMicrophoneClock.SAMPLES_PER_UNIT)
            if (!active.packets.offer(ReceivedPacket(units, nowNs())))
                fail(active, "Microphone decoder could not keep up. Please retry.")
        } catch (_: IllegalArgumentException) {
            fail(active, "AirPods sent an incomplete microphone packet. Please retry.")
        }
    }

    private fun fail(active: Session, message: String) {
        if (session !== active) return
        active.failure = message
        active.accepting = false
        active.stop.set(true)
        active.delivery?.close()
    }

    private fun capture(active: Session, destination: File?, live: Boolean) {
        val startedNs = nowNs()
        val started = startedNs / 1_000_000
        val clock = MicrophoneClockStats(startedNs)
        var lastPcm = started
        var bytesDecoded = 0L
        var writer: PcmWaveWriter? = null
        var decoder: MicrophoneDecoder? = null
        var attemptedStart = false
        var outputFormat: PcmFormat? = null
        var outputFile: File? = null
        var captureEnded = started
        try {
            val currentDecoder = decoderFactory(format@{ format ->
                if (active.stop.get()) return@format
                check(format == BoundedPcmDelivery.FORMAT) { "Unexpected microphone PCM format" }
                if (outputFormat == null) {
                    outputFormat = format
                    Log.i("AirPodsMicrophone", "Session ${active.generation}: PCM16 format ${format.sampleRate} Hz, ${format.channels} channel")
                    if (!live) {
                        check(outputDirectory.exists() || outputDirectory.mkdirs()) { "Cannot create recording directory" }
                        outputFile = destination ?: File.createTempFile("airpods-mic-", ".wav", outputDirectory)
                        if (destination == null) pruneMicrophoneRecordings(outputDirectory, checkNotNull(outputFile))
                        writer = PcmWaveWriter(checkNotNull(outputFile), format)
                    }
                }
            }, pcm@{ bytes, timestamp ->
                if (!active.accepting || active.stop.get()) return@pcm
                check(aacp.ownsAudioSocket(active.socket)) { "Microphone connection changed" }
                if (live && active.delivery?.offer(bytes, timestamp) != true) return@pcm
                writer?.write(bytes)
                if (bytesDecoded == 0L) Log.i("AirPodsMicrophone", "Session ${active.generation}: first decoded PCM")
                bytesDecoded += bytes.size
                lastPcm = nowMs()
                val format = checkNotNull(outputFormat)
                mutableState.value = MicrophoneState(MicrophonePhase.CAPTURING, active.generation,
                    format = format, file = outputFile, pcmDurationMs = format.durationMs(bytesDecoded),
                    wallDurationMs = lastPcm - started, level = calculateLevel(bytes),
                    receivedPackets = active.receivedPackets.get(), decodedFrames = bytesDecoded / format.frameSize)
            })
            decoder = currentDecoder
            check(active.connected.get() && !active.stop.get()) { "Microphone start cancelled" }
            attemptedStart = true
            check(aacp.requestMicrophoneStream(active.socket)) { "Could not start the AirPods microphone" }
            Log.i("AirPodsMicrophone", "Session ${active.generation}: microphone Start sent")
            val deadline = nowMs() + 5_000
            while (!active.stop.get()) {
                check(aacp.ownsAudioSocket(active.socket)) { "Microphone connection changed" }
                val packet = active.packets.poll(10, TimeUnit.MILLISECONDS)
                packet?.units?.forEach { unit ->
                    if (!active.stop.get()) currentDecoder.decode(unit.bytes) { active.stop.get() }
                }
                currentDecoder.drain()
                if (packet != null && !active.stop.get()) {
                    clock.packet(packet.units.map { it.timestamp }, packet.receivedNs, currentDecoder.decodedFrames)
                }
                val now = nowMs()
                check(if (bytesDecoded == 0L) now < deadline else now - lastPcm < 5_000) {
                    "No decoded microphone audio. Please retry."
                }
                check(bytesDecoded == 0L || now - active.lastPacketMs.get() < 2_000) {
                    "Microphone stream was interrupted. Please retry."
                }
                if (!live && now - started >= 60_000) stop()
            }
        } catch (e: Exception) {
            Log.w("AirPodsMicrophone", "Session ${active.generation}: capture ended (${e.javaClass.simpleName})")
            if (active.failure == null && !active.stop.get())
                active.failure = e.message ?: "Microphone capture failed"
        } finally {
            captureEnded = nowMs()
            if (Log.isLoggable("AirPodsMicrophone", Log.DEBUG)) clock.snapshot()?.let { stats ->
                Log.d("AirPodsMicrophone", "Session ${active.generation}: clock first_packet_ms=${stats.firstPacketMs} warmup_ms=${stats.warmupMs} steady_ms=${stats.steadyMs} aus=${stats.accessUnits} decoded_frames=${stats.decodedFrames} source_hz=${stats.sourceHz} physical_hz=${AirPodsMicrophoneClock.PHYSICAL_RATE} timestamp_ticks=${stats.timestampTicks} intervals=${stats.timestampIntervals} repeats=${stats.repeats} backwards=${stats.backwards}")
            }
            active.accepting = false
            active.stop.set(true)
            active.packets.clear()
            active.delivery?.close()
            try { decoder?.close() } catch (_: Exception) { active.failure = "Microphone decoder did not close cleanly" }
            try { writer?.close() } catch (_: Exception) { active.failure = "Could not finish the microphone recording" }
            if (attemptedStart && active.connected.get()) {
                try { finishUplink(active) } catch (_: Exception) {
                    active.failure = "Microphone cleanup failed. Reconnect your AirPods."
                }
            }
            synchronized(this) {
                val neverStarted = !attemptedStart && !active.sawAudio.get() && !closed && active.connected.get() &&
                    aacp.ownsAudioSocket(active.socket)
                if (session === active) quietStopReceipt = active.consumer?.takeIf { neverStarted || canConfirmQuietLiveStop(active) }
                    ?.let { QuietStopReceipt(it, active.generation, active.socket) }
            }
            val previous = mutableState.value
            mutableState.value = previous.copy(
                phase = if (active.failure == null) MicrophonePhase.IDLE else MicrophonePhase.ERROR,
                message = active.failure ?: if (live) "Microphone stopped." else
                    "Recording stopped. Confirm playback in both AirPods, then check the recording duration and pitch.",
                wallDurationMs = captureEnded - started,
                level = 0f,
            )
            Log.i("AirPodsMicrophone", "Session ${active.generation}: ${mutableState.value.phase}, PCM ${mutableState.value.pcmDurationMs} ms, elapsed ${mutableState.value.wallDurationMs} ms, error=${active.failure != null}, reason=${active.failure.orEmpty()}")
            synchronized(this) { if (session === active) session = null }
            if (closed && aacp.microphonePacketCallback === packetCallback) aacp.microphonePacketCallback = null
        }
    }

    private fun finishUplink(active: Session) {
        mutableState.value = mutableState.value.copy(phase = MicrophonePhase.STOPPING)
        Log.i("AirPodsMicrophone", "Session ${active.generation}: stopping uplink")
        var quiet = false
        var stopWritesSucceeded = true
        repeat(2) {
            if (quiet || !active.connected.get()) return@repeat
            if (!aacp.endMicrophoneStream(active.socket)) {
                stopWritesSucceeded = false
                active.failure = "Could not stop the AirPods microphone. Reconnect your AirPods."
                return@repeat
            }
            active.stopCompletedMs = nowMs()
            val deadline = nowMs() + 2_000
            while (active.connected.get() && nowMs() < deadline) {
                if (nowMs() - active.lastPacketMs.get() >= 500 &&
                    (active.consumer == null || nowMs() - checkNotNull(active.stopCompletedMs) >= 500)) {
                    quiet = true
                    break
                }
                Thread.sleep(25)
            }
        }
        if (!active.connected.get()) return
        if (!quiet) active.failure = "Microphone uplink did not stop. Reconnect your AirPods."
        active.verifiedQuietStop = quiet && stopWritesSucceeded
        active.quietLiveStop = active.consumer != null && quiet && active.failure == null
        mutableState.value = mutableState.value.copy(phase = MicrophonePhase.RECOVERING)
        Log.i("AirPodsMicrophone", "Session ${active.generation}: recovering playback, quiet=$quiet")
        try {
            onRecoveryRequired(active.generation)
            val deadline = nowMs() + 8_000
            while (active.connected.get() && active.recovery == null && nowMs() < deadline)
                Thread.sleep(25)
            if (active.connected.get() && active.recovery != true)
                active.failure = "Playback recovery could not be confirmed. Reconnect your AirPods."
        } catch (_: Exception) {
            active.failure = "Playback recovery failed. Reconnect your AirPods."
        }
    }

    override fun close() {
        synchronized(this) {
            closed = true
            quietStopReceipt = null
            stop()
            if (session == null && aacp.microphonePacketCallback === packetCallback) aacp.microphonePacketCallback = null
        }
    }
}
