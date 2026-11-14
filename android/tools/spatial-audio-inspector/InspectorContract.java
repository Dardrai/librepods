package me.kavishdevar.librepods.spatialinspector;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class InspectorContract {
    private static final Set<String> AUDIO_GETTERS = new HashSet<>(Arrays.asList(
        "getSpatializerImmersiveAudioLevel", "isSpatializerEnabled", "isSpatializerAvailable",
        "getDevicesForAttributes", "canBeSpatialized", "isSpatializerAvailableForDevice",
        "getSpatializerCompatibleAudioDevices", "hasHeadTracker", "isHeadTrackerEnabled",
        "isHeadTrackerAvailable", "getActualHeadTrackingMode", "getDesiredHeadTrackingMode",
        "getSupportedHeadTrackingModes", "getSpatializerOutput", "getMode"));

    static String address(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"))
            throw new IllegalArgumentException("invalid_address");
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.equals("00:00:00:00:00:00") || normalized.equals("FF:FF:FF:FF:FF:FF"))
            throw new IllegalArgumentException("invalid_address");
        return normalized;
    }

    static UUID expectedUuid(String address) {
        return new UUID(0, 0x4254000000000000L | Long.parseLong(address(address).replace(":", ""), 16));
    }

    static boolean sensorUidPrivileged(int uid) { return uid % 100000 == 1000 || uid == 1041; }

    static Method audioGetter(Class<?> type, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        if (!AUDIO_GETTERS.contains(name)) throw new IllegalArgumentException("getter_not_allowlisted");
        Method method = type.getMethod(name, parameters);
        if (method.getReturnType() != result) throw new NoSuchMethodException("return_type_mismatch");
        return method;
    }
}
