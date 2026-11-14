package me.kavishdevar.librepods.spatialinspector;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SpatialInspector {
    private static JSONObject error(Throwable failure) throws Exception {
        while (failure instanceof InvocationTargetException)
            failure = ((InvocationTargetException) failure).getTargetException();
        return new JSONObject().put("error_type", failure.getClass().getName());
    }

    private static String signature(Method method) {
        StringBuilder text = new StringBuilder(method.getReturnType().getTypeName())
            .append(' ').append(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; ++i) {
            if (i != 0) text.append(',');
            text.append(parameters[i].getTypeName());
        }
        return text.append(')').toString();
    }

    @SuppressWarnings("deprecation")
    private static Context context(JSONObject report) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        else if (Looper.myLooper() == null) Looper.prepare();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getMethod("systemMain");
        Object thread = systemMain.invoke(null);
        Method getContext = activityThread.getMethod("getSystemContext");
        Object value = getContext.invoke(thread);
        if (!(value instanceof Context)) throw new IllegalStateException("context_type_mismatch");
        Context context = (Context) value;
        report.put("context", new JSONObject().put("system_main_signature", signature(systemMain))
            .put("get_system_context_signature", signature(getContext)).put("package", context.getPackageName())
            .put("application_uid", context.getApplicationInfo().uid));
        return context;
    }

    private static final class Audio {
        final Class<?> api = Class.forName("android.media.IAudioService");
        final Class<?> attributes = Class.forName("android.media.AudioDeviceAttributes");
        final Object service;
        final Object selected;
        final String address;

        Audio(String address, JSONObject report) throws Exception {
            this.address = address;
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method getService = manager.getMethod("getService", String.class);
            if (getService.getReturnType() != IBinder.class) throw new NoSuchMethodException("getService_return");
            Object binderValue = getService.invoke(null, Context.AUDIO_SERVICE);
            if (!(binderValue instanceof IBinder)) throw new IllegalStateException("audio_binder_unavailable");
            IBinder binder = (IBinder) binderValue;
            Method asInterface = Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", IBinder.class);
            if (asInterface.getReturnType() != api) throw new NoSuchMethodException("asInterface_return");
            service = asInterface.invoke(null, binder);
            if (!api.isInstance(service)) throw new IllegalStateException("audio_service_type_mismatch");
            selected = attributes.getConstructor(Integer.TYPE, Integer.TYPE, String.class)
                .newInstance(2, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, address);
            report.put("audio_binder", new JSONObject().put("alive", binder.isBinderAlive())
                .put("get_service_signature", signature(getService)).put("as_interface_signature", signature(asInterface)));
        }

        private boolean selected(Object value) throws Exception {
            if (!attributes.isInstance(value)) throw new IllegalStateException("audio_device_type_mismatch");
            return ((Integer) attributes.getMethod("getRole").invoke(value)) == 2 &&
                ((Integer) attributes.getMethod("getType").invoke(value)) == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                address.equalsIgnoreCase((String) attributes.getMethod("getAddress").invoke(value));
        }

        JSONObject read(String name, Class<?> result, Class<?>[] parameters, Object... arguments) throws Exception {
            JSONObject record = new JSONObject().put("name", name).put("expected_return", result.getTypeName())
                .put("signature_valid", false).put("invoked", false);
            JSONArray parameterNames = new JSONArray();
            for (Class<?> parameter : parameters) parameterNames.put(parameter.getTypeName());
            record.put("expected_parameters", parameterNames);
            JSONArray candidates = new JSONArray();
            for (Method candidate : api.getMethods())
                if (candidate.getName().equals(name)) candidates.put(signature(candidate));
            record.put("available_signatures", candidates);
            try {
                Method method = InspectorContract.audioGetter(api, name, result, parameters);
                record.put("signature_valid", true).put("actual_signature", signature(method));
                record.put("invoked", true);
                Object value = method.invoke(service, arguments);
                if (result == List.class) {
                    if (!(value instanceof List)) throw new IllegalStateException("list_result_type_mismatch");
                    int count = 0;
                    for (Object device : (List<?>) value) if (device != null && selected(device)) ++count;
                    record.put("value_type", "selected_device_membership").put("selected_member", count > 0)
                        .put("selected_match_count", count);
                    if (name.equals("getDevicesForAttributes"))
                        record.put("single_selected_route", count == 1 && ((List<?>) value).size() == 1);
                } else if (result == int[].class) {
                    if (!(value instanceof int[])) throw new IllegalStateException("int_array_result_type_mismatch");
                    JSONArray array = new JSONArray();
                    for (int item : (int[]) value) array.put(item);
                    record.put("value_type", "int_array").put("value", array);
                } else if (result == Integer.TYPE && value instanceof Integer) {
                    record.put("value_type", "int").put("value", value);
                } else if (result == Boolean.TYPE && value instanceof Boolean) {
                    record.put("value_type", "boolean").put("value", value);
                } else throw new IllegalStateException("scalar_result_type_mismatch");
                record.put("ok", true);
            } catch (Throwable failure) {
                record.put("ok", false).put("error", error(failure));
            }
            return record;
        }

        JSONObject scalar(String name, Class<?> result) throws Exception {
            return read(name, result, new Class<?>[0]);
        }

        JSONObject device(String name) throws Exception {
            return read(name, Boolean.TYPE, new Class<?>[]{attributes}, selected);
        }
    }

    private static AudioAttributes media(int content) {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(content).build();
    }

    private static void audio(JSONObject report, String address) throws Exception {
        Audio audio = new Audio(address, report);
        AudioFormat format = new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build();
        JSONArray getters = new JSONArray();
        getters.put(audio.scalar("getSpatializerImmersiveAudioLevel", Integer.TYPE));
        getters.put(audio.scalar("isSpatializerEnabled", Boolean.TYPE));
        getters.put(audio.scalar("isSpatializerAvailable", Boolean.TYPE));
        getters.put(audio.scalar("getMode", Integer.TYPE));
        getters.put(audio.read("getDevicesForAttributes", List.class, new Class<?>[]{AudioAttributes.class},
            media(AudioAttributes.CONTENT_TYPE_MUSIC)));
        getters.put(audio.scalar("getSpatializerCompatibleAudioDevices", List.class));
        getters.put(audio.device("isSpatializerAvailableForDevice"));
        getters.put(audio.device("isHeadTrackerEnabled"));
        getters.put(audio.device("hasHeadTracker"));
        getters.put(audio.scalar("isHeadTrackerAvailable", Boolean.TYPE));
        getters.put(audio.scalar("getActualHeadTrackingMode", Integer.TYPE));
        getters.put(audio.scalar("getDesiredHeadTrackingMode", Integer.TYPE));
        getters.put(audio.scalar("getSupportedHeadTrackingModes", int[].class));
        getters.put(audio.scalar("getSpatializerOutput", Integer.TYPE));
        for (int content : new int[]{AudioAttributes.CONTENT_TYPE_MUSIC, AudioAttributes.CONTENT_TYPE_MOVIE}) {
            getters.put(audio.read("canBeSpatialized", Boolean.TYPE,
                new Class<?>[]{AudioAttributes.class, AudioFormat.class}, media(content), format)
                .put("content_type", content).put("format", "48000_float_5point1_back"));
        }
        report.put("audio_getters", getters);
    }

    private static void outputs(JSONObject report, Context context, String address) throws Exception {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) throw new IllegalStateException("audio_manager_unavailable");
        JSONArray selected = new JSONArray();
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && address.equalsIgnoreCase(device.getAddress()))
                selected.put(new JSONObject().put("id", device.getId()).put("type", device.getType()));
        }
        report.put("selected_audio_manager_outputs", selected);
    }

    private static void sensors(JSONObject report, Context context, UUID expected, int uid) throws Exception {
        JSONObject result = new JSONObject().put("enumeration_limited_by_uid", !InspectorContract.sensorUidPrivileged(uid))
            .put("uuid_may_be_anonymized", !InspectorContract.sensorUidPrivileged(uid))
            .put("empty_list_proves_absence", false);
        report.put("head_trackers", result);
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) throw new IllegalStateException("sensor_manager_unavailable");
        Method getUuid = Sensor.class.getMethod("getUuid");
        Method getId = Sensor.class.getMethod("getId");
        Method getHandle = Sensor.class.getMethod("getHandle");
        if (getUuid.getReturnType() != UUID.class || getId.getReturnType() != Integer.TYPE ||
            getHandle.getReturnType() != Integer.TYPE) throw new NoSuchMethodException("sensor_signature_mismatch");
        result.put("get_uuid_signature", signature(getUuid)).put("get_id_signature", signature(getId))
            .put("get_handle_signature", signature(getHandle));
        JSONArray selected = new JSONArray();
        for (Sensor sensor : manager.getDynamicSensorList(37)) {
            UUID uuid = (UUID) getUuid.invoke(sensor);
            if (!"LibrePods synthetic head tracker probe".equals(sensor.getName()) && !expected.equals(uuid)) continue;
            boolean visible = InspectorContract.sensorUidPrivileged(uid) && uuid != null;
            JSONObject record = new JSONObject().put("name", sensor.getName()).put("type", sensor.getType())
                .put("string_type", sensor.getStringType()).put("dynamic", sensor.isDynamicSensor())
                .put("id", getId.invoke(sensor)).put("handle", getHandle.invoke(sensor))
                .put("min_delay_us", sensor.getMinDelay()).put("max_delay_us", sensor.getMaxDelay())
                .put("reported_uuid", uuid == null ? JSONObject.NULL : uuid.toString())
                .put("uuid_visibility", visible ? "privileged_sensor_uid" : "possibly_anonymized")
                .put("matches_selected_uuid", visible ? expected.equals(uuid) : JSONObject.NULL);
            selected.put(record);
        }
        result.put("ok", true).put("selected_or_probe_sensors", selected);
    }

    public static void main(String[] args) {
        int exit = 0;
        JSONObject report = new JSONObject();
        try {
            if (args.length != 1) throw new IllegalArgumentException("one_address_required");
            String address = InspectorContract.address(args[0]);
            UUID expected = InspectorContract.expectedUuid(address);
            int uid = Process.myUid();
            report.put("schema", 1).put("read_only", true).put("uid", uid).put("sdk", Build.VERSION.SDK_INT)
                .put("selected_a2dp_address", address).put("expected_bt_sensor_uuid", expected.toString());
            Context context = null;
            try { context = context(report); } catch (Throwable failure) { report.put("context_error", error(failure)); }
            try { audio(report, address); } catch (Throwable failure) { report.put("audio_error", error(failure)); }
            if (context != null) {
                try { outputs(report, context, address); } catch (Throwable failure) { report.put("outputs_error", error(failure)); }
                try { sensors(report, context, expected, uid); } catch (Throwable failure) { report.put("sensors_error", error(failure)); }
            }
            report.put("inspection_completed", true);
        } catch (Throwable failure) {
            exit = 1;
            try { report.put("inspection_completed", false).put("error", error(failure)); }
            catch (Exception ignored) { }
        }
        System.out.println(report.toString());
        System.out.flush();
        System.exit(exit);
    }
}
