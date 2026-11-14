package me.kavishdevar.librepods.headtrackerlease;

import java.util.Locale;
import java.util.function.BooleanSupplier;

final class LeaseState {
    static String address(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"))
            throw new IllegalArgumentException("invalid_address");
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.equals("00:00:00:00:00:00") || normalized.equals("FF:FF:FF:FF:FF:FF"))
            throw new IllegalArgumentException("invalid_address");
        return normalized;
    }

    static final class Flags {
        final boolean spatial, head;
        Flags(boolean spatial, boolean head) { this.spatial = spatial; this.head = head; }
        boolean same(Flags other) { return spatial == other.spatial && head == other.head; }
    }

    static final class State {
        final Flags flags;
        final boolean global, route;
        State(Flags flags, boolean global, boolean route) { this.flags = flags; this.global = global; this.route = route; }
        void requireEnabledRoute() {
            if (!global || !route || !flags.spatial) throw new IllegalStateException("enable_precondition_failed");
        }
    }

    interface Access {
        Flags flags() throws Exception;
        State read() throws Exception;
        void setHeadTracking(boolean enabled) throws Exception;
    }

    final Access access;
    Flags original;
    boolean attempted;
    private boolean restored;
    LeaseState(Access access) { this.access = access; }

    synchronized Flags capture() throws Exception {
        State state = access.read();
        state.requireEnabledRoute();
        original = state.flags;
        return original;
    }

    synchronized void enable(BooleanSupplier permitted) throws Exception {
        if (original == null) throw new IllegalStateException("not_captured");
        State current = access.read();
        current.requireEnabledRoute();
        if (!original.same(current.flags)) throw new IllegalStateException("state_changed");
        if (!permitted.getAsBoolean()) throw new IllegalStateException("lease_cancelled_before_write");
        if (!original.head) {
            attempted = true; // A Binder failure can happen after the service applied the write.
            access.setHeadTracking(true);
        }
        State after = access.read();
        after.requireEnabledRoute();
        if (!after.flags.head || after.flags.spatial != original.spatial)
            throw new IllegalStateException("enable_readback_failed");
    }

    synchronized Flags restore() throws Exception {
        if (original == null) return null;
        Flags current = access.flags();
        if (!restored && attempted && !original.head && current.head) access.setHeadTracking(false);
        Flags after = access.flags();
        if (!after.same(original)) throw new IllegalStateException("restore_readback_changed");
        restored = true;
        return after;
    }
}
