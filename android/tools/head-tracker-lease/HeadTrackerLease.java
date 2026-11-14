package me.kavishdevar.librepods.headtrackerlease;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;

public final class HeadTrackerLease {
    private static native int installSignals();
    private static native int interruptedSignal();

    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... arguments) throws Exception {
        Method method = owner.getMethod(name, arguments);
        if (method.getReturnType() != result) throw new NoSuchMethodException("return_type_mismatch");
        return method;
    }

    private static final class Audio implements LeaseState.Access {
        final Class<?> api = Class.forName("android.media.IAudioService");
        final Class<?> attributes = Class.forName("android.media.AudioDeviceAttributes");
        final Object service, selected;
        final String address;
        final AudioAttributes media = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();

        Audio(String address) throws Exception {
            this.address = address;
            Object binder = method(Class.forName("android.os.ServiceManager"), "getService", IBinder.class, String.class)
                .invoke(null, "audio");
            if (!(binder instanceof IBinder) || !((IBinder) binder).isBinderAlive())
                throw new IllegalStateException("audio_service_unavailable");
            service = method(Class.forName("android.media.IAudioService$Stub"), "asInterface", api, IBinder.class)
                .invoke(null, binder);
            if (!api.isInstance(service)) throw new IllegalStateException("audio_service_type_mismatch");
            selected = attributes.getConstructor(Integer.TYPE, Integer.TYPE, String.class)
                .newInstance(2, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, address);
            method(api, "setHeadTrackerEnabled", Void.TYPE, Boolean.TYPE, attributes);
        }

        boolean selected(Object value) throws Exception {
            return attributes.isInstance(value) && ((Integer) method(attributes, "getRole", Integer.TYPE).invoke(value)) == 2 &&
                ((Integer) method(attributes, "getType", Integer.TYPE).invoke(value)) == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                address.equalsIgnoreCase((String) method(attributes, "getAddress", String.class).invoke(value));
        }

        boolean bool(String name) throws Exception { return (Boolean) method(api, name, Boolean.TYPE).invoke(service); }
        public LeaseState.Flags flags() throws Exception {
            List<?> compatible = (List<?>) method(api, "getSpatializerCompatibleAudioDevices", List.class).invoke(service);
            int selectedCount = 0;
            for (Object value : compatible) if (selected(value)) ++selectedCount;
            if (selectedCount > 1) throw new IllegalStateException("duplicate_selected_device");
            boolean head = (Boolean) method(api, "isHeadTrackerEnabled", Boolean.TYPE, attributes).invoke(service, selected);
            return new LeaseState.Flags(selectedCount == 1, head);
        }
        public LeaseState.State read() throws Exception {
            List<?> routes = (List<?>) method(api, "getDevicesForAttributes", List.class, AudioAttributes.class).invoke(service, media);
            boolean route = routes.size() == 1 && selected(routes.get(0)) &&
                ((Integer) method(api, "getMode", Integer.TYPE).invoke(service)) == 0;
            boolean compatible = (Boolean) method(api, "isSpatializerAvailableForDevice", Boolean.TYPE, attributes)
                .invoke(service, selected);
            return new LeaseState.State(flags(), bool("isSpatializerEnabled"), route && compatible);
        }
        public void setHeadTracking(boolean enabled) throws Exception {
            method(api, "setHeadTrackerEnabled", Void.TYPE, Boolean.TYPE, attributes).invoke(service, enabled, selected);
        }
    }

    private static JSONObject flags(LeaseState.Flags value) throws Exception {
        return new JSONObject().put("device_spatial_enabled", value.spatial).put("head_tracker_enabled", value.head);
    }
    private static void emit(String event, String address, LeaseState.Flags value) throws Exception {
        JSONObject record = new JSONObject().put("event", event).put("selected_address", address)
            .put("uid", Process.myUid()).put("pid", Process.myPid());
        if (value != null) record.put("flags", flags(value));
        System.out.println(record.toString());
        System.out.flush();
    }
    private static void failure(String event, Throwable error) {
        while (error instanceof InvocationTargetException) error = ((InvocationTargetException) error).getTargetException();
        try {
            System.out.println(new JSONObject().put("event", event).put("error_type", error.getClass().getName())
                .put("reason", error instanceof IllegalStateException ? error.getMessage() : JSONObject.NULL).toString());
            System.out.flush();
        } catch (Exception ignored) { }
    }
    private static String temporaryPath(String value) throws Exception {
        String path = new File(value).getCanonicalPath();
        if (!new File(value).isAbsolute() || !path.startsWith("/data/local/tmp/"))
            throw new IllegalArgumentException("temporary_path_required");
        return path;
    }
    private static void writeSnapshot(String path, String address, LeaseState.Flags original) throws Exception {
        FileDescriptor descriptor = Os.open(path, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL |
            OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0600);
        try (FileOutputStream output = new FileOutputStream(descriptor)) {
            JSONObject snapshot = new JSONObject().put("schema", 1).put("kind", "selected_head_tracker_lease")
                .put("address", address).put("pid", Process.myPid()).put("original", flags(original));
            output.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
            output.flush(); descriptor.sync();
        }
    }
    private static LeaseState.Flags readSnapshot(String path, String address) throws Exception {
        FileDescriptor descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        try (FileInputStream input = new FileInputStream(descriptor)) {
            StructStat stat = Os.fstat(descriptor);
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != 0 || (stat.st_mode & 077) != 0 || stat.st_size > 4096)
                throw new IllegalStateException("unsafe_snapshot");
            byte[] bytes = new byte[4097];
            int total = 0, count;
            while ((count = input.read(bytes, total, bytes.length - total)) > 0) {
                total += count;
                if (total > 4096) throw new IllegalStateException("oversized_snapshot");
            }
            JSONObject snapshot = new JSONObject(new String(bytes, 0, total, StandardCharsets.UTF_8));
            if (snapshot.getInt("schema") != 1 || !snapshot.getString("kind").equals("selected_head_tracker_lease") ||
                !address.equals(snapshot.getString("address"))) throw new IllegalStateException("snapshot_mismatch");
            JSONObject original = snapshot.getJSONObject("original");
            return new LeaseState.Flags(original.getBoolean("device_spatial_enabled"), original.getBoolean("head_tracker_enabled"));
        }
    }

    public static void main(String[] args) {
        LeaseState lease = null;
        String selectedAddress = null;
        int exit = 0;
        try {
            if (Process.myUid() != 0) throw new IllegalStateException("root_runner_required");
            if (args.length != 3 && args.length != 5) throw new IllegalArgumentException("usage");
            boolean restore = args[0].equals("--restore");
            if ((!restore && !args[0].equals("--hold")) || (restore ? args.length != 3 : args.length != 5))
                throw new IllegalArgumentException("usage");
            String address = LeaseState.address(args[1]);
            selectedAddress = address;
            String snapshot = temporaryPath(args[restore ? 2 : 3]);
            Audio audio = new Audio(address);
            lease = new LeaseState(audio);
            if (restore) {
                lease.original = readSnapshot(snapshot, address);
                lease.attempted = !lease.original.head;
                emit("restored", address, lease.restore());
            } else {
                if (!args[2].matches("[0-9]{1,2}")) throw new IllegalArgumentException("duration");
                int seconds = Integer.parseInt(args[2]);
                if (seconds < 1 || seconds > 25) throw new IllegalArgumentException("duration");
                System.load(temporaryPath(args[4]));
                int signalError = installSignals();
                if (signalError != 0) throw new IllegalStateException("signal_guard_install_failed");
                final long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
                LeaseState.Flags before = lease.capture();
                writeSnapshot(snapshot, address, before);
                emit("captured_before_write", address, before);
                final LeaseState guarded = lease;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { emit("shutdown_restore", address, guarded.restore()); }
                    catch (Throwable error) { failure("shutdown_restore_failed", error); }
                }, "head-tracker-lease-restore"));
                if (interruptedSignal() != 0 || SystemClock.elapsedRealtime() >= deadline)
                    throw new IllegalStateException("lease_cancelled_before_enable");
                lease.enable(() -> interruptedSignal() == 0 && SystemClock.elapsedRealtime() < deadline);
                emit("enabled", address, audio.flags());
                while (interruptedSignal() == 0 && SystemClock.elapsedRealtime() < deadline) {
                    LeaseState.State current = audio.read();
                    current.requireEnabledRoute();
                    if (!current.flags.head || current.flags.spatial != before.spatial)
                        throw new IllegalStateException("active_state_changed");
                    Thread.sleep(Math.min(250, Math.max(1, deadline - SystemClock.elapsedRealtime())));
                }
                emit("hold_finished", address, audio.flags());
            }
        } catch (Throwable error) {
            exit = 1; failure("lease_failed", error);
        } finally {
            if (lease != null && lease.original != null) {
                try { emit("finally_restored", selectedAddress, lease.restore()); }
                catch (Throwable error) { exit = 1; failure("finally_restore_failed", error); }
            }
        }
        System.out.flush();
        System.exit(exit);
    }
}
