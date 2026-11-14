/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import rikka.shizuku.SystemServiceHelper
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal interface HeadTrackerSinkSession : Closeable {
    val reporting: RootHeadTrackerSink.Reporting
    val halHandshake: Boolean
    val isClosed: Boolean
    val stopReason: String?
}

/** Root-only sink. The caller transfers a readable pose pipe; this sink enables no audio/settings. */
internal class RootHeadTrackerSink(
    private val selectedRoute: (String) -> Boolean,
    private val caller: () -> Unit,
) : Closeable {
    constructor(caller: () -> Unit) : this(NativeA2dpRoute()::selected, caller)

    private var active: Session? = null
    private var closed = false
    data class Reporting(val enabled: Boolean, val intervalNanos: Long)

    /** onReportingChanged must enqueue work promptly; it runs after synchronous HID replies. */
    @Synchronized fun open(address: String, owner: IPlaybackRecoveryOwner, poses: ParcelFileDescriptor,
        onReportingChanged: (Reporting) -> Unit): Session {
        var session: Session? = null
        try {
            caller()
            check(Process.myUid() == 0 && !closed && active == null) { "head_tracker_sink_unavailable" }
            val selected = A2dpRecoveryGuard(address).address
            requireCurrent(selected, owner)
            val descriptor = poses.fileDescriptor
            check(OsConstants.S_ISFIFO(Os.fstat(descriptor).st_mode)) { "pose_fd_not_pipe" }
            val flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0)
            check(flags and OsConstants.O_ACCMODE == OsConstants.O_RDONLY) { "pose_pipe_not_readable" }
            Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            session = Session(selected, owner, poses, onReportingChanged)
            owner.asBinder().linkToDeath(session, 0)
            requireCurrent(selected, owner)
            active = session
            session.start()
            return session
        } catch (error: Throwable) {
            session?.let { runCatching { owner.asBinder().unlinkToDeath(it, 0) } }
            runCatching { poses.close() }
            if (active === session) active = null
            throw error
        }
    }

    private fun requireCurrent(address: String, owner: IPlaybackRecoveryOwner) {
        check(owner.asBinder().isBinderAlive && owner.isCurrent && selectedRoute(address) &&
            owner.asBinder().isBinderAlive && owner.isCurrent) { "head_tracker_owner_or_route_changed" }
    }

    inner class Session internal constructor(private val address: String, private val owner: IPlaybackRecoveryOwner,
        private val poses: ParcelFileDescriptor, private val onReportingChanged: (Reporting) -> Unit) :
        HeadTrackerSinkSession, IBinder.DeathRecipient {
        private val stopped = AtomicBoolean(false)
        @Volatile override var reporting = Reporting(false, 10_000_000L)
            private set
        @Volatile override var halHandshake = false
            private set
        @Volatile override var isClosed = false
            private set
        @Volatile override var stopReason: String? = null
            private set

        private fun stop(reason: String) { if (stopReason == null) stopReason = reason; stopped.set(true) }
        override fun close() = stop("closed")
        override fun binderDied() = stop("owner_died")

        internal fun start() { thread(name = "head-tracker-uhid", isDaemon = true) { run() } }

        private fun send(descriptor: FileDescriptor, bytes: ByteArray) {
            while (!stopped.get()) {
                try {
                    check(Os.write(descriptor, bytes, 0, bytes.size) == bytes.size) { "short_uhid_write" }
                    return
                } catch (error: ErrnoException) { if (error.errno != OsConstants.EINTR) throw error }
            }
            throw IllegalStateException("head_tracker_cancelled")
        }

        private fun run() {
            var uhid: FileDescriptor? = null
            try {
                requireCurrent(address, owner)
                check(!stopped.get()) { "head_tracker_cancelled" }
                val descriptor = Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
                uhid = descriptor
                check(OsConstants.S_ISCHR(Os.fstat(descriptor).st_mode)) { "uhid_not_character_device" }
                val protocol = HeadTrackerUhidProtocol(address)
                val gate = HeadTrackerPoseGate()
                send(descriptor, protocol.create())
                val events = ByteArray(HeadTrackerUhidProtocol.EVENT_BYTES)
                val frame = ByteArray(HeadTrackerUhidProtocol.FRAME_BYTES)
                var partial = 0
                var nextInput = 0L
                var nextOwnerCheck = 0L
                val polls = arrayOf(
                    StructPollfd().apply { fd = descriptor; this.events = OsConstants.POLLIN.toShort() },
                    StructPollfd().apply { fd = poses.fileDescriptor; this.events = OsConstants.POLLIN.toShort() },
                )
                while (!stopped.get()) {
                    var now = SystemClock.elapsedRealtimeNanos()
                    if (now >= nextOwnerCheck) {
                        requireCurrent(address, owner)
                        check(!stopped.get()) { "head_tracker_cancelled" }
                        now = SystemClock.elapsedRealtimeNanos()
                        nextOwnerCheck = now + 250_000_000L
                    }
                    var wake = minOf(nextOwnerCheck, gate.deadline)
                    if (protocol.reporting && gate.hasPose) wake = minOf(wake, nextInput)
                    val timeout = ((wake - now).coerceAtLeast(0) + 999_999) / 1_000_000
                    polls.forEach { it.revents = 0 }
                    try { Os.poll(polls, timeout.coerceAtMost(250).toInt()) }
                    catch (error: ErrnoException) { if (error.errno == OsConstants.EINTR) continue else throw error }
                    check(!stopped.get()) { "head_tracker_cancelled" }
                    val ended = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
                    check(polls.none { it.revents.toInt() and ended != 0 }) { "head_tracker_fd_closed" }
                    if (polls[0].revents.toInt() and OsConstants.POLLIN != 0) {
                        val count = Os.read(descriptor, events, 0, events.size)
                        check(count == events.size) { "short_uhid_read" }
                        protocol.handle(events)?.let { send(descriptor, it) }
                        halHandshake = protocol.halHandshake
                        now = SystemClock.elapsedRealtimeNanos()
                        val changed = Reporting(protocol.reporting, protocol.intervalNanos)
                        if (changed != reporting) {
                            gate.reporting(changed.enabled, now)
                            reporting = changed
                            nextInput = now + changed.intervalNanos
                            onReportingChanged(changed)
                        }
                    }
                    var received = 0
                    while (!stopped.get() && received < 32) {
                        val count = try { Os.read(poses.fileDescriptor, frame, partial, frame.size - partial) }
                        catch (error: ErrnoException) {
                            if (error.errno == OsConstants.EAGAIN) break
                            if (error.errno == OsConstants.EINTR) continue
                            throw error
                        }
                        check(count > 0) { "pose_pipe_eof" }
                        partial += count
                        if (partial == frame.size) {
                            now = SystemClock.elapsedRealtimeNanos()
                            gate.accept(HeadTrackerUhidProtocol.decodePose(frame, now), now)
                            partial = 0; ++received
                        }
                    }
                    if (received == 32) {
                        polls[1].revents = 0
                        Os.poll(arrayOf(polls[1]), 0)
                        check(polls[1].revents.toInt() and ended == 0) { "pose_pipe_closed" }
                        check(polls[1].revents.toInt() and OsConstants.POLLIN == 0) { "pose_backlog" }
                    }
                    now = SystemClock.elapsedRealtimeNanos()
                    gate.checkFresh(now)
                    if (protocol.reporting && now >= nextInput) {
                        gate.input(now)?.let { send(descriptor, it) }
                        nextInput = now + protocol.intervalNanos
                    }
                }
            } catch (error: Exception) {
                if (!stopped.get()) { stop("sink_failed"); Log.w("RootHeadTrackerSink", "Head tracker sink stopped", error) }
            } finally {
                stopped.set(true)
                reporting = reporting.copy(enabled = false)
                runCatching { poses.close() }
                // Closing this worker-owned FD unregisters UHID, including on owner/process death.
                uhid?.let { runCatching { Os.close(it) } }
                runCatching { onReportingChanged(reporting) }
                runCatching { owner.asBinder().unlinkToDeath(this, 0) }
                isClosed = true
                synchronized(this@RootHeadTrackerSink) { if (active === this) active = null }
            }
        }
    }

    @Synchronized override fun close() { closed = true; active?.close() }

    /** Exact selected native A2DP route only; no context attribution or mutating APIs. */
    private class NativeA2dpRoute {
        private val api = Class.forName("android.media.IAudioService")
        private val attributes = Class.forName("android.media.AudioDeviceAttributes")
        private val service = Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null,
                checkNotNull(SystemServiceHelper.getSystemService("audio")))
        private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        private val routes = api.getMethod("getDevicesForAttributes", AudioAttributes::class.java).also {
            check(it.returnType == List::class.java)
        }
        private val mode = api.getMethod("getMode").also { check(it.returnType == Int::class.javaPrimitiveType) }
        fun selected(address: String): Boolean {
            val devices = routes.invoke(service, media) as List<*>
            val selected = devices.singleOrNull() ?: return false
            return attributes.isInstance(selected) && attributes.getMethod("getRole").invoke(selected) == 2 &&
                attributes.getMethod("getType").invoke(selected) == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                address.equals(attributes.getMethod("getAddress").invoke(selected) as? String, true) && mode.invoke(service) == 0
        }
    }
}
