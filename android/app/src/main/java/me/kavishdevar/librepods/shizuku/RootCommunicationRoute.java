/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import java.io.FileDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One explicitly selected call. Uses runtime-checked framework contracts, never native offsets. */
@SuppressLint({"MissingPermission", "SoonBlockedPrivateApi"})
public final class RootCommunicationRoute implements AutoCloseable {
    public static final int CELLULAR = 1, VOIP = 2;
    private static final int RATE = 48000, PHYSICAL_RATE = 64000;
    static final int OUTPUT_KEEPALIVE_SAMPLES = 360 * 2; // 7.5 ms of 48 kHz stereo.
    private static final long SETUP_MS = 5000, PCM_GAP_MS = 250, EPOCH_MS = 2000;
    private static final long CLEANUP_MS = 6500, INPUT_QUIET_MS = 12000;
    private static final String POLICY = "android.media.audiopolicy.";
    private final Context context;
    private final ParcelFileDescriptor input;
    private final IPlaybackRecoveryOwner owner;
    private final IHfpCallControl hfpControl;
    private final IBinder suppressionOwner = new Binder();
    private final String peer, targetPackage;
    private final int kind, session;
    private final boolean allowMedia;
    private final String mediaPackage;
    private final Object lock = new Object();
    private final AtomicBoolean stopped = new AtomicBoolean(), cleaning = new AtomicBoolean(), prepared = new AtomicBoolean();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final CountDownLatch cleaned = new CountDownLatch(1);
    private final CountDownLatch finalized = new CountDownLatch(1);
    private final IBinder.DeathRecipient death = () -> { ownerDied = true; stop("Call owner died"); };
    private volatile AudioManager manager;
    private AudioTrack uplink, output;
    private AudioRecord downlink;
    private Object policy;
    private String inputAddress, downlinkAddress;
    private int uid, remoteType, redirectMode, uplinkBufferFrames, downlinkBufferFrames, outputBufferFrames;
    private int downlinkObservedType = -1, downlinkRecordingType = -1, downlinkRecordingMatches;
    private String outputObservedTypes = "[]";
    private int outputCapacityFrames, outputStartThresholdFrames;
    private volatile int outputPlayState;
    private volatile long outputAdmissionHead = -1, outputPlaybackHead, outputWriteCalls, outputZeroWrites, outputShortWrites;
    private volatile long outputAcceptedFrames, outputSilenceFrames, maxOutputWriteUs, maxLoopUs, maxControlCheckUs;
    private volatile long maxActiveOutputWriteUs, maxActiveLoopUs, maxActiveControlCheckUs, maxGuardAgeMs;
    private volatile GuardObservation guardObservation;
    private boolean linked, registered, ownsMode, upStarted, outStarted, downStarted, cleanupClaimed, uplinkAudible;
    private volatile boolean cleanupComplete, ready, inputVerified, outputVerified, downlinkVerified, endedNormally;
    private volatile boolean resourcesStopped, everReady, inputQuiet, ownerDied, destroying, awaitingInputQuiet;
    private HfpCallLease hfp;
    private volatile boolean suppressionAttempted, suppressionActive, suppressionReleased;
    private volatile long suppressionGeneration;
    private CommunicationPcm pcm;
    private CallMediaLease media;
    private String mediaReason = "";
    private boolean mediaActive;
    private long mediaCheckedAt;
    private volatile long readyAt, lastPcmAt, startedAt, endedAt, sourceFrames, downlinkFrames, lastLoopAt;
    private long silenceFrames, shortWrites, zeroWrites, maxPendingMs, headWraps, lastHead, downPendingAt, maxDownPendingMs;
    private int downCount, downOffset;
    private final short[] downSamples = new short[960], silence = new short[480];
    private static final class CallEnded extends Exception { }

    public RootCommunicationRoute(Context context, ParcelFileDescriptor input, IPlaybackRecoveryOwner owner,
            String peer, int kind, String targetPackage, int session, boolean allowMedia, String mediaPackage,
            IHfpCallControl hfpControl) {
        this.context = context; this.input = input; this.owner = owner; this.peer = peer;
        this.kind = kind; this.targetPackage = targetPackage; this.session = session;
        this.allowMedia = allowMedia; this.mediaPackage = mediaPackage;
        this.hfpControl = hfpControl;
    }

    static boolean sameRecording(int expectedUid, int expectedSession, int actualUid, int actualSession, int source) {
        return expectedUid >= 10000 && expectedSession > 0 && expectedUid == actualUid &&
                expectedSession == actualSession && source == MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    }
    static boolean callActive(int kind, int mode, boolean telecomCall, int phoneState, int redirect) {
        return kind == CELLULAR ? telecomCall && phoneState == TelephonyManager.CALL_STATE_OFFHOOK &&
                (mode == AudioManager.MODE_IN_CALL || mode == redirect) : kind == VOIP &&
                (mode == AudioManager.MODE_IN_COMMUNICATION || mode == redirect);
    }
    static boolean ownRemote(AudioDeviceInfo device, int type, String address) {
        return device != null && device.isSource() && device.getType() == type && address != null &&
                !address.isEmpty() && address.equals(device.getAddress());
    }
    static boolean mayRestoreHfp(boolean resourcesStopped, boolean everReady, boolean inputQuiet, boolean ownerDied) {
        return resourcesStopped && (!everReady || inputQuiet || ownerDied);
    }
    static boolean ownPhoneDownlink(int expectedSession, int actualUid, int actualSession, int source) {
        return expectedSession > 0 && actualUid == 0 && expectedSession == actualSession &&
                source == MediaRecorder.AudioSource.VOICE_DOWNLINK;
    }
    static boolean outputHeadAdvanced(long start, long current) {
        long delta = (current - start) & 0xffff_ffffL;
        return start >= 0 && delta > 0 && delta < 0x8000_0000L;
    }
    interface RoutePoll { boolean ready() throws Exception; }
    interface RouteClock { long now(); void pause() throws Exception; }
    static boolean awaitRouteAdmission(long deadline, RoutePoll poll, RouteClock clock) throws Exception {
        while (clock.now() < deadline) {
            if (poll.ready()) return clock.now() < deadline;
            clock.pause();
        }
        return false;
    }
    static final class GuardObservation {
        final long startedMs;
        final boolean muted;
        GuardObservation(long startedMs, boolean muted) { this.startedMs = startedMs; this.muted = muted; }
        boolean fresh(long now) { return now >= startedMs && now - startedMs <= PCM_GAP_MS; }
    }
    private static void require(boolean condition, String message) { CommunicationPcm.require(condition, message); }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    private static Object get(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }
    private static int constant(Class<?> type, String name) throws Exception { return type.getField(name).getInt(null); }
    private static String describe(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
    private static void daemon(String name, Runnable action) { Thread t = new Thread(action, name); t.setDaemon(true); t.start(); }
    private <T> T mutate(Callable<T> action) throws Exception {
        synchronized (lock) { require(!stopped.get() && !cleanupClaimed, "Call owner retired"); return action.call(); }
    }
    private static AudioManager manager(Context context) throws Exception {
        var constructor = AudioManager.class.getDeclaredConstructor(Context.class); constructor.setAccessible(true);
        return constructor.newInstance(context);
    }
    private static int rawMode(AudioManager manager) throws Exception {
        var service = AudioManager.class.getDeclaredMethod("getService"); service.setAccessible(true);
        return (Integer) get(service.invoke(null), "getMode");
    }
    private static int recordingUid(AudioRecordingConfiguration value) throws Exception {
        return (Integer) get(value, "getClientUid");
    }
    private static void permissions(Context context) {
        require(Process.myUid() == 0 && Build.VERSION.SDK_INT >= 33, "Root call routing is unavailable");
        for (String permission : new String[]{"MODIFY_AUDIO_ROUTING", "RECORD_AUDIO", "CAPTURE_AUDIO_OUTPUT",
                "CALL_AUDIO_INTERCEPTION", "MODIFY_PHONE_STATE", "OBSERVE_SENSOR_PRIVACY"})
            require(context.checkPermission("android.permission." + permission, Process.myPid(), 0) == PackageManager.PERMISSION_GRANTED,
                    "Missing call routing permission: " + permission);
    }
    private static String resolvedPackage(Context context, int kind, String target) {
        if (kind == CELLULAR) {
            String dialer = context.getSystemService(TelecomManager.class).getDefaultDialerPackage();
            require(dialer != null && !dialer.isEmpty() && (target.isEmpty() || target.equals(dialer)), "Default Phone app is unavailable");
            return dialer;
        }
        require(kind == VOIP && (target.equals("cocobo1.pupu.app") || target.equals("com.discord")), "Unsupported calling app");
        return target;
    }
    private static AudioDeviceInfo selectedOutput(AudioManager manager, String address) {
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && device.getAddress().equalsIgnoreCase(address)) return device;
        return null;
    }
    private static void requireSelectedBond(Context context, String peer) {
        var adapter = ShellBluetooth.INSTANCE.adapter(context);
        require(adapter.isEnabled(), "Bluetooth is off");
        BluetoothDevice selected = adapter.getRemoteDevice(peer);
        boolean aacp = false;
        if (selected.getUuids() != null) for (var uuid : selected.getUuids())
            aacp |= uuid.toString().equalsIgnoreCase(A2dpRecoveryGuard.AACP_UUID);
        require(selected.getBondState() == BluetoothDevice.BOND_BONDED && aacp &&
                (selected.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC || selected.getType() == BluetoothDevice.DEVICE_TYPE_DUAL),
                "Selected AirPods bond is unavailable");
    }

    /** No audio objects, policy registrations, modes or settings are changed by this query. */
    public static Bundle inspect(Context context, String peer, int kind, String target) {
        Bundle result = new Bundle(); result.putInt("kind", kind); result.putInt("session", 0);
        try {
            permissions(context);
            requireSelectedBond(context, peer);
            String selected = resolvedPackage(context, kind, target);
            int uid = context.getPackageManager().getApplicationInfo(selected, 0).uid;
            require(uid >= 10000, "Calling app identity is unavailable");
            result.putString("package", selected); result.putInt("uid", uid);
            AudioManager manager = manager(context);
            require(!ShellMicrophonePrivacy.INSTANCE.isBlocked(context), "Microphone privacy is enabled");
            int redirect = constant(AudioManager.class, kind == CELLULAR ? "MODE_CALL_REDIRECT" : "MODE_COMMUNICATION_REDIRECT");
            require(callActive(kind, rawMode(manager), context.getSystemService(TelecomManager.class).isInCall(),
                    context.getSystemService(TelephonyManager.class).getCallState(), redirect), "No active selected call");
            require(selectedOutput(manager, peer) != null, "Selected AirPods stereo output is unavailable");
            if (kind == CELLULAR) {
                require((Boolean) get(manager, "isPstnCallAudioInterceptable"), "Phone call interception is unavailable");
                boolean rx = false, tx = false;
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS | AudioManager.GET_DEVICES_OUTPUTS))
                    if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) { rx |= device.isSource(); tx |= device.isSink(); }
                require(rx && tx, "Phone call audio ports are unavailable");
            } else {
                int found = 0, matches = 0;
                for (AudioRecordingConfiguration recording : manager.getActiveRecordingConfigurations()) {
                    if (recordingUid(recording) == uid && recording.getClientAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                        found = recording.getClientAudioSessionId(); matches++;
                    }
                }
                require(matches == 1 && found > 0, "No unique voice recording session for the selected app");
                result.putInt("session", found);
                Class.forName(POLICY + "AudioMixingRule").getField("RULE_MATCH_AUDIO_SESSION_ID");
            }
            result.putBoolean("eligible", true); result.putString("reason", "");
        } catch (Exception error) { result.putBoolean("eligible", false); result.putString("reason", describe(error)); }
        return result;
    }

    public Bundle prepare() {
        require(prepared.compareAndSet(false, true), "Call route cannot be reused");
        startedAt = SystemClock.elapsedRealtime();
        daemon("call-route-watchdog", () -> {
            while (!stopped.get()) {
                if (!ready && SystemClock.elapsedRealtime() - startedAt > SETUP_MS) {
                    stop("Call route setup timed out; outputTypes=" + outputObservedTypes + " downlinkType=" + downlinkObservedType +
                            " recordingType=" + downlinkRecordingType + " matches=" + downlinkRecordingMatches +
                            " head=" + outputPlaybackHead + " initialHead=" + outputAdmissionHead + " playState=" + outputPlayState +
                            " accepted=" + outputAcceptedFrames + " writes=" + outputWriteCalls + " zeros=" + outputZeroWrites);
                    break;
                }
                if (ready && SystemClock.elapsedRealtime() - lastLoopAt > 1000) { stop("Call route worker stalled"); break; }
                GuardObservation observed = guardObservation;
                if (ready && (observed == null || !observed.fresh(SystemClock.elapsedRealtime()))) {
                    stop("Call owner and route validation expired"); break;
                }
                SystemClock.sleep(25);
            }
            awaitResources(CLEANUP_MS);
            // All audio resources belong to this helper. Its death releases its policies and mode.
            if (!resourcesStopped) { Process.killProcess(Process.myPid()); return; }
            long quietUntil = SystemClock.elapsedRealtime() + INPUT_QUIET_MS;
            while (everReady && !inputQuiet && !ownerDied && !destroying &&
                    SystemClock.elapsedRealtime() < quietUntil) {
                if (!owner.asBinder().isBinderAlive()) ownerDied = true;
                SystemClock.sleep(25);
            }
            awaitingInputQuiet = false;
            // An AACP stream can outlive a closed producer FD. Never restore HFP on EOF alone.
            if (!mayRestoreHfp(resourcesStopped, everReady, inputQuiet, ownerDied)) {
                try { if (hfp != null) hfp.close(); }
                catch (Exception error) { failure.compareAndSet(null, "Hands-free lease cleanup was not confirmed"); }
                finally {
                    failure.compareAndSet(null, "Microphone quiet was not confirmed before hands-free restoration");
                    unlinkOwner();
                    endedAt = SystemClock.elapsedRealtime();
                    finalized.countDown();
                }
            } else {
                daemon("call-hfp-restore", () -> {
                    try { cleanupComplete = hfp == null || hfp.restore(); }
                    catch (Exception error) { cleanupComplete = false; }
                    finally {
                        if (!cleanupComplete) failure.compareAndSet(null, "Hands-free restoration was not confirmed");
                        unlinkOwner();
                        endedAt = SystemClock.elapsedRealtime();
                        finalized.countDown();
                    }
                });
                awaitFinal();
                if (finalized.getCount() != 0) Process.killProcess(Process.myPid());
            }
        });
        try {
            permissions(context);
            Bundle admission = inspect(context, peer, kind, targetPackage);
            require(admission.getBoolean("eligible"), admission.getString("reason"));
            uid = admission.getInt("uid");
            require(admission.getInt("session") == session && (kind != CELLULAR || session == 0), "Call session changed before preparation");
            validateInput();
            mutate(() -> { owner.asBinder().linkToDeath(death, 0); linked = true; manager = manager(context); return null; });
            redirectMode = constant(AudioManager.class, kind == CELLULAR ? "MODE_CALL_REDIRECT" : "MODE_COMMUNICATION_REDIRECT");
            remoteType = constant(AudioDeviceInfo.class, "TYPE_REMOTE_SUBMIX");
            checkOwner();
            // Read-only HFP inspection also rejects a competing headset or unknown active owner.
            require(new NativeCallConnection(context, peer, owner).run(false) != 0,
                    "The selected headset is not the only call-audio owner");
            pcm = new CommunicationPcm(manager.isMicrophoneMute(), SystemClock.elapsedRealtime());
            if (allowMedia) mutate(() -> {
                try {
                    require(mediaPackage != null && !mediaPackage.isEmpty(), "Choose a media player before the call");
                    require(context.getPackageManager().getApplicationInfo(mediaPackage, 0).uid != uid,
                            "The calling app cannot also own media isolation");
                    media = CallMediaLease.acquire(context, peer, mediaPackage, owner);
                    mediaReason = media == null ? "The selected media player is unavailable" : media.getFailure();
                    mediaActive = media != null && media.isActive();
                } catch (Exception error) { mediaReason = describe(error); }
                return null;
            });
            // AudioManager has its own mode-owner Binder; releasing it cannot remove Telecom's owner.
            mutate(() -> { ownsMode = true; manager.setMode(redirectMode); return null; });
            require(rawMode(manager) == redirectMode, "Call redirect mode was not accepted");
            if (kind == CELLULAR) createCellular(); else createVoip();
            require(uplink != null && uplink.getState() == AudioTrack.STATE_INITIALIZED && downlink != null &&
                    downlink.getState() == AudioRecord.STATE_INITIALIZED, "Call streams are unavailable");
            require(uplink.getSampleRate() == RATE && uplink.getChannelCount() == 1 && uplink.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT &&
                    downlink.getSampleRate() == RATE && downlink.getChannelCount() == 2 && downlink.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT,
                    "Unexpected call stream format");
            uplinkBufferFrames = uplink.getBufferSizeInFrames(); downlinkBufferFrames = downlink.getBufferSizeInFrames();
            require(uplink.setPlaybackRate(PHYSICAL_RATE) == AudioTrack.SUCCESS && uplink.getPlaybackRate() == PHYSICAL_RATE,
                    "Physical microphone clock was not accepted");
            require(uplink.setVolume(0) == AudioTrack.SUCCESS, "Microphone mute gate failed");
            createOutput();
            mutate(() -> { upStarted = true; uplink.play(); outStarted = true; output.play(); return null; });
            daemon("call-route-audio", this::stream);
            daemon("call-route-guard", this::guard);
            mutate(() -> { hfp = HfpCallLease.open(context, peer,
                    () -> !stopped.get() && owner.asBinder().isBinderAlive() && owner.isCurrent(), suppression()); return null; });
            hfp.acquire();
            long until = startedAt + SETUP_MS;
            waitForRoutes(until, "before recording");
            mutate(() -> { downStarted = true; downlink.startRecording(); return null; });
            require(downlink.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING, "Call downlink did not start");
            waitForRoutes(until, "after recording");
            waitForOutputProgress(until);
            mutate(() -> {
                GuardObservation observed = requireFreshGuard();
                pcm.setMute(pcm.explicitMute, observed.muted, SystemClock.elapsedRealtime());
                require(output.setVolume(1) == AudioTrack.SUCCESS, "Call output gate failed");
                readyAt = SystemClock.elapsedRealtime(); lastLoopAt = readyAt; everReady = true; ready = true;
                return null;
            });
            return snapshot();
        } catch (Exception error) { failure.compareAndSet(null, describe(error)); close(); return snapshot(); }
    }

    private HfpCallLease.Suppression suppression() {
        return new HfpCallLease.Suppression() {
            public void acquire() throws Exception {
                require(hfpControl != null && hfpControl.asBinder().isBinderAlive(), "Hands-free call isolation is unavailable");
                // A lost reply can still leave the exact root token installed in Bluetooth.
                suppressionAttempted = true;
                Bundle receipt = hfpControl.acquire(peer, suppressionOwner, owner.asBinder());
                if (receipt != null && receipt.containsKey("owned") && !receipt.getBoolean("owned"))
                    suppressionAttempted = false; // Explicit rejection: this token was never installed.
                require(receipt != null && receipt.getBoolean("owned") && HfpCallLease.suppressionMatches(peer, 0,
                        receipt.getBoolean("active"), receipt.getString("peer"), receipt.getLong("generation")),
                        "Hands-free call isolation was not confirmed");
                suppressionGeneration = receipt.getLong("generation"); suppressionActive = true;
            }
            public void check() throws Exception {
                Bundle receipt = hfpControl.state(suppressionOwner);
                suppressionActive = receipt != null && receipt.getBoolean("owned") && HfpCallLease.suppressionMatches(peer, suppressionGeneration,
                        receipt.getBoolean("active"), receipt.getString("peer"), receipt.getLong("generation"));
                require(suppressionActive, "Hands-free call isolation ended");
            }
            public boolean release() {
                if (!suppressionAttempted || suppressionReleased) return true;
                try {
                    Bundle receipt = hfpControl.release(suppressionOwner);
                    suppressionReleased = receipt != null && receipt.getBoolean("released") && !receipt.getBoolean("active", true) &&
                            receipt.getLong("generation") > 0 && (suppressionGeneration == 0 ||
                            receipt.getLong("generation") == suppressionGeneration);
                    if (suppressionReleased) suppressionActive = false;
                    return suppressionReleased;
                } catch (Exception unknown) { return false; }
            }
        };
    }

    private void waitForOutputProgress(long until) throws Exception {
        synchronized (lock) {
            sampleOutputProgress();
            outputBufferFrames = output.getBufferSizeInFrames();
            outputCapacityFrames = output.getBufferCapacityInFrames();
            outputStartThresholdFrames = output.getStartThresholdInFrames();
            outputAdmissionHead = outputPlaybackHead;
        }
        boolean progressed = awaitRouteAdmission(until, () -> {
            GuardObservation observed = observeGuard();
            synchronized (lock) {
                require(!stopped.get(), "Call route stopped before playback advanced");
                sampleOutputProgress();
                if (observed == null || !observed.fresh(SystemClock.elapsedRealtime()) || outputPlayState != AudioTrack.PLAYSTATE_PLAYING ||
                        !outputHeadAdvanced(outputAdmissionHead, outputPlaybackHead)) return false;
                guardObservation = observed;
                return true;
            }
        }, new RouteClock() {
            public long now() { return SystemClock.elapsedRealtime(); }
            public void pause() { SystemClock.sleep(10); }
        });
        require(progressed, "Call stereo output did not advance; head=" + outputPlaybackHead + " initialHead=" + outputAdmissionHead +
                " playState=" + outputPlayState + " accepted=" + outputAcceptedFrames + " writes=" + outputWriteCalls +
                " zeros=" + outputZeroWrites + " types=" + outputObservedTypes);
    }

    private void waitForRoutes(long until, String stage) throws Exception {
        boolean admitted = awaitRouteAdmission(until, () -> {
            return observeGuard() != null;
        }, new RouteClock() {
            public long now() { return SystemClock.elapsedRealtime(); }
            public void pause() { SystemClock.sleep(10); }
        });
        require(inputVerified, "The calling app did not select the owned virtual microphone " + stage);
        require(outputVerified, "Call stereo output route rejected " + stage + "; types=" + outputObservedTypes);
        require(downlinkVerified, "Call downlink route rejected " + stage + "; type=" + downlinkObservedType +
                " recordingType=" + downlinkRecordingType + " matches=" + downlinkRecordingMatches);
        require(!manager.isBluetoothScoOn(), "Android kept hands-free audio active");
        require(admitted, "Call route readiness timed out " + stage);
    }

    /** All system-service and Bluetooth queries stay outside the PCM/mute lock. */
    private GuardObservation observeGuard() throws Exception {
        long startedUs = SystemClock.elapsedRealtimeNanos() / 1000;
        try {
            checkOwner();
            require(!stopped.get(), "Call route stopped during validation");
            hfp.checkIdle();
            inputVerified = inputRouteMatches(); outputVerified = outputRouteMatches(); downlinkVerified = downlinkRouteMatches();
            boolean sco = manager.isBluetoothScoOn(), muted = manager.isMicrophoneMute();
            GuardObservation observed = new GuardObservation(startedUs / 1000, muted);
            return inputVerified && outputVerified && downlinkVerified && !sco && observed.fresh(SystemClock.elapsedRealtime()) ? observed : null;
        } finally { recordControlTime(startedUs); }
    }
    private void recordControlTime(long startedUs) {
        long elapsed = SystemClock.elapsedRealtimeNanos() / 1000 - startedUs;
        maxControlCheckUs = Math.max(maxControlCheckUs, elapsed);
        if (readyAt > 0 && startedUs >= readyAt * 1000) maxActiveControlCheckUs = Math.max(maxActiveControlCheckUs, elapsed);
    }
    private GuardObservation requireFreshGuard() {
        GuardObservation observed = guardObservation;
        long now = SystemClock.elapsedRealtime();
        if (observed != null && now >= observed.startedMs) maxGuardAgeMs = Math.max(maxGuardAgeMs, now - observed.startedMs);
        require(!stopped.get() && observed != null && observed.fresh(now), "Call owner and route validation expired");
        return observed;
    }
    private void guard() {
        try {
            while (!stopped.get()) {
                if (!ready) { SystemClock.sleep(10); continue; }
                long startedUs = SystemClock.elapsedRealtimeNanos() / 1000;
                try {
                    GuardObservation observed = observeGuard();
                    require(observed != null, "Call owner or audio route changed; outputTypes=" + outputObservedTypes +
                            " downlinkType=" + downlinkObservedType);
                    mutate(() -> {
                        require(ready && observed.fresh(SystemClock.elapsedRealtime()), "Call validation expired before publication");
                        guardObservation = observed;
                        if (pcm.setMute(pcm.explicitMute, observed.muted, SystemClock.elapsedRealtime())) {
                            gateUplink(false); lastPcmAt = 0;
                        }
                        return null;
                    });
                    // Optional media work cannot refresh the preceding owner/privacy observation.
                    checkMedia(SystemClock.elapsedRealtime());
                } finally { recordControlTime(startedUs); }
                SystemClock.sleep(40);
            }
        } catch (CallEnded ended) { endedNormally = true; }
        catch (Exception error) { if (!stopped.get()) failure.compareAndSet(null, describe(error)); }
        finally { stop(null); }
    }

    private void validateInput() throws Exception {
        var option = Os.class.getMethod("getsockoptInt", FileDescriptor.class, int.class, int.class);
        require((Integer) option.invoke(null, input.getFileDescriptor(), OsConstants.SOL_SOCKET, OsConstants.SO_TYPE) == OsConstants.SOCK_STREAM,
                "Expected owned call PCM stream");
        int capacity = (Integer) option.invoke(null, input.getFileDescriptor(), OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF);
        require(capacity > 0 && capacity <= 8192 && (Os.fcntlInt(input.getFileDescriptor(), OsConstants.F_GETFL, 0) & OsConstants.O_NONBLOCK) != 0,
                "Call PCM stream must be bounded and nonblocking");
    }
    private AudioFormat format(boolean input, boolean stereo) {
        return new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(input ? (stereo ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO) :
                        (stereo ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO)).build();
    }
    private void createCellular() throws Exception {
        AudioAttributes.Builder upAttrs = new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        call(upAttrs, "setSystemUsage", new Class<?>[]{int.class}, constant(AudioAttributes.class, "USAGE_CALL_ASSISTANT"));
        AudioTrack.Builder upBuilder = new AudioTrack.Builder().setAudioAttributes(upAttrs.build()).setAudioFormat(format(false, false));
        call(upBuilder, "setCallRedirectionMode", new Class<?>[]{int.class}, constant(AudioManager.class, "CALL_REDIRECT_PSTN"));
        AudioAttributes.Builder downAttrs = new AudioAttributes.Builder();
        call(downAttrs, "setInternalCapturePreset", new Class<?>[]{int.class}, MediaRecorder.AudioSource.VOICE_DOWNLINK);
        AudioRecord.Builder downBuilder = new AudioRecord.Builder().setAudioFormat(format(true, true));
        call(downBuilder, "setAudioAttributes", new Class<?>[]{AudioAttributes.class}, downAttrs.build());
        call(downBuilder, "setCallRedirectionMode", new Class<?>[]{int.class}, constant(AudioManager.class, "CALL_REDIRECT_PSTN"));
        call(downBuilder, "setContext", new Class<?>[]{Context.class}, context);
        mutate(() -> {
            uplink = upBuilder.build();
            downlink = downBuilder.build();
            return null;
        });
    }
    private Object mix(boolean injector) throws Exception {
        Class<?> ruleType = Class.forName(POLICY + "AudioMixingRule");
        Object builder = Class.forName(POLICY + "AudioMixingRule$Builder").getConstructor().newInstance();
        call(builder, "setTargetMixRole", new Class<?>[]{int.class}, constant(ruleType, injector ? "MIX_ROLE_INJECTOR" : "MIX_ROLE_PLAYERS"));
        call(builder, "addMixRule", new Class<?>[]{int.class, Object.class}, constant(ruleType, "RULE_MATCH_UID"), uid);
        if (injector) {
            call(builder, "addMixRule", new Class<?>[]{int.class, Object.class}, constant(ruleType, "RULE_MATCH_AUDIO_SESSION_ID"), session);
            AudioAttributes.Builder attrs = new AudioAttributes.Builder();
            call(attrs, "setCapturePreset", new Class<?>[]{int.class}, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            call(attrs, "setForCallRedirection", new Class<?>[0]);
            call(builder, "addMixRule", new Class<?>[]{int.class, Object.class}, constant(ruleType, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET"), attrs.build());
        } else for (int usage : new int[]{AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING}) {
            AudioAttributes.Builder attrs = new AudioAttributes.Builder().setUsage(usage);
            call(attrs, "setForCallRedirection", new Class<?>[0]);
            call(builder, "addMixRule", new Class<?>[]{int.class, Object.class}, constant(ruleType, "RULE_MATCH_ATTRIBUTE_USAGE"), attrs.build());
        }
        Object rule = get(builder, "build");
        List<?> criteria = (List<?>) get(rule, "getCriteria");
        require(criteria.size() == 3 && (Boolean) get(rule, "isForCallRedirection"), "Incomplete call mix scope");
        int seen = 0;
        for (Object criterion : criteria) {
            int type = (Integer) get(criterion, "getRule");
            if (type == constant(ruleType, "RULE_MATCH_UID") && (Integer) get(criterion, "getIntProp") == uid) seen |= 1;
            else if (injector && type == constant(ruleType, "RULE_MATCH_AUDIO_SESSION_ID") &&
                    (Integer) get(criterion, "getIntProp") == session) seen |= 2;
            else if (injector && type == constant(ruleType, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET") &&
                    (Integer) get(get(criterion, "getAudioAttributes"), "getCapturePreset") == MediaRecorder.AudioSource.VOICE_COMMUNICATION) seen |= 4;
            else if (!injector && type == constant(ruleType, "RULE_MATCH_ATTRIBUTE_USAGE")) {
                int usage = ((AudioAttributes) get(criterion, "getAudioAttributes")).getUsage();
                require(usage == AudioAttributes.USAGE_VOICE_COMMUNICATION || usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
                        "Unexpected call playback usage");
                seen |= usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ? 2 : 4;
            } else throw new IllegalStateException("Unexpected call mix criterion");
        }
        require(seen == 7, "Call mix does not match the exact requested owner");
        Class<?> mixType = Class.forName(POLICY + "AudioMix");
        Object builderMix = Class.forName(POLICY + "AudioMix$Builder").getConstructor(ruleType).newInstance(rule);
        call(builderMix, "setFormat", new Class<?>[]{AudioFormat.class}, format(false, !injector));
        call(builderMix, "setRouteFlags", new Class<?>[]{int.class}, constant(mixType, "ROUTE_FLAG_LOOP_BACK"));
        return get(builderMix, "build");
    }
    private void createVoip() throws Exception {
        Object inputMix = mix(true), outputMix = mix(false);
        Class<?> mixType = Class.forName(POLICY + "AudioMix");
        Object builder = Class.forName(POLICY + "AudioPolicy$Builder").getConstructor(Context.class).newInstance(context);
        call(builder, "addMix", new Class<?>[]{mixType}, inputMix); call(builder, "addMix", new Class<?>[]{mixType}, outputMix);
        Object made = get(builder, "build");
        mutate(() -> {
            policy = made; registered = true;
            require((Integer) call(manager, "registerAudioPolicy", new Class<?>[]{made.getClass()}, made) == 0, "Call policy registration failed");
            inputAddress = (String) get(inputMix, "getRegistration"); downlinkAddress = (String) get(outputMix, "getRegistration");
            require(inputAddress != null && !inputAddress.isEmpty() && downlinkAddress != null && !downlinkAddress.isEmpty() &&
                    !inputAddress.equals(downlinkAddress), "Invalid owned call policy addresses");
            uplink = (AudioTrack) call(policy, "createAudioTrackSource", new Class<?>[]{mixType}, inputMix);
            downlink = (AudioRecord) call(policy, "createAudioRecordSink", new Class<?>[]{mixType}, outputMix);
            return null;
        });
    }
    private void createOutput() throws Exception {
        AudioDeviceInfo selected = selectedOutput(manager, peer);
        require(selected != null, "Selected AirPods stereo output disappeared");
        int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        require(minimum > 0, "Call stereo output format is unavailable");
        mutate(() -> {
            output = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(format(false, true)).setBufferSizeInBytes(minimum).setTransferMode(AudioTrack.MODE_STREAM).build();
            require(output.getState() == AudioTrack.STATE_INITIALIZED && output.setPreferredDevice(selected) &&
                    output.setVolume(0) == AudioTrack.SUCCESS, "Call stereo output preference failed");
            outputBufferFrames = output.getBufferSizeInFrames();
            outputCapacityFrames = output.getBufferCapacityInFrames();
            outputStartThresholdFrames = output.getStartThresholdInFrames();
            return null;
        });
    }
    private void sampleOutputProgress() {
        outputPlaybackHead = Integer.toUnsignedLong(output.getPlaybackHeadPosition());
        outputPlayState = output.getPlayState();
    }
    private int writeOutput(short[] samples, int offset, int count, boolean startup) {
        require(!stopped.get(), "Call route stopped before playback write");
        if (ready) requireFreshGuard();
        long startedUs = SystemClock.elapsedRealtimeNanos() / 1000;
        int written = output.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING);
        long elapsedUs = SystemClock.elapsedRealtimeNanos() / 1000 - startedUs;
        maxOutputWriteUs = Math.max(maxOutputWriteUs, elapsedUs);
        if (readyAt > 0 && startedUs >= readyAt * 1000) maxActiveOutputWriteUs = Math.max(maxActiveOutputWriteUs, elapsedUs);
        outputWriteCalls++;
        if (written == 0) outputZeroWrites++;
        else if (written > 0 && written <= count) {
            if (written < count) outputShortWrites++;
            outputAcceptedFrames += written / 2;
            if (startup) outputSilenceFrames += written / 2;
        }
        sampleOutputProgress();
        return written;
    }
    private boolean outputRouteMatches() throws Exception {
        List<?> routes = (List<?>) get(output, "getRoutedDevices");
        if (routes.isEmpty()) { outputObservedTypes = "[]"; return false; }
        for (Object value : routes) {
            AudioDeviceInfo device = (AudioDeviceInfo) value;
            if (!device.isSink() || device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || !peer.equalsIgnoreCase(device.getAddress())) {
                StringBuilder types = new StringBuilder("[");
                for (Object observed : routes) {
                    if (types.length() > 1) types.append(',');
                    types.append(((AudioDeviceInfo) observed).getType());
                }
                outputObservedTypes = types.append(']').toString();
                return false;
            }
        }
        outputObservedTypes = "[8]"; // All observed sinks are the selected A2DP device.
        return true;
    }
    private boolean inputRouteMatches() throws Exception {
        if (kind == CELLULAR) {
            AudioDeviceInfo device = uplink.getRoutedDevice();
            return device != null && device.isSink() && device.getType() == AudioDeviceInfo.TYPE_TELEPHONY;
        }
        for (AudioRecordingConfiguration recording : manager.getActiveRecordingConfigurations())
            if (sameRecording(uid, session, recordingUid(recording), recording.getClientAudioSessionId(), recording.getClientAudioSource()))
                return ownRemote(recording.getAudioDevice(), remoteType, inputAddress);
        return false;
    }
    private boolean downlinkRouteMatches() throws Exception {
        AudioDeviceInfo route = downlink.getRoutedDevice();
        downlinkObservedType = route == null ? -1 : route.getType();
        if (kind == CELLULAR) {
            boolean matching = route != null && route.isSource() && route.getType() == AudioDeviceInfo.TYPE_TELEPHONY &&
                    downlink.getAudioSource() == MediaRecorder.AudioSource.VOICE_DOWNLINK;
            // Diagnose the exact active recorder on rejection; do not add per-buffer Binder queries.
            if (!matching && downStarted) {
                try { observePhoneRecording(); }
                catch (Exception unavailable) { downlinkRecordingMatches = 0; downlinkRecordingType = -2; }
            }
            return matching;
        }
        if (!ownRemote(route, remoteType, downlinkAddress)) return false;
        int matched = 0;
        for (AudioPlaybackConfiguration playback : manager.getActivePlaybackConfigurations()) {
            if (!(Boolean) get(playback, "isActive") || (Integer) get(playback, "getClientUid") != uid) continue;
            int usage = playback.getAudioAttributes().getUsage();
            if (usage != AudioAttributes.USAGE_VOICE_COMMUNICATION && usage != AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING) continue;
            AudioDeviceInfo actual = playback.getAudioDeviceInfo();
            if (actual == null || !actual.isSink() || actual.getType() != remoteType || !downlinkAddress.equals(actual.getAddress())) return false;
            matched++;
        }
        return matched > 0;
    }
    private void observePhoneRecording() throws Exception {
        downlinkRecordingMatches = 0; downlinkRecordingType = -1;
        for (AudioRecordingConfiguration recording : manager.getActiveRecordingConfigurations()) {
            if (!ownPhoneDownlink(downlink.getAudioSessionId(), recordingUid(recording),
                    recording.getClientAudioSessionId(), recording.getClientAudioSource())) continue;
            downlinkRecordingMatches++;
            AudioDeviceInfo actual = recording.getAudioDevice();
            downlinkRecordingType = actual == null ? -1 : actual.getType();
        }
    }
    private void checkOwner() throws Exception {
        int mode = rawMode(manager);
        boolean inCall = context.getSystemService(TelecomManager.class).isInCall();
        int phoneState = context.getSystemService(TelephonyManager.class).getCallState();
        if (ready && kind == CELLULAR && !inCall && phoneState == TelephonyManager.CALL_STATE_IDLE) throw new CallEnded();
        require(owner.asBinder().isBinderAlive() && owner.isCurrent(), "Call owner is no longer current");
        require(!ShellMicrophonePrivacy.INSTANCE.isBlocked(context), "Microphone privacy is enabled");
        require(callActive(kind, mode, inCall, phoneState, redirectMode), "Selected call mode changed");
        if (kind == VOIP) {
            int count = 0;
            for (AudioRecordingConfiguration recording : manager.getActiveRecordingConfigurations())
                if (sameRecording(uid, session, recordingUid(recording), recording.getClientAudioSessionId(), recording.getClientAudioSource())) count++;
            if (ready && count == 0) throw new CallEnded();
            require(count == 1, "Selected voice recording session is unavailable");
        }
    }
    private long playbackHead() {
        long head = Integer.toUnsignedLong(uplink.getPlaybackHeadPosition());
        if (head < lastHead) {
            require(lastHead > 0xf0000000L && head < 0x10000000L, "Call playback clock was reset");
            headWraps += 1L << 32;
        }
        lastHead = head;
        return headWraps + head;
    }
    public boolean setMuted(boolean muted) {
        try {
            checkOwner();
            return mutate(() -> {
                GuardObservation observed = requireFreshGuard();
                if (pcm.setMute(muted, observed.muted, SystemClock.elapsedRealtime())) {
                    gateUplink(false);
                    lastPcmAt = 0;
                }
                return true;
            });
        } catch (CallEnded ended) { endedNormally = true; stop(null); return false; }
        catch (Exception error) { stop(describe(error)); return false; }
    }
    /** AudioTrack gain changes only at a mute/epoch edge, not on every nonblocking poll. */
    private void gateUplink(boolean audible) {
        if (uplinkAudible == audible) return;
        require(uplink.setVolume(audible ? 1 : 0) == AudioTrack.SUCCESS, "Call mute gate failed");
        uplinkAudible = audible;
    }
    private void stream() {
        long nextSilence = 0;
        try {
            while (!stopped.get()) {
                long loopStartedUs = SystemClock.elapsedRealtimeNanos() / 1000;
                try {
                    mutate(() -> {
                        if (ready) requireFreshGuard();
                        long now = SystemClock.elapsedRealtime();
                        if (ready && pcm.remaining() == 0) {
                            int count;
                            try { count = Os.read(input.getFileDescriptor(), pcm.bytes, pcm.readOffset(), pcm.readLength()); }
                            catch (ErrnoException error) { if (error.errno != OsConstants.EAGAIN) throw error; count = -1; }
                            require(count != 0, "Call PCM producer ended");
                            if (count > 0 && pcm.consume(count, now)) lastPcmAt = now;
                        }
                        if (pcm.remaining() > 0) {
                            requireFreshGuard();
                            maxPendingMs = Math.max(maxPendingMs, now - pcm.pendingAt);
                            require(now - pcm.pendingAt < PCM_GAP_MS, "Call uplink stalled");
                            int requested = pcm.remaining();
                            int accepted = uplink.write(pcm.samples, pcm.offset, requested, AudioTrack.WRITE_NON_BLOCKING);
                            require(accepted >= 0 && accepted <= requested, "Call uplink rejected samples");
                            if (accepted == 0) zeroWrites++; else if (accepted < requested) shortWrites++;
                            pcm.accepted(accepted); sourceFrames += accepted;
                        }
                        if (ready && !pcm.muted()) {
                            require(pcm.fresh || now - Math.max(readyAt, pcm.epochChangedAt) < EPOCH_MS, "Call microphone epoch was not acknowledged");
                            if (pcm.fresh) require(now - lastPcmAt < PCM_GAP_MS, "Call microphone PCM stopped");
                        }
                        if (ready) requireFreshGuard();
                        gateUplink(ready && pcm.mayUnmute(playbackHead()));
                        if (ready && downStarted) relayDownlink();
                        return null;
                    });
                    long nowUs = SystemClock.elapsedRealtimeNanos() / 1000;
                    if (nowUs >= nextSilence) {
                        mutate(() -> {
                            if (ready) requireFreshGuard();
                            if (!ready || pcm.muted() || !pcm.fresh) {
                                int count = uplink.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
                                require(count >= 0, "Call virtual microphone keepalive failed");
                                pcm.acceptedSilence(count); silenceFrames += count;
                            }
                            if (!ready || !downStarted) require(writeOutput(downSamples, 0, OUTPUT_KEEPALIVE_SAMPLES, true) >= 0,
                                    "Call playback keepalive failed");
                            return null;
                        });
                        nextSilence = nowUs + 7500;
                    }
                    lastLoopAt = SystemClock.elapsedRealtime();
                    SystemClock.sleep(1);
                } finally {
                    long elapsedUs = SystemClock.elapsedRealtimeNanos() / 1000 - loopStartedUs;
                    maxLoopUs = Math.max(maxLoopUs, elapsedUs);
                    if (readyAt > 0 && loopStartedUs >= readyAt * 1000) maxActiveLoopUs = Math.max(maxActiveLoopUs, elapsedUs);
                }
            }
        } catch (CallEnded ended) { endedNormally = true; }
        catch (Exception error) { if (!stopped.get()) failure.compareAndSet(null, describe(error)); }
        finally { stop(null); }
    }
    private void checkMedia(long now) {
        CallMediaLease selected;
        synchronized (lock) {
            if (stopped.get() || media == null || now - mediaCheckedAt < 250) return;
            selected = media; mediaCheckedAt = now;
        }
        // A selected player's Binder is outside our process. Never hold the PCM/mute lock
        // while querying it; the independent worker watchdog handles a stalled reply.
        selected.poll();
        if (!selected.isActive() && !selected.isCleanupComplete()) selected.finish(false);
        String reason = selected.getFailure(); boolean active = selected.isActive();
        synchronized (lock) { if (media == selected) { mediaReason = reason; mediaActive = active; } }
    }
    private void relayDownlink() throws Exception {
        requireFreshGuard();
        if (downOffset == downCount) {
            int count = downlink.read(downSamples, 0, downSamples.length, AudioRecord.READ_NON_BLOCKING);
            require(count >= 0 && (count & 1) == 0, "Call downlink read failed"); downCount = count; downOffset = 0;
            if (count > 0) downPendingAt = SystemClock.elapsedRealtime();
        }
        if (downCount > downOffset) {
            long age = SystemClock.elapsedRealtime() - downPendingAt;
            maxDownPendingMs = Math.max(maxDownPendingMs, age);
            require(age < PCM_GAP_MS, "Call playback stalled");
            int sent = writeOutput(downSamples, downOffset, downCount - downOffset, false);
            require(sent >= 0 && sent <= downCount - downOffset && (sent & 1) == 0, "Call playback rejected samples");
            downOffset += sent; downlinkFrames += sent / 2;
            if (downOffset == downCount) { Arrays.fill(downSamples, (short) 0); downOffset = downCount = 0; }
        }
    }
    private void stop(String error) {
        if (error != null) failure.compareAndSet(null, error);
        stopped.set(true); ready = false;
        if (cleaning.compareAndSet(false, true)) daemon("call-route-cleanup", this::cleanup);
    }
    private void cleanup() {
        synchronized (lock) {
            if (cleanupClaimed) return; cleanupClaimed = true;
            boolean ok = true;
            try { if (uplink != null) uplink.setVolume(0); } catch (Exception e) { ok = false; }
            try { if (output != null) output.setVolume(0); } catch (Exception e) { ok = false; }
            try { if (downlink != null && downStarted) downlink.stop(); } catch (Exception e) { ok = false; }
            boolean recordReleased = downlink == null;
            try { if (downlink != null) downlink.release(); recordReleased = true; } catch (Exception e) { ok = false; }
            // Never remove an input out from under an owned recorder whose release is uncertain.
            if (recordReleased) {
                try { if (uplink != null && upStarted) uplink.stop(); } catch (Exception e) { ok = false; }
                try { if (uplink != null) uplink.release(); } catch (Exception e) { ok = false; }
                try { if (output != null && outStarted) output.stop(); } catch (Exception e) { ok = false; }
                try { if (output != null) output.release(); } catch (Exception e) { ok = false; }
                try { if (registered && policy != null) call(manager, "unregisterAudioPolicy", new Class<?>[]{Class.forName(POLICY + "AudioPolicy")}, policy); }
                catch (Exception e) { ok = false; }
                try { if (ownsMode) manager.setMode(AudioManager.MODE_NORMAL); } catch (Exception e) { ok = false; }
                try {
                    if (policy != null) {
                        long until = SystemClock.elapsedRealtime() + 1000;
                        boolean present;
                        do {
                            present = false;
                            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS | AudioManager.GET_DEVICES_OUTPUTS))
                                if (device.getType() == remoteType && (device.getAddress().equals(inputAddress) ||
                                        device.getAddress().equals(downlinkAddress))) present = true;
                            if (present) SystemClock.sleep(10);
                        } while (present && SystemClock.elapsedRealtime() < until);
                        ok &= !present;
                    }
                } catch (Exception e) { ok = false; }
            } else ok = false;
            if (media != null) {
                ok &= media.finish(endedNormally && failure.get() == null);
                mediaReason = media.getFailure();
                mediaActive = false;
            }
            try { input.close(); } catch (Exception e) { ok = false; }
            if (pcm != null) pcm.erase(); Arrays.fill(downSamples, (short) 0);
            resourcesStopped = ok;
            awaitingInputQuiet = ok && everReady && !inputQuiet && !ownerDied;
            if (!ok) failure.compareAndSet(null, "Call audio cleanup was incomplete");
            cleaned.countDown();
        }
    }
    private void unlinkOwner() {
        if (linked) try { owner.asBinder().unlinkToDeath(death, 0); } catch (Exception ignored) { }
    }
    private void awaitResources(long timeout) { try { cleaned.await(timeout, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private void awaitFinal() { try { finalized.await(CLEANUP_MS, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    @Override public void close() { destroying = true; stop(null); awaitFinal(); }
    public Bundle finish(boolean inputQuiet) {
        if (inputQuiet) this.inputQuiet = true;
        stop(null);
        if (inputQuiet) awaitFinal(); else awaitResources(2000);
        return snapshot();
    }
    public boolean isCleanupComplete() { return cleanupComplete; }
    public Bundle snapshot() {
        // HFP validation can wait on Bluetooth Binder. Never wait for its monitor with the PCM lock held.
        HfpCallLease selectedHfp = hfp;
        int originalPolicy = selectedHfp == null ? -1 : selectedHfp.originalPolicy();
        boolean disconnectAttempted = selectedHfp != null && selectedHfp.wasAttempted();
        boolean restoreAttempted = selectedHfp != null && selectedHfp.restoreWasAttempted();
        String hfpResult = selectedHfp == null ? "not-acquired" : selectedHfp.result();
        synchronized (lock) {
            Bundle result = new Bundle();
            result.putInt("routeProtocol", 2);
            result.putBoolean("ready", ready && !stopped.get()); result.putBoolean("cleanupComplete", cleanupComplete);
            result.putBoolean("resourcesStopped", resourcesStopped); result.putBoolean("awaitingInputQuiet", awaitingInputQuiet);
            result.putInt("hfpOriginalPolicy", originalPolicy);
            result.putBoolean("hfpDisconnectAttempted", disconnectAttempted);
            result.putBoolean("hfpRestoreAttempted", restoreAttempted);
            result.putString("hfpResult", hfpResult);
            result.putBoolean("hfpSuppressionAttempted", suppressionAttempted); result.putBoolean("hfpSuppressionActive", suppressionActive);
            result.putBoolean("hfpSuppressionReleased", suppressionReleased); result.putLong("hfpSuppressionGeneration", suppressionGeneration);
            result.putBoolean("ended", endedNormally && failure.get() == null);
            result.putBoolean("mediaRequested", allowMedia);
            result.putBoolean("mediaActive", mediaActive);
            result.putString("mediaReason", mediaReason);
            result.putInt("kind", kind); result.putInt("uid", uid); result.putInt("session", session);
            result.putInt("sourceRate", PHYSICAL_RATE); result.putInt("mixRate", RATE);
            result.putInt("inputChannels", 1); result.putInt("outputChannels", 2);
            result.putBoolean("inputVerified", inputVerified); result.putBoolean("outputVerified", outputVerified);
            result.putBoolean("downlinkVerified", downlinkVerified);
            result.putString("outputObservedTypes", outputObservedTypes);
            result.putInt("downlinkObservedType", downlinkObservedType); result.putInt("downlinkRecordingType", downlinkRecordingType);
            result.putInt("downlinkRecordingMatches", downlinkRecordingMatches);
            result.putLong("epoch", pcm == null ? 0 : pcm.epoch); result.putBoolean("muted", pcm == null || pcm.muted());
            result.putLong("framesInput", sourceFrames); result.putLong("framesOutput", downlinkFrames);
            result.putLong("silenceFrames", silenceFrames); result.putLong("staleFrames", pcm == null ? 0 : pcm.staleFrames);
            result.putLong("shortWrites", shortWrites); result.putLong("zeroWrites", zeroWrites); result.putLong("pendingMaxAgeMs", maxPendingMs);
            result.putLong("downlinkPendingMaxAgeMs", maxDownPendingMs);
            result.putInt("uplinkBufferFrames", uplinkBufferFrames);
            result.putInt("downlinkBufferFrames", downlinkBufferFrames);
            result.putInt("outputBufferFrames", outputBufferFrames);
            result.putInt("outputCapacityFrames", outputCapacityFrames); result.putInt("outputStartThresholdFrames", outputStartThresholdFrames);
            result.putInt("outputPlayState", outputPlayState); result.putLong("outputAdmissionHead", outputAdmissionHead);
            result.putLong("outputPlaybackHead", outputPlaybackHead); result.putLong("outputWriteCalls", outputWriteCalls);
            result.putLong("outputZeroWrites", outputZeroWrites); result.putLong("outputShortWrites", outputShortWrites);
            result.putLong("outputAcceptedFrames", outputAcceptedFrames); result.putLong("outputSilenceFrames", outputSilenceFrames);
            result.putLong("maxOutputWriteUs", maxOutputWriteUs); result.putLong("maxLoopUs", maxLoopUs);
            result.putLong("maxControlCheckUs", maxControlCheckUs);
            result.putLong("maxActiveOutputWriteUs", maxActiveOutputWriteUs); result.putLong("maxActiveLoopUs", maxActiveLoopUs);
            result.putLong("maxActiveControlCheckUs", maxActiveControlCheckUs); result.putLong("maxGuardAgeMs", maxGuardAgeMs);
            result.putLong("wallMs", startedAt == 0 ? 0 : (endedAt == 0 ? SystemClock.elapsedRealtime() : endedAt) - startedAt);
            result.putString("error", failure.get()); result.putInt("audioFilesWritten", 0);
            return result;
        }
    }
}
