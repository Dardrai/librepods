package me.kavishdevar.librepods.headtrackerlease;

public final class LeaseStateCheck {
    private static final class Fake implements LeaseState.Access {
        boolean global = true, route = true, spatial = true, head;
        boolean failAfterWrite, ignoreWrite;
        boolean cancelled, cancelOnRead;
        int writes;
        public LeaseState.Flags flags() { return new LeaseState.Flags(spatial, head); }
        public LeaseState.State read() {
            if (cancelOnRead) cancelled = true;
            return new LeaseState.State(flags(), global, route);
        }
        public void setHeadTracking(boolean enabled) {
            ++writes;
            if (!ignoreWrite) head = enabled;
            if (failAfterWrite && enabled) throw new IllegalStateException("lost_binder_reply");
        }
    }

    public static void main(String[] args) throws Exception {
        for (int denied = 0; denied < 3; ++denied) {
            Fake fake = new Fake();
            if (denied == 0) fake.global = false;
            if (denied == 1) fake.route = false;
            if (denied == 2) fake.spatial = false;
            try { new LeaseState(fake).capture(); throw new AssertionError(); }
            catch (IllegalStateException expected) { }
            check(fake.writes == 0);
        }
        Fake normal = new Fake();
        LeaseState lease = new LeaseState(normal);
        lease.capture(); lease.enable(() -> true); check(normal.head && normal.writes == 1);
        lease.restore(); lease.restore(); check(!normal.head && normal.spatial && normal.writes == 2);
        Fake changed = new Fake();
        lease = new LeaseState(changed); lease.capture(); changed.head = true;
        try { lease.enable(() -> true); throw new AssertionError(); } catch (IllegalStateException expected) { }
        check(changed.writes == 0);
        Fake lostReply = new Fake(); lostReply.failAfterWrite = true;
        lease = new LeaseState(lostReply); lease.capture();
        try { lease.enable(() -> true); throw new AssertionError(); } catch (IllegalStateException expected) { }
        lease.restore(); check(!lostReply.head && lostReply.writes == 2);
        Fake ignored = new Fake(); ignored.ignoreWrite = true;
        lease = new LeaseState(ignored); lease.capture();
        try { lease.enable(() -> true); throw new AssertionError(); } catch (IllegalStateException expected) { }
        lease.restore(); check(!ignored.head);
        Fake already = new Fake(); already.head = true;
        lease = new LeaseState(already); lease.capture(); lease.enable(() -> true); lease.restore(); check(already.writes == 0);
        Fake external = new Fake();
        lease = new LeaseState(external); lease.capture(); lease.enable(() -> true); external.spatial = false;
        try { lease.restore(); throw new AssertionError(); } catch (IllegalStateException expected) { }
        check(!external.head && !external.spatial && external.writes == 2);
        Fake cancelled = new Fake();
        lease = new LeaseState(cancelled); lease.capture(); cancelled.cancelOnRead = true;
        try { lease.enable(() -> !cancelled.cancelled); throw new AssertionError(); }
        catch (IllegalStateException expected) { }
        check(cancelled.writes == 0 && !cancelled.head);
        check(LeaseState.address("12:34:56:78:9a:bc").equals("12:34:56:78:9A:BC"));
        for (String invalid : new String[]{null, "", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "12:34:56:78:9A:BG"}) {
            try { LeaseState.address(invalid); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        }
        System.out.println("Lease preconditions, drift, lost reply, readbacks, idempotent restoration and spatial preservation: passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
