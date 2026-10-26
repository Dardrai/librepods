/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.AttributionSource;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.IInterface;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Temporarily releases one HFP profile without changing its saved Phone calls preference. */
@SuppressLint({"MissingPermission", "SoonBlockedPrivateApi"})
final class HfpCallLease implements AutoCloseable {
    interface Current { boolean get() throws Exception; }
    interface Clock { long now(); void pause() throws Exception; }
    interface Suppression {
        void acquire() throws Exception;
        void check() throws Exception;
        boolean release();
    }
    interface Port extends AutoCloseable {
        Snapshot read() throws Exception;
        void disconnect() throws Exception;
        void connect() throws Exception;
        void close();
    }
    static final class Snapshot {
        final int policy, profile, audio;
        final boolean selectedAvailable, competitor;
        Snapshot(int policy, int profile, int audio, boolean selectedAvailable, boolean competitor) {
            this.policy = policy; this.profile = profile; this.audio = audio;
            this.selectedAvailable = selectedAvailable; this.competitor = competitor;
        }
    }
    private final Port port;
    private final Current current;
    private final Clock clock;
    private final Suppression suppression;
    private Snapshot original;
    private boolean attempted, restoreAttempted, finished, complete;
    private String result = "not-acquired";

    HfpCallLease(Port port, Current current, Clock clock) {
        this(port, current, clock, new Suppression() {
            public void acquire() { }
            public void check() { }
            public boolean release() { return true; }
        });
    }
    HfpCallLease(Port port, Current current, Clock clock, Suppression suppression) {
        this.port = port; this.current = current; this.clock = clock; this.suppression = suppression;
    }
    static HfpCallLease open(Context context, String peer, Current current, Suppression suppression) throws Exception {
        return new HfpCallLease(new HeadsetPort(context, peer), current, new Clock() {
            public long now() { return SystemClock.elapsedRealtime(); }
            public void pause() throws InterruptedException { Thread.sleep(20); }
        }, suppression);
    }
    static boolean suppressionMatches(String peer, long expectedGeneration, boolean active, String actualPeer, long generation) {
        return active && generation > 0 && (expectedGeneration == 0 || generation == expectedGeneration) && peer.equals(actualPeer);
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private static void valid(Snapshot value) {
        require(value.policy == 0 || value.policy == 100, "Selected HFP preference is unavailable");
        require(value.profile >= 0 && value.profile <= 3 && value.audio >= 10 && value.audio <= 12,
                "Selected HFP state is unavailable");
    }
    private boolean idle(Snapshot value) {
        valid(value);
        require(value.selectedAvailable && !value.competitor && value.policy == original.policy,
                "Selected HFP owner or preference changed");
        return value.profile == BluetoothProfile.STATE_DISCONNECTED && value.audio == BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
    }
    synchronized void acquire() throws Exception {
        require(original == null && !finished && current.get(), "HFP call lease is no longer current");
        original = port.read(); valid(original);
        require(original.selectedAvailable && !original.competitor, "Another headset owns call audio");
        require(original.profile == BluetoothProfile.STATE_CONNECTED || original.profile == BluetoothProfile.STATE_DISCONNECTED,
                "Selected HFP profile is changing");
        suppression.acquire();
        require(current.get(), "HFP call lease was interrupted");
        if (idle(original)) { result = "already-idle"; return; }
        require(original.profile == BluetoothProfile.STATE_CONNECTED && current.get(), "Selected HFP profile is not ready");
        // A lost Binder reply cannot prove the disconnect was not delivered.
        attempted = true;
        try { port.disconnect(); } catch (Exception ignored) { }
        long until = clock.now() + 1500;
        do {
            require(current.get() && !finished, "HFP call lease was interrupted");
            if (idle(port.read())) {
                require(current.get(), "HFP call lease was interrupted");
                result = "released"; return;
            }
            clock.pause();
        } while (clock.now() < until);
        throw new IllegalStateException("Selected hands-free profile did not become idle");
    }
    synchronized void checkIdle() throws Exception {
        suppression.check();
        require(!finished && original != null && current.get() && idle(port.read()),
                "Selected hands-free profile returned during the call");
    }
    /** Caller must first stop its AACP uplink, or prove that the owning process died. */
    synchronized boolean restore() {
        if (finished) return complete;
        finished = true;
        try {
            // The caller has proved AACP quiet. A queued HFP connect must see the real
            // policy throughout its later Bluetooth state-machine acceptance.
            if (!suppression.release()) { result = "suppression-release-unconfirmed"; return false; }
            if (!attempted || original == null || original.profile != BluetoothProfile.STATE_CONNECTED || original.policy != 100) {
                result = attempted && original != null && original.policy == 0 ? "preference-off-preserved" : "unchanged";
                return complete = true;
            }
            Snapshot state = port.read(); valid(state);
            if (!state.selectedAvailable || state.competitor || state.policy != original.policy) {
                result = "newer-choice-preserved"; return complete = true;
            }
            if (state.profile == BluetoothProfile.STATE_CONNECTED) { result = "already-restored"; return complete = true; }
            long until = clock.now() + 2500;
            while (state.profile == BluetoothProfile.STATE_DISCONNECTING && clock.now() < until) {
                clock.pause(); state = port.read(); valid(state);
                if (!state.selectedAvailable || state.competitor || state.policy != original.policy) {
                    result = "newer-choice-preserved"; return complete = true;
                }
            }
            if (state.profile == BluetoothProfile.STATE_DISCONNECTED) {
                // Recheck immediately before connect: the platform may evict another connecting headset.
                state = port.read(); valid(state);
                if (!state.selectedAvailable || state.competitor || state.policy != original.policy) {
                    result = "newer-choice-preserved"; return complete = true;
                }
                if (state.profile == BluetoothProfile.STATE_DISCONNECTED) {
                    restoreAttempted = true;
                    try { port.connect(); } catch (Exception ignored) { }
                }
            }
            while (clock.now() < until) {
                state = port.read(); valid(state);
                if (!state.selectedAvailable || state.competitor || state.policy != original.policy) {
                    result = "newer-choice-preserved"; return complete = true;
                }
                if (state.profile == BluetoothProfile.STATE_CONNECTED) { result = "restored"; return complete = true; }
                clock.pause();
            }
            result = "restore-unconfirmed"; return false;
        } catch (Exception error) { result = "restore-unconfirmed"; return false; }
        finally { port.close(); }
    }
    /** Abandoning an unconfirmed, live microphone never reconnects HFP over it. */
    @Override public synchronized void close() { if (!finished) { finished = true; result = "quiet-unconfirmed"; port.close(); } }
    synchronized int originalPolicy() { return original == null ? -1 : original.policy; }
    synchronized boolean wasAttempted() { return attempted; }
    synchronized boolean restoreWasAttempted() { return restoreAttempted; }
    synchronized String result() { return result; }

    private static final class HeadsetPort implements Port {
        private final Context context;
        private final BluetoothAdapter adapter;
        private final BluetoothDevice device;
        private final Object gate = new Object();
        private final CountDownLatch ready = new CountDownLatch(1);
        private BluetoothHeadset proxy;
        private boolean closed, serviceLost;
        private IInterface service;
        private IBinder binder;
        private Method serviceGetter, policy, profile, audio, active, matching, disconnect, connect;
        private final AttributionSource source;
        HeadsetPort(Context context, String peer) throws Exception {
            this.context = context; adapter = ShellBluetooth.INSTANCE.adapter(context);
            device = adapter.getRemoteDevice(peer); source = context.getAttributionSource();
            BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int kind, BluetoothProfile value) {
                    synchronized (gate) {
                        if (!closed && kind == BluetoothProfile.HEADSET && value instanceof BluetoothHeadset) proxy = (BluetoothHeadset) value;
                        else adapter.closeProfileProxy(kind, value);
                    }
                    ready.countDown();
                }
                public void onServiceDisconnected(int kind) { synchronized (gate) { serviceLost = true; } ready.countDown(); }
            };
            try {
                require(adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET) && ready.await(1500, TimeUnit.MILLISECONDS),
                        "Hands-free service did not become ready");
                synchronized (gate) { require(proxy != null && !serviceLost, "Hands-free service is unavailable"); }
                serviceGetter = BluetoothHeadset.class.getDeclaredMethod("getService"); serviceGetter.setAccessible(true);
                service = (IInterface) serviceGetter.invoke(proxy); require(service != null, "Hands-free service is unavailable");
                binder = service.asBinder();
                Class<?> type = Class.forName("android.bluetooth.IBluetoothHeadset");
                policy = type.getMethod("getConnectionPolicy", BluetoothDevice.class, AttributionSource.class);
                profile = type.getMethod("getConnectionState", BluetoothDevice.class, AttributionSource.class);
                audio = type.getMethod("getAudioState", BluetoothDevice.class, AttributionSource.class);
                active = type.getMethod("getActiveDevice", AttributionSource.class);
                matching = type.getMethod("getDevicesMatchingConnectionStates", int[].class, AttributionSource.class);
                disconnect = type.getMethod("disconnect", BluetoothDevice.class, AttributionSource.class);
                connect = type.getMethod("connect", BluetoothDevice.class, AttributionSource.class);
            } catch (Exception error) { close(); throw error; }
        }
        private void available() throws Exception {
            synchronized (gate) { require(!closed && !serviceLost && proxy != null, "Hands-free service changed"); }
            require(adapter.isEnabled() && binder.isBinderAlive() && binder.pingBinder() &&
                    serviceGetter.invoke(proxy) == service, "Hands-free service changed");
        }
        public Snapshot read() throws Exception {
            available();
            int p = (Integer) policy.invoke(service, device, source);
            int state = (Integer) profile.invoke(service, device, source);
            int sco = (Integer) audio.invoke(service, device, source);
            BluetoothDevice selected = (BluetoothDevice) active.invoke(service, source);
            List<?> others = (List<?>) matching.invoke(service, new int[]{BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_CONNECTED}, source);
            boolean competitor = selected != null && !selected.equals(device);
            for (Object other : others) competitor |= !device.equals(other);
            boolean stereo = false;
            for (AudioDeviceInfo output : context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                stereo |= output.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && output.getAddress().equalsIgnoreCase(device.getAddress());
            available();
            return new Snapshot(p, state, sco, device.getBondState() == BluetoothDevice.BOND_BONDED && stereo, competitor);
        }
        public void disconnect() throws Exception { available(); disconnect.invoke(service, device, source); }
        public void connect() throws Exception { available(); connect.invoke(service, device, source); }
        public void close() {
            BluetoothHeadset old;
            synchronized (gate) { closed = true; old = proxy; proxy = null; }
            if (old != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, old);
        }
    }
}
