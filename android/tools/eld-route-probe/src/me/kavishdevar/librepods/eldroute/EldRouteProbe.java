// SPDX-License-Identifier: GPL-3.0-or-later
package me.kavishdevar.librepods.eldroute;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit codec queries/preferences only. No connect, audio, microphone or playback API is called. */
public final class EldRouteProbe extends Instrumentation {
    private final Object ownership = new Object();
    private final CountDownLatch connected = new CountDownLatch(1);
    private Bundle arguments;
    private BluetoothAdapter adapter;
    private BluetoothProfile ownedProxy;
    private boolean finishing, serviceLost;
    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : new Bundle(args); start(); }
    @Override public void onStart() { execute(); }

    private void ensureService() {
        synchronized (ownership) {
            if (serviceLost || ownedProxy == null) throw new IllegalStateException("A2DP service disconnected");
        }
    }
    private static Object call(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }
    private static JSONObject config(CodecCarriers.Config value) throws Exception {
        JSONArray specifics = new JSONArray();
        for (long field : value.specifics) specifics.put(Long.toString(field));
        return new JSONObject().put("native_index", value.index()).put("codec_id", Long.toUnsignedString(value.id))
                .put("codec_id_hex", Long.toUnsignedString(value.id, 16)).put("name", value.name)
                .put("priority", value.integers[1]).put("sample_rate_mask", value.integers[2])
                .put("bits_per_sample_mask", value.integers[3]).put("channel_mode_mask", value.integers[4])
                .put("codec_specific", specifics);
    }
    private static Collection<?> bounded(Object raw) {
        if (!(raw instanceof Collection) || ((Collection<?>) raw).size() > 32)
            throw new IllegalStateException("Unexpected or unbounded codec collection");
        return (Collection<?>) raw;
    }
    private static List<CodecCarriers.Config> configurations(Object raw, CodecCarriers carriers) throws Exception {
        List<CodecCarriers.Config> result = new ArrayList<>();
        for (Object value : bounded(raw)) result.add(carriers.read(value));
        return result;
    }
    private void execute() {
        JSONObject report = new JSONObject(); Bundle result = new Bundle();
        boolean accepted = false, adopted = false, dropAttempt = false, dropped = false, closed = false;
        boolean writeAttempted = false;
        long started = SystemClock.elapsedRealtime();
        try {
            Map<String, String> args = new HashMap<>();
            for (String key : new String[]{"action", "address", "expectedBeforeCodecId"})
                if (arguments.getString(key) != null) args.put(key, arguments.getString(key));
            RouteRequest request = RouteRequest.parse(args);
            report.put("schema", 1).put("action", request.action).put("address", request.address)
                    .put("read_only", request.desired == null).put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
                    .put("audio_or_device_connection_accessed", false);
            if (Build.VERSION.SDK_INT != 37 || !"Pixel 7 Pro".equals(Build.MODEL))
                throw new IllegalStateException("This probe requires Pixel 7 Pro SDK37");
            // Temporary instrumentation identity, never a permanent package permission grant.
            dropAttempt = true;
            getUiAutomation().adoptShellPermissionIdentity("android.permission.BLUETOOTH_CONNECT",
                    "android.permission.BLUETOOTH_PRIVILEGED");
            adopted = true;
            BluetoothManager manager = (BluetoothManager) getTargetContext().getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is not enabled");
            BluetoothDevice device = adapter.getRemoteDevice(request.address);
            if (device.getBondState() != BluetoothDevice.BOND_BONDED)
                throw new IllegalStateException("The explicit AirPods address is not bonded");
            BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    boolean keep = false;
                    synchronized (ownership) {
                        if (profile == BluetoothProfile.A2DP && !finishing && (ownedProxy == null || ownedProxy == proxy)) {
                            ownedProxy = proxy; keep = true;
                        }
                    }
                    if (keep) connected.countDown();
                    else try { adapter.closeProfileProxy(profile, proxy); }
                    catch (RuntimeException error) { synchronized (ownership) { serviceLost = true; } }
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) synchronized (ownership) { serviceLost = true; }
                }
            };
            if (!adapter.getProfileProxy(getTargetContext(), listener, BluetoothProfile.A2DP) ||
                    !connected.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("A2DP proxy unavailable after five seconds");
            ensureService();
            BluetoothProfile proxy;
            synchronized (ownership) { proxy = ownedProxy; }
            Class<?> configType = Class.forName("android.bluetooth.BluetoothCodecConfig");
            Class<?> extendedType = Class.forName("android.bluetooth.BluetoothCodecType");
            Class<?> statusType = Class.forName("android.bluetooth.BluetoothCodecStatus");
            CodecCarriers carriers = new CodecCarriers(configType, extendedType);
            Method supported = CodecCarriers.method(proxy.getClass(), "getSupportedCodecTypes", Collection.class);
            Method status = CodecCarriers.method(proxy.getClass(), "getCodecStatus", statusType, BluetoothDevice.class);
            Method current = CodecCarriers.method(statusType, "getCodecConfig", configType);
            Method selectable = CodecCarriers.method(statusType, "getCodecsSelectableCapabilities", List.class);
            Collection<?> types = bounded(call(supported, proxy));
            if (types.isEmpty()) throw new IllegalStateException("Missing native codec inventory");
            JSONArray inventory = new JSONArray(); Map<Integer, Long> indexes = new HashMap<>(); Set<Long> ids = new HashSet<>();
            for (Object type : types) {
                if (type == null || type.getClass() != extendedType) throw new IllegalStateException("Unexpected native codec type");
                int index = (Integer) call(CodecCarriers.method(extendedType, "getNativeCodecType", int.class), type);
                long id = (Long) call(CodecCarriers.method(extendedType, "getCodecId", long.class), type);
                String name = (String) call(CodecCarriers.method(extendedType, "getCodecName", String.class), type);
                if (name == null || name.length() > 128 || indexes.put(index, id) != null || !ids.add(id))
                    throw new IllegalStateException("Invalid or duplicate native codec inventory");
                inventory.put(new JSONObject().put("native_index", index).put("codec_id", Long.toUnsignedString(id))
                        .put("codec_id_hex", Long.toUnsignedString(id, 16)).put("name", name));
            }
            report.put("supported", inventory).put("connection_state", proxy.getConnectionState(device));
            Object beforeStatus = call(status, proxy, device);
            if (beforeStatus == null) throw new IllegalStateException("No current codec status for the explicit pair");
            CodecCarriers.Config before = carriers.read(call(current, beforeStatus));
            CodecCarriers.inInventory(indexes, before);
            List<CodecCarriers.Config> choices = configurations(call(selectable, beforeStatus), carriers);
            JSONArray selectableReport = new JSONArray();
            for (CodecCarriers.Config choice : choices) {
                CodecCarriers.inInventory(indexes, choice);
                selectableReport.put(config(choice));
            }
            report.put("before", config(before)).put("selectable", selectableReport);
            ensureService();
            if (request.desired != null) {
                CodecCarriers.expected(before, request.expected);
                CodecCarriers.Config target = CodecCarriers.choose(choices, request.desired);
                CodecCarriers.inInventory(indexes, target);
                Object preference = carriers.highestPriority(target);
                report.put("requested", config(carriers.read(preference)))
                        .put("expected_before_codec_id", Long.toUnsignedString(request.expected));
                Object checkedStatus = call(status, proxy, device);
                if (checkedStatus == null) throw new IllegalStateException("Current codec unavailable before request");
                CodecCarriers.Config checked = carriers.read(call(current, checkedStatus));
                CodecCarriers.inInventory(indexes, checked);
                CodecCarriers.expected(checked, request.expected); // Narrow the non-atomic API race before sending.
                if (!CodecCarriers.matches(checked, request.desired, target.index())) {
                    Method set = CodecCarriers.method(proxy.getClass(), "setCodecConfigPreference", void.class,
                            BluetoothDevice.class, configType);
                    ensureService();
                    writeAttempted = true;
                    call(set, proxy, device, preference);
                }
                long deadline = SystemClock.elapsedRealtime() + 5000;
                CodecCarriers.Config after = null;
                do {
                    ensureService();
                    Object snapshot = call(status, proxy, device);
                    Object actual = snapshot == null ? null : call(current, snapshot);
                    after = actual == null ? null : carriers.read(actual);
                    if (after != null) CodecCarriers.inInventory(indexes, after);
                    if (CodecCarriers.matches(after, request.desired, target.index())) break;
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("Requested codec not confirmed within five seconds");
                    SystemClock.sleep(50);
                } while (true);
                report.put("after", config(after)).put("readback_matches_codec_id_and_index", true);
            }
            ensureService();
            accepted = true;
        } catch (Exception error) {
            Throwable cause = error;
            for (int depth = 0; depth < 8 && cause instanceof InvocationTargetException && cause.getCause() != null; ++depth)
                cause = cause.getCause();
            try { report.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage()); }
            catch (Exception ignored) { }
        } finally {
            BluetoothProfile close;
            synchronized (ownership) { finishing = true; close = ownedProxy; ownedProxy = null; }
            try { if (adapter != null && close != null) { adapter.closeProfileProxy(BluetoothProfile.A2DP, close); closed = true; } }
            catch (Exception error) { accepted = false; result.putString("proxy_cleanup_error", error.toString()); }
            finally {
                if (dropAttempt) try { getUiAutomation().dropShellPermissionIdentity(); dropped = true; }
                catch (Exception error) { accepted = false; result.putString("permission_cleanup_error", error.toString()); }
            }
        }
        try {
            report.put("accepted", accepted).put("write_attempted", writeAttempted).put("proxy_closed", closed)
                    .put("temporary_shell_identity_adopted", adopted).put("temporary_shell_identity_dropped", dropped)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            File output = new File(getTargetContext().getFilesDir(), "eld-route-probe-" + System.currentTimeMillis() + ".json");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                stream.write((report.toString(2) + "\n").getBytes("UTF-8"));
            }
            result.putString("report", output.getAbsolutePath());
            result.putString("result", report.toString());
        } catch (Exception error) { accepted = false; result.putString("error", error.toString()); }
        finish(accepted ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
