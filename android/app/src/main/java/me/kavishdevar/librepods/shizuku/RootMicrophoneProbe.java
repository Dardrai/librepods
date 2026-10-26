package me.kavishdevar.librepods.shizuku;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import me.kavishdevar.librepods.BuildConfig;

/** Debug-only, memory-only delivery proof into this root helper's own virtual input. */
@SuppressLint({"MissingPermission", "SoonBlockedPrivateApi"})
public final class RootMicrophoneProbe implements AutoCloseable {
    private static final int SOURCE_RATE = 64000, MIX_RATE = 48000, BLOCK = 480;
    private static final long SETUP_MS = 3000, FIRST_PCM_MS = 2000, GAP_MS = 250, STREAM_MS = 20000;
    private static final String PREFIX = "android.media.audiopolicy.";
    private final Context context;
    private final ParcelFileDescriptor input;
    private final IPlaybackRecoveryOwner owner;
    private final Lease lease = new Lease();
    private final AtomicBoolean prepared = new AtomicBoolean(), cleaning = new AtomicBoolean();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final CountDownLatch cleaned = new CountDownLatch(1);
    private final Stats sent = new Stats(), received = new Stats();
    private final PendingPcm pending = new PendingPcm();
    private final IBinder.DeathRecipient death = () -> stop("Owner died");
    private volatile AudioManager manager;
    private volatile Object policy;
    private volatile AudioTrack track;
    private volatile AudioRecord record;
    private volatile String address;
    private volatile int remoteType, session, trackBufferFrames, trackCapacityFrames, recordBufferFrames;
    private volatile boolean linked, registered, trackStarted, recordStarted, cleanupComplete;
    private volatile long readyAt, firstPcmAt, lastPcmAt, endedAt, silenceFrames;

    public RootMicrophoneProbe(Context context, ParcelFileDescriptor input, IPlaybackRecoveryOwner owner) {
        this.context = context;
        this.input = input;
        this.owner = owner;
    }

    static final class Lease {
        final AtomicBoolean stopped = new AtomicBoolean();
        private boolean cleanupClaimed;
        synchronized <T> T mutate(Callable<T> operation) throws Exception {
            require(!stopped.get() && !cleanupClaimed, "Route owner retired");
            return operation.call();
        }
        synchronized boolean claimCleanup() {
            stopped.set(true);
            if (cleanupClaimed) return false;
            cleanupClaimed = true;
            return true;
        }
    }

    static boolean exactRoute(boolean source, int type, int wantedType, String actual, String owned) {
        return source && type == wantedType && owned != null && !owned.isEmpty() && owned.equals(actual);
    }
    static boolean mayStart(int uid, int actualSession, int wantedSession, int source,
            boolean initialized, boolean routed, boolean stopped) {
        return uid == 0 && wantedSession > 0 && actualSession == wantedSession && source == 7
                && initialized && routed && !stopped;
    }
    static boolean withinStream(long now, long ready, long first, long last) {
        return first == 0 ? now - ready < FIRST_PCM_MS : now - first < STREAM_MS && now - last < GAP_MS;
    }
    @SafeVarargs
    static boolean releaseVirtualInput(boolean recorderReleased, Callable<Void>... orderedActions) {
        if (!recorderReleased) return false;
        boolean ok = true;
        for (Callable<Void> action : orderedActions) {
            try { action.call(); } catch (Exception error) { ok = false; }
        }
        return ok;
    }

    /** Counts only aggregate levels; never retains samples beyond the current short block. */
    static final class Stats {
        long frames, clips, squares;
        int peak;
        synchronized void add(short[] samples, int count) {
            add(samples, 0, count);
        }
        synchronized void add(short[] samples, int offset, int count) {
            for (int i = offset; i < offset + count; i++) {
                int value = samples[i];
                squares += (long) value * value;
                peak = Math.max(peak, Math.abs(value));
                if (value == Short.MIN_VALUE || value == Short.MAX_VALUE) clips++;
            }
            frames += count;
        }
        synchronized long frames() { return frames; }
        synchronized void put(Bundle result, String prefix) {
            result.putLong(prefix + "Frames", frames);
            result.putDouble(prefix + "Rms", frames == 0 ? 0 : Math.sqrt((double) squares / frames) / 32768.0);
            result.putDouble(prefix + "Peak", peak / 32768.0);
            result.putLong(prefix + "ClipCount", clips);
        }
    }

    @FunctionalInterface
    interface PcmWriter { int write(short[] samples, int offset, int count) throws Exception; }

    /** One block only. A short write retains its suffix; the producer is not read again yet. */
    static final class PendingPcm {
        final short[] samples = new short[BLOCK];
        private int size, offset;
        private long since;
        volatile long offeredFrames, shortWrites, zeroWrites, maxAgeMs;
        int remaining() { return size - offset; }
        void load(byte[] bytes, int frames, long now) {
            require(remaining() == 0 && frames > 0 && frames <= BLOCK && frames * 2 <= bytes.length,
                    "Pending PCM block would be overwritten");
            for (int i = 0; i < frames; i++) samples[i] = (short) ((bytes[i * 2] & 255) | (bytes[i * 2 + 1] << 8));
            size = frames; offset = 0; since = now; offeredFrames += frames;
        }
        int drain(PcmWriter writer, Stats stats, long now, boolean stopped) throws Exception {
            if (remaining() == 0) return 0;
            maxAgeMs = Math.max(maxAgeMs, now - since);
            require(!stopped, "Pending PCM owner retired");
            require(now - since < GAP_MS, "Virtual output stalled with pending PCM");
            int wanted = remaining();
            int accepted = writer.write(samples, offset, wanted);
            require(accepted >= 0 && accepted <= wanted, "Virtual output rejected PCM");
            if (accepted == 0) zeroWrites++;
            else if (accepted < wanted) shortWrites++;
            stats.add(samples, offset, accepted); // Count only samples actually accepted by AudioTrack.
            offset += accepted;
            if (remaining() == 0) erase();
            return accepted;
        }
        void erase() {
            java.util.Arrays.fill(samples, (short) 0);
            size = 0; offset = 0; since = 0;
        }
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    private static Object get(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }
    private static int constant(Class<?> type, String name) throws Exception { return type.getField(name).getInt(null); }
    private int socketOption(int option) throws Exception {
        return (Integer) Os.class.getMethod("getsockoptInt", java.io.FileDescriptor.class, int.class, int.class)
                .invoke(null, input.getFileDescriptor(), OsConstants.SOL_SOCKET, option);
    }
    private static void require(boolean condition, String error) { if (!condition) throw new IllegalStateException(error); }
    private static String describe(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
    private static Thread daemon(String name, Runnable action) {
        Thread thread = new Thread(action, name); thread.setDaemon(true); thread.start(); return thread;
    }

    public Bundle prepare() {
        require(prepared.compareAndSet(false, true), "Probe cannot be reused");
        try {
            require(BuildConfig.DEBUG && Process.myUid() == 0 && Build.VERSION.SDK_INT == 37,
                    "Probe requires the debug Android 17 root helper");
            long deadline = SystemClock.elapsedRealtime() + SETUP_MS;
            daemon("root-mic-proof-deadline", () -> {
                while (!cleanupComplete) {
                    long now = SystemClock.elapsedRealtime();
                    if (readyAt == 0 && now >= deadline) { stop("Setup deadline"); break; }
                    if (readyAt != 0 && firstPcmAt == 0 && now - readyAt >= FIRST_PCM_MS) {
                        stop("No microphone PCM within startup deadline"); break;
                    }
                    if (firstPcmAt != 0 && now - firstPcmAt >= STREAM_MS) { stop(null); break; }
                    if (lease.stopped.get()) break;
                    SystemClock.sleep(20);
                }
                awaitCleanup();
                if (!cleanupComplete) Process.killProcess(Process.myPid());
            });
            for (String permission : new String[]{"MODIFY_AUDIO_ROUTING", "RECORD_AUDIO", "CAPTURE_AUDIO_OUTPUT"})
                require(context.checkPermission("android.permission." + permission, Process.myPid(), 0)
                        == PackageManager.PERMISSION_GRANTED, "Root audio permission unavailable");
            require(socketOption(OsConstants.SO_TYPE) == OsConstants.SOCK_STREAM, "Expected owned stream socket");
            require((Os.fcntlInt(input.getFileDescriptor(), OsConstants.F_GETFL, 0) & OsConstants.O_NONBLOCK) != 0,
                    "Input must be nonblocking");
            int capacity = socketOption(OsConstants.SO_RCVBUF);
            require(capacity > 0 && capacity <= 8192, "Input queue exceeds probe bound");
            lease.mutate(() -> { owner.asBinder().linkToDeath(death, 0); linked = true; return null; });
            var constructor = AudioManager.class.getDeclaredConstructor(Context.class); constructor.setAccessible(true);
            lease.mutate(() -> { manager = constructor.newInstance(context); return null; });
            checkOwnerAndPrivacy();
            session = manager.generateAudioSessionId();
            require(session > 0, "No own audio session");
            remoteType = constant(AudioDeviceInfo.class, "TYPE_REMOTE_SUBMIX");
            createPolicy();
            lease.mutate(() -> { trackStarted = true; track.play(); return null; });
            daemon("root-mic-proof-stream", this::stream);
            boolean published = false;
            do {
                require(!lease.stopped.get(), "Probe cancelled during setup");
                published = hasOwnInput();
                if (!published) SystemClock.sleep(10);
            } while (!published && SystemClock.elapsedRealtime() < deadline);
            require(published, "Own virtual input was not published");
            AudioRecord.Builder builder = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(MIX_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(MIX_RATE / 10);
            call(builder, "setSessionId", new Class<?>[]{int.class}, session);
            call(builder, "setContext", new Class<?>[]{Context.class}, context);
            lease.mutate(() -> { record = builder.build(); return null; });
            require(mayStart(Process.myUid(), record.getAudioSessionId(), session, record.getAudioSource(),
                    record.getState() == AudioRecord.STATE_INITIALIZED, recordRouteMatches(), lease.stopped.get()),
                    "No exact own remote-submix route before recording");
            require(record.getSampleRate() == MIX_RATE && record.getChannelCount() == 1
                    && record.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT, "Unexpected receiver format");
            recordBufferFrames = record.getBufferSizeInFrames();
            require(SystemClock.elapsedRealtime() < deadline, "Setup deadline");
            checkOwnerAndPrivacy();
            lease.mutate(() -> { recordStarted = true; record.startRecording(); return null; });
            require(record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING, "Virtual input did not start");
            readyAt = SystemClock.elapsedRealtime();
            return snapshot();
        } catch (Exception error) {
            failure.compareAndSet(null, describe(error));
            close();
            return snapshot();
        }
    }

    @SuppressWarnings("unchecked")
    private void createPolicy() throws Exception {
        Class<?> ruleType = Class.forName(PREFIX + "AudioMixingRule");
        int uidRule = constant(ruleType, "RULE_MATCH_UID"), sessionRule = constant(ruleType, "RULE_MATCH_AUDIO_SESSION_ID");
        int sourceRule = constant(ruleType, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET");
        Object builder = Class.forName(PREFIX + "AudioMixingRule$Builder").getConstructor().newInstance();
        call(builder, "setTargetMixRole", new Class<?>[]{int.class}, constant(ruleType, "MIX_ROLE_INJECTOR"));
        call(builder, "addMixRule", new Class<?>[]{int.class, Object.class}, uidRule, 0);
        call(builder, "addMixRule", new Class<?>[]{int.class, Object.class}, sessionRule, session);
        AudioAttributes.Builder attributes = new AudioAttributes.Builder();
        call(attributes, "setCapturePreset", new Class<?>[]{int.class}, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        call(builder, "addMixRule", new Class<?>[]{int.class, Object.class}, sourceRule, attributes.build());
        Object rule = get(builder, "build");
        List<?> criteria = (List<?>) get(rule, "getCriteria");
        require(criteria.size() == 3, "Unexpected scope count");
        boolean uid = false, ownSession = false, source = false;
        for (Object criterion : criteria) {
            int kind = (Integer) get(criterion, "getRule");
            if (kind == uidRule) uid = (Integer) get(criterion, "getIntProp") == 0;
            else if (kind == sessionRule) ownSession = (Integer) get(criterion, "getIntProp") == session;
            else if (kind == sourceRule) source = (Integer) get(get(criterion, "getAudioAttributes"), "getCapturePreset") == 7;
            else throw new IllegalStateException("Unexpected scope dimension");
        }
        require(uid && ownSession && source, "Incomplete own-session scope");
        Class<?> mixType = Class.forName(PREFIX + "AudioMix");
        Object mixBuilder = Class.forName(PREFIX + "AudioMix$Builder").getConstructor(ruleType).newInstance(rule);
        call(mixBuilder, "setFormat", new Class<?>[]{AudioFormat.class}, new AudioFormat.Builder().setSampleRate(MIX_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build());
        int loopback = constant(mixType, "ROUTE_FLAG_LOOP_BACK");
        call(mixBuilder, "setRouteFlags", new Class<?>[]{int.class}, loopback);
        Object mix = get(mixBuilder, "build");
        require((Integer) get(mix, "getRouteFlags") == loopback, "Physical output rejected");
        Object policyBuilder = Class.forName(PREFIX + "AudioPolicy$Builder").getConstructor(Context.class).newInstance(context);
        call(policyBuilder, "addMix", new Class<?>[]{mixType}, mix);
        Object newPolicy = get(policyBuilder, "build");
        lease.mutate(() -> {
            policy = newPolicy; registered = true;
            require((Integer) call(manager, "registerAudioPolicy", new Class<?>[]{policy.getClass()}, policy) == 0,
                    "Policy registration failed");
            address = (String) get(mix, "getRegistration");
            require(address != null && !address.isEmpty(), "No own policy address");
            track = (AudioTrack) call(policy, "createAudioTrackSource", new Class<?>[]{mixType}, mix);
            return null;
        });
        require(track != null && track.getState() == AudioTrack.STATE_INITIALIZED, "Virtual track unavailable");
        AudioAttributes actual = track.getAudioAttributes();
        require(actual.getUsage() == constant(AudioAttributes.class, "USAGE_VIRTUAL_SOURCE")
                && ((Set<String>) get(actual, "getTags")).contains("addr=" + address), "Track not bound to own mix");
        require(track.setPlaybackRate(SOURCE_RATE) == AudioTrack.SUCCESS && track.getPlaybackRate() == SOURCE_RATE,
                "64 kHz physical microphone clock not accepted");
        trackBufferFrames = track.getBufferSizeInFrames();
        trackCapacityFrames = track.getBufferCapacityInFrames();
    }

    private boolean hasOwnInput() {
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if (exactRoute(device.isSource(), device.getType(), remoteType, device.getAddress(), address)) return true;
        return false;
    }
    private boolean recordRouteMatches() {
        AudioDeviceInfo route = record.getRoutedDevice();
        return route != null && exactRoute(route.isSource(), route.getType(), remoteType, route.getAddress(), address);
    }
    private void checkOwnerAndPrivacy() throws Exception {
        require(owner.asBinder().isBinderAlive() && owner.isCurrent(), "Probe owner no longer current");
        require(manager.getMode() == AudioManager.MODE_NORMAL, "Calls are excluded from this proof");
        require(!manager.isMicrophoneMute() && !ShellMicrophonePrivacy.INSTANCE.isBlocked(context), "Microphone privacy or mute enabled");
    }

    private void stream() {
        byte[] bytes = new byte[BLOCK * 2 + 1];
        short[] output = new short[BLOCK], silence = new short[BLOCK];
        int carry = 0;
        long nextSilenceUs = 0, nextCheck = 0;
        try {
            while (!lease.stopped.get()) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextCheck) {
                    checkOwnerAndPrivacy();
                    if (recordStarted) require(recordRouteMatches(), "Virtual receiver route changed");
                    nextCheck = now + 50;
                }
                if (readyAt != 0) {
                    if (!withinStream(now, readyAt, firstPcmAt, lastPcmAt)) {
                        if (firstPcmAt == 0) throw new IllegalStateException("No microphone PCM within startup deadline");
                        if (now - lastPcmAt >= GAP_MS) throw new IllegalStateException("Microphone PCM stalled");
                        break;
                    }
                    if (pending.remaining() == 0) {
                        int count;
                        try { count = Os.read(input.getFileDescriptor(), bytes, carry, BLOCK * 2 - carry); }
                        catch (ErrnoException error) {
                            if (error.errno != OsConstants.EAGAIN) throw error;
                            count = -1;
                        }
                        require(count != 0, carry == 0 ? "PCM producer closed" : "Incomplete final PCM sample");
                        if (count > 0) {
                            int available = count + carry, frames = available / 2;
                            if (frames > 0) {
                                pending.load(bytes, frames, now);
                                lastPcmAt = now;
                                if (firstPcmAt == 0) firstPcmAt = now;
                            }
                            carry = available & 1;
                            if (carry != 0) bytes[0] = bytes[available - 1];
                        }
                    }
                    if (pending.remaining() > 0) lease.mutate(() -> pending.drain(
                            (samples, offset, count) -> track.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING),
                            sent, SystemClock.elapsedRealtime(), lease.stopped.get()));
                }
                long nowUs = SystemClock.elapsedRealtimeNanos() / 1000;
                if (firstPcmAt == 0 && nowUs >= nextSilenceUs) {
                    lease.mutate(() -> {
                        int accepted = track.write(silence, 0, BLOCK, AudioTrack.WRITE_NON_BLOCKING);
                        require(accepted >= 0, "Virtual keepalive failed");
                        silenceFrames += accepted;
                        return null;
                    });
                    nextSilenceUs = nowUs + 7500; // 480 physical samples at 64 kHz.
                }
                if (recordStarted) lease.mutate(() -> {
                    require(recordRouteMatches(), "Virtual input changed before read");
                    int count = record.read(output, 0, output.length, AudioRecord.READ_NON_BLOCKING);
                    require(count >= 0, "Virtual input read failed");
                    // Exclude setup silence and the initial mixer ramp from received level statistics.
                    if (firstPcmAt != 0 && SystemClock.elapsedRealtime() - firstPcmAt >= 100) received.add(output, count);
                    return null;
                });
                SystemClock.sleep(1);
            }
        } catch (Exception error) {
            if (!lease.stopped.get()) failure.compareAndSet(null, describe(error));
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
            pending.erase();
            java.util.Arrays.fill(output, (short) 0);
            stop(null);
        }
    }

    private void stop(String error) {
        if (error != null) failure.compareAndSet(null, error);
        lease.stopped.set(true);
        if (cleaning.compareAndSet(false, true)) daemon("root-mic-proof-cleanup", this::cleanup);
    }
    private void cleanup() {
        if (!lease.claimCleanup()) return;
        boolean ok = true, recorderReleased = record == null;
        try { if (record != null && recordStarted) record.stop(); } catch (Exception error) { ok = false; }
        try { if (record != null) record.release(); recorderReleased = true; } catch (Exception error) { ok = false; }
        // Stopping this virtual track also removes its INPUT port. Keep both source and policy
        // until process death if recorder release failed; never reroute a surviving recorder.
        ok &= releaseVirtualInput(recorderReleased,
                () -> { if (track != null && trackStarted) track.stop(); return null; },
                () -> { if (track != null) track.release(); return null; },
                () -> {
                    if (registered && policy != null && manager != null)
                        call(manager, "unregisterAudioPolicy", new Class<?>[]{Class.forName(PREFIX + "AudioPolicy")}, policy);
                    return null;
                });
        try { input.close(); } catch (Exception error) { ok = false; }
        if (linked) try { owner.asBinder().unlinkToDeath(death, 0); } catch (Exception ignored) { }
        try {
            if (manager != null && address != null) {
                long until = SystemClock.elapsedRealtime() + 1000;
                boolean present;
                do {
                    present = false;
                    for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS | AudioManager.GET_DEVICES_OUTPUTS))
                        if (device.getType() == remoteType && address.equals(device.getAddress())) present = true;
                    if (present) SystemClock.sleep(10);
                } while (present && SystemClock.elapsedRealtime() < until);
                ok &= !present;
            }
        } catch (Exception error) { ok = false; }
        endedAt = SystemClock.elapsedRealtime();
        cleanupComplete = ok && recorderReleased;
        if (!cleanupComplete) failure.compareAndSet(null, "Root route cleanup incomplete");
        cleaned.countDown();
    }
    private void awaitCleanup() {
        try { cleaned.await(2000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
    @Override public void close() { stop(null); awaitCleanup(); }
    public Bundle finish() { close(); return snapshot(); }
    public boolean isActive() { return readyAt != 0 && !lease.stopped.get(); }
    public boolean isCleanupComplete() { return cleanupComplete; }
    private Bundle snapshot() {
        Bundle result = new Bundle();
        result.putBoolean("ready", isActive());
        result.putBoolean("success", failure.get() == null && cleanupComplete && sent.frames() > 0 && received.frames() > 0);
        result.putBoolean("cleanupComplete", cleanupComplete);
        result.putBoolean("recordStarted", recordStarted);
        result.putInt("inputType", remoteType); result.putString("inputAddress", address);
        result.putInt("sessionId", session); result.putInt("sourceRate", SOURCE_RATE); result.putInt("mixRate", MIX_RATE);
        result.putInt("receivedRate", MIX_RATE); result.putLong("framesInput", sent.frames()); result.putLong("framesReceived", received.frames());
        result.putLong("silenceFrames", silenceFrames);
        result.putLong("framesReadFromProducer", pending.offeredFrames);
        result.putLong("shortWrites", pending.shortWrites); result.putLong("zeroWrites", pending.zeroWrites);
        result.putLong("pendingMaxAgeMs", pending.maxAgeMs);
        result.putInt("trackBufferFrames", trackBufferFrames); result.putInt("trackCapacityFrames", trackCapacityFrames);
        result.putInt("recordBufferFrames", recordBufferFrames);
        result.putLong("wallMs", firstPcmAt == 0 ? 0 : (endedAt == 0 ? SystemClock.elapsedRealtime() : endedAt) - firstPcmAt);
        result.putString("error", failure.get());
        sent.put(result, "source"); received.put(result, "received");
        result.putInt("audioFilesWritten", 0);
        return result;
    }
}
