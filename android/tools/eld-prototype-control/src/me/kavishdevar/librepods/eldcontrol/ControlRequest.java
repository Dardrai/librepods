package me.kavishdevar.librepods.eldcontrol;

import java.util.Map;

final class ControlRequest {
    static final String KEY = "aac_eld_hook";
    static final String RUNNER = "me.kavishdevar.librepods.eldcontrol/.EldPrototypeControl";
    final Boolean desired, expected;
    private ControlRequest(Boolean desired, Boolean expected) { this.desired = desired; this.expected = expected; }
    static ControlRequest parse(Map<String, String> args) {
        String action = args.getOrDefault("action", "read");
        if ("read".equals(action)) return new ControlRequest(null, null);
        Boolean desired;
        if ("enable".equals(action)) desired = true;
        else if ("disable".equals(action)) desired = false;
        else if ("restore".equals(action)) desired = bool(args.get("enabled"));
        else throw new IllegalArgumentException("Use read, enable, disable or restore");
        return new ControlRequest(desired, bool(args.get("expectedBefore")));
    }
    private static boolean bool(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException("Write actions require explicit true/false values and expectedBefore");
    }
    static String restoreCommand(boolean before, boolean after) {
        return "am instrument -w -e action restore -e enabled " + before +
            " -e expectedBefore " + after + " " + RUNNER;
    }
}
