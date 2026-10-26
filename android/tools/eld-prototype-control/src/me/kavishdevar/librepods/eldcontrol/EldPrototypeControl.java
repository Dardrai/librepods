package me.kavishdevar.librepods.eldcontrol;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Explicit operator tool; only the module's existing remote preference API is used. */
public final class EldPrototypeControl extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : new Bundle(args); start(); }
    @Override public void onStart() {
        if (Looper.myLooper() == Looper.getMainLooper()) new Thread(new Runnable() {
            @Override public void run() { execute(); }
        }, "eld-control").start();
        else execute();
    }
    private void execute() {
        Bundle result = new Bundle();
        result.putString("key", ControlRequest.KEY);
        result.putString("native_runtime_readiness", "not_checked; this changes startup opt-in only");
        boolean attempted = false;
        int code = Activity.RESULT_CANCELED;
        try {
            Map<String, String> values = new HashMap<>();
            for (String key : new String[]{"action", "enabled", "expectedBefore"}) {
                String value = arguments.getString(key);
                if (value != null) values.put(key, value);
            }
            ControlRequest request = ControlRequest.parse(values);
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> holder = Class.forName("me.kavishdevar.librepods.utils.XposedServiceHolder", true, loader);
            Object instance = holder.getField("INSTANCE").get(null);
            Method serviceGetter = holder.getMethod("getService");
            long deadline = SystemClock.elapsedRealtime() + 5000;
            Object service = serviceGetter.invoke(instance);
            while (service == null) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("Xposed preference service unavailable after five seconds");
                SystemClock.sleep(25);
                service = serviceGetter.invoke(instance);
            }
            SharedPreferences prefs = (SharedPreferences) service.getClass()
                .getMethod("getRemotePreferences", String.class).invoke(service, "me.kavishdevar.librepods");
            boolean before = prefs.getBoolean(ControlRequest.KEY, false);
            result.putBoolean("before", before);
            result.putString("restore_command", ControlRequest.restoreCommand(before, request.desired == null ? before : request.desired));
            if (request.desired != null && before != request.expected) {
                result.putBoolean("after", before);
                throw new IllegalStateException("Preference changed; expectedBefore does not match");
            }
            if (request.desired != null && before != request.desired) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("Service deadline expired before write");
                attempted = true;
                boolean committed = prefs.edit().putBoolean(ControlRequest.KEY, request.desired).commit();
                result.putBoolean("committed", committed);
                if (!committed) throw new IllegalStateException("Preference commit failed");
            }
            boolean after = prefs.getBoolean(ControlRequest.KEY, false);
            while (request.desired != null && after != request.desired && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25);
                after = prefs.getBoolean(ControlRequest.KEY, false);
            }
            result.putBoolean("after", after);
            if (request.desired != null && after != request.desired) throw new IllegalStateException("Preference write not confirmed");
            result.putString("status", attempted ? "changed" : "unchanged");
            result.putString("restart", "No restart performed; Bluetooth process reads opt-in at startup");
            code = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("status", "failed");
            result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            result.putBoolean("write_attempted", attempted);
            finish(code, result);
        }
    }
}
