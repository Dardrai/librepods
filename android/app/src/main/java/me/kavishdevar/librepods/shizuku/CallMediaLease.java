/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional focus isolation for one selected media app. Sends no Play, Pause or volume commands.
 * The platform isolates a UID, so shared UIDs and ambiguous sessions/players are rejected.
 * Admission can deliver Android's focus-gain callback to an initially paused app.
 * This does not override Android's separate call mute and is not proof of audible media. */
@SuppressLint({"MissingPermission", "SoonBlockedPrivateApi"})
public final class CallMediaLease implements AutoCloseable {
    static final int RETAIN = 1, LOSE = 2;
    private static final long POLL_MS = 250;
    static boolean retiresPlayback(boolean hasPlayed, int state) {
        return state != PlaybackState.STATE_PLAYING && (state != PlaybackState.STATE_PAUSED || hasPlayed);
    }

    static final class Snapshot {
        final Object sessionToken;
        final int uid, playerId, audioSession;
        final boolean playing;
        Snapshot(Object token, int uid, int playerId, int audioSession, boolean playing) {
            this.sessionToken = token; this.uid = uid; this.playerId = playerId; this.audioSession = audioSession;
            this.playing = playing;
        }
        boolean matches(Snapshot other) {
            return other != null && sessionToken != null && sessionToken.equals(other.sessionToken) &&
                    uid >= 10000 && uid == other.uid && playerId > 0 && other.playerId > 0 &&
                    audioSession > 0 && other.audioSession > 0;
        }
        boolean admits(Snapshot other) {
            // A manual pause after initial selection must not become a newly
            // authorized paused admission just because callback registration lagged.
            return matches(other) && (!playing || other.playing);
        }
    }

    interface Backend {
        Snapshot current() throws Exception;
        void watch() throws Exception;
        void stopWatching();
        Object token();
        boolean enter(int uid, Object token) throws Exception;
        boolean exit(Object token, int mode) throws Exception;
    }

    private final Backend backend;
    private final Snapshot selected;
    private final Object token;
    private boolean entered, mayOwn, closed, cleanupComplete;
    private int exitMode;
    private long checkedAt = -1;
    private String failure = "";

    private CallMediaLease(Backend backend, Snapshot selected) {
        this.backend = backend; this.selected = selected; this.token = backend.token();
    }

    /** Invoke only for the separate user opt-in. A null result leaves native focus untouched. */
    public static CallMediaLease acquire(Context context, String address, String mediaPackage,
            IPlaybackRecoveryOwner owner) throws Exception {
        if (owner == null) throw new IllegalArgumentException("Missing call media owner");
        return acquire(new AndroidBackend(context, address, mediaPackage, owner));
    }

    /** Read-only suggestion for explicit user selection before a call. No token or policy is created. */
    public static String inspectSelection(Context context, String address) throws Exception {
        String selectedPackage = null;
        for (MediaController controller : context.getSystemService(MediaSessionManager.class).getActiveSessions(null)) {
            PlaybackState state = controller.getPlaybackState();
            MediaController.PlaybackInfo info = controller.getPlaybackInfo();
            if (state == null || state.getState() != PlaybackState.STATE_PLAYING || info == null ||
                    info.getPlaybackType() != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL ||
                    !AndroidBackend.mediaUsage(info.getAudioAttributes().getUsage())) continue;
            if (selectedPackage != null) return null;
            selectedPackage = controller.getPackageName();
        }
        if (selectedPackage == null) return null;
        AndroidBackend probe = new AndroidBackend(context, address, selectedPackage, null);
        Snapshot snapshot = probe.current();
        return snapshot != null && snapshot.playing && snapshot.matches(snapshot) ? selectedPackage : null;
    }

    static CallMediaLease acquire(Backend backend) throws Exception {
        Snapshot selected = backend.current();
        if (selected == null || !selected.matches(selected)) return null;
        CallMediaLease lease = new CallMediaLease(backend, selected);
        try {
            backend.watch();
            if (!selected.admits(backend.current())) {
                lease.failure = "Selected media changed before isolation";
            } else {
                // Keep our exact Binder even if delivery succeeded but the reply throws.
                lease.mayOwn = true;
                lease.entered = backend.enter(selected.uid, lease.token);
                if (!lease.entered) {
                    lease.mayOwn = false; // A returned false explicitly rejected this token.
                    lease.failure = "Media focus isolation was declined";
                } else if (!selected.admits(backend.current())) {
                    lease.entered = false;
                    lease.failure = "Selected media changed during isolation";
                }
            }
        } catch (Exception error) {
            lease.entered = false;
            lease.failure = "Media focus isolation failed";
        }
        if (!lease.entered) lease.finish(false);
        return lease;
    }

    /** Called by the existing route watchdog; expensive framework queries run at most four times/s. */
    public void poll() { poll(SystemClock.elapsedRealtime()); }
    synchronized void poll(long now) {
        if (closed || (checkedAt >= 0 && now >= checkedAt && now - checkedAt < POLL_MS)) return;
        checkedAt = now;
        try {
            if (selected.matches(backend.current())) return;
            failure = "Selected media changed";
        } catch (Exception error) { failure = "Media ownership is unavailable"; }
        finish(false);
    }

    /** Retain focus only on normal call end with the exact unchanged, still-playing owner.
     * There is deliberately no Play command: this lease never created a pause to resume. */
    public synchronized boolean finish(boolean normalCallEnded) {
        if (cleanupComplete) return true;
        if (!closed) {
            closed = true;
            exitMode = LOSE;
            if (normalCallEnded && entered) {
                try {
                    Snapshot current = backend.current();
                    if (selected.matches(current) && current.playing) exitMode = RETAIN;
                }
                catch (Exception ignored) { /* Stale/unknown ownership must not reacquire focus. */ }
            }
        }
        entered = false;
        try {
            if (mayOwn) {
                if (exitMode == RETAIN) {
                    try {
                        Snapshot current = backend.current();
                        if (!normalCallEnded || !selected.matches(current) || !current.playing) exitMode = LOSE;
                    } catch (Exception ignored) { exitMode = LOSE; }
                }
                // IAudioService false means this exact Binder has no isolation record.
                // Both true (removed) and false (absent) prove cleanup; exceptions do not.
                backend.exit(token, exitMode);
                mayOwn = false;
            }
            cleanupComplete = true;
        } catch (Exception error) { failure = "Media focus cleanup is pending"; }
        finally { if (cleanupComplete) backend.stopWatching(); }
        return cleanupComplete;
    }

    public synchronized boolean isActive() { return entered && !closed; }
    public synchronized boolean isCleanupComplete() { return cleanupComplete; }
    public synchronized String getFailure() { return failure; }
    @Override public void close() { finish(false); }

    private static final class AndroidBackend implements Backend {
        private final Context context;
        private final String address, mediaPackage;
        private final IPlaybackRecoveryOwner owner;
        private final AudioManager manager;
        private final Object service;
        private final Method enter, exit, uid, playerId, sessionId, playerState, devices;
        private final int started, paused;
        private final AtomicBoolean changed = new AtomicBoolean();
        private final AtomicBoolean observedPlaying = new AtomicBoolean();
        private MediaController player;
        private HandlerThread callbacks;
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) observedPlaying.set(true);
                else if (retiresPlayback(observedPlaying.get(), state == null ? PlaybackState.STATE_NONE : state.getState())) changed.set(true);
            }
            @Override public void onSessionDestroyed() { changed.set(true); }
        };

        AndroidBackend(Context context, String address, String mediaPackage, IPlaybackRecoveryOwner owner) throws Exception {
            this.context = context; this.address = address; this.mediaPackage = mediaPackage; this.owner = owner;
            if (Process.myUid() != 0 || address == null || address.isEmpty() || mediaPackage == null ||
                    mediaPackage.isEmpty()) throw new IllegalArgumentException("Invalid media owner");
            for (String permission : new String[]{"MODIFY_AUDIO_SETTINGS_PRIVILEGED", "MEDIA_CONTENT_CONTROL",
                    "QUERY_AUDIO_STATE", "MODIFY_AUDIO_ROUTING"}) {
                if (context.checkPermission("android.permission." + permission, Process.myPid(), Process.myUid()) !=
                        PackageManager.PERMISSION_GRANTED) throw new SecurityException("Media focus access is unavailable");
            }
            if (!(Boolean) Class.forName("android.media.audio.Flags").getMethod("audioFocusIsolation").invoke(null))
                throw new UnsupportedOperationException("Media focus isolation is disabled");
            // Retain the caller-created token across an uncertain Binder reply. These are
            // the same checked service signatures used by AudioManager's typed wrapper.
            Method getService = AudioManager.class.getDeclaredMethod("getService");
            getService.setAccessible(true); service = getService.invoke(null);
            Class<?> serviceType = Class.forName("android.media.IAudioService");
            enter = serviceType.getMethod("enterFocusIsolation", int.class, android.os.IBinder.class);
            exit = serviceType.getMethod("exitFocusIsolation", android.os.IBinder.class, int.class);
            if (AudioManager.class.getField("FOCUS_ISOLATION_EXIT_RETAIN_FOCUS").getInt(null) != RETAIN ||
                    AudioManager.class.getField("FOCUS_ISOLATION_EXIT_LOSE_FOCUS").getInt(null) != LOSE)
                throw new UnsupportedOperationException("Changed focus isolation contract");
            manager = context.getSystemService(AudioManager.class);
            Class<AudioPlaybackConfiguration> type = AudioPlaybackConfiguration.class;
            uid = type.getMethod("getClientUid"); playerId = type.getMethod("getPlayerInterfaceId");
            sessionId = type.getMethod("getSessionId"); playerState = type.getMethod("getPlayerState");
            devices = type.getMethod("getAudioDeviceInfos"); started = type.getField("PLAYER_STATE_STARTED").getInt(null);
            paused = type.getField("PLAYER_STATE_PAUSED").getInt(null);
        }

        @Override public Snapshot current() throws Exception {
            if (changed.get() || (owner != null && !owner.isCurrent())) return null;
            MediaController candidate = null;
            for (MediaController controller : context.getSystemService(MediaSessionManager.class).getActiveSessions(null)) {
                PlaybackState state = controller.getPlaybackState();
                MediaController.PlaybackInfo info = controller.getPlaybackInfo();
                if (state == null || info == null ||
                        info.getPlaybackType() != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL ||
                        !mediaUsage(info.getAudioAttributes().getUsage())) continue;
                boolean selectedPackage = mediaPackage.equals(controller.getPackageName());
                if (!selectedPackage) {
                    if (state.getState() == PlaybackState.STATE_PLAYING) return null;
                    continue;
                }
                if (candidate != null || (state.getState() != PlaybackState.STATE_PLAYING &&
                        state.getState() != PlaybackState.STATE_PAUSED)) return null;
                candidate = controller;
            }
            if (candidate == null || (player != null && !player.getSessionToken().equals(candidate.getSessionToken()))) return null;
            PlaybackState state = candidate.getPlaybackState();
            if (state == null || retiresPlayback(observedPlaying.get(), state.getState())) {
                changed.set(true);
                return null;
            }
            boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
            if (playing) observedPlaying.set(true);
            int expectedUid = context.getPackageManager().getApplicationInfo(mediaPackage, 0).uid;
            String[] packages = context.getPackageManager().getPackagesForUid(expectedUid);
            if (packages == null || packages.length != 1 || !mediaPackage.equals(packages[0])) return null;
            Snapshot result = null;
            for (AudioPlaybackConfiguration configuration : manager.getActivePlaybackConfigurations()) {
                int nativeState = (Integer) playerState.invoke(configuration);
                if ((Integer) uid.invoke(configuration) != expectedUid || !mediaUsage(configuration.getAudioAttributes().getUsage()) ||
                        (nativeState != started && nativeState != paused)) continue;
                if (result != null || !selectedDevices(configuration)) return null;
                result = new Snapshot(candidate.getSessionToken(), expectedUid,
                        (Integer) playerId.invoke(configuration), (Integer) sessionId.invoke(configuration),
                        playing);
            }
            if (result != null && player == null) {
                player = candidate;
            }
            return result;
        }

        private static boolean mediaUsage(int usage) {
            return usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME;
        }
        private boolean selectedDevices(AudioPlaybackConfiguration configuration) throws Exception {
            Object value = devices.invoke(configuration);
            if (!(value instanceof List<?>)) return false;
            List<?> routes = (List<?>) value;
            if (routes.isEmpty()) return false;
            for (Object item : routes) {
                if (!(item instanceof AudioDeviceInfo)) return false;
                AudioDeviceInfo route = (AudioDeviceInfo) item;
                if (route.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || !route.getAddress().equalsIgnoreCase(address)) return false;
            }
            return true;
        }
        @Override public void watch() {
            if (owner == null) throw new IllegalStateException("Read-only media inspection cannot create a lease");
            callbacks = new HandlerThread("LibrePods-call-media-owner"); callbacks.start();
            player.registerCallback(callback, new Handler(callbacks.getLooper()));
        }
        @Override public void stopWatching() {
            if (player != null) try { player.unregisterCallback(callback); } catch (Exception ignored) { }
            if (callbacks != null) { callbacks.quitSafely(); callbacks = null; }
        }
        @Override public Object token() { return new Binder(); }
        @Override public boolean enter(int value, Object token) throws Exception { return (Boolean) enter.invoke(service, value, token); }
        @Override public boolean exit(Object token, int mode) throws Exception { return (Boolean) exit.invoke(service, token, mode); }
    }
}
