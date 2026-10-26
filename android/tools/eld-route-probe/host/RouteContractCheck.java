// SPDX-License-Identifier: GPL-3.0-or-later
package me.kavishdevar.librepods.eldroute;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class RouteContractCheck {
    private static int checks;
    private static void check(boolean value) { ++checks; if (!value) throw new AssertionError("check " + checks); }
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action) throws Exception {
        ++checks;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("accepted invalid request at check " + checks);
    }
    private static Map<String, String> args(String... values) {
        Map<String, String> args = new HashMap<>(); args.put("address", "aa:bb:cc:dd:ee:ff");
        for (int i = 0; i < values.length; i += 2) args.put(values[i], values[i + 1]);
        return args;
    }
    public static final class Type {
        final int index; final long id;
        Type(int index, long id) { this.index = index; this.id = id; }
        public int getNativeCodecType() { return index; }
        public long getCodecId() { return id; }
        public String getCodecName() { return "existing codec"; }
    }
    public static final class Config {
        final Type type; final int priority, rate, bits, channels; final long a, b, c, d;
        public Config(Type type, int priority, int rate, int bits, int channels, long a, long b, long c, long d) {
            this.type = type; this.priority = priority; this.rate = rate; this.bits = bits; this.channels = channels;
            this.a = a; this.b = b; this.c = c; this.d = d;
        }
        public int getCodecType() { return type.index; }
        public Type getExtendedCodecType() { return type; }
        public int getCodecPriority() { return priority; }
        public int getSampleRate() { return rate; }
        public int getBitsPerSample() { return bits; }
        public int getChannelMode() { return channels; }
        public long getCodecSpecific1() { return a; }
        public long getCodecSpecific2() { return b; }
        public long getCodecSpecific3() { return c; }
        public long getCodecSpecific4() { return d; }
    }
    public static void main(String[] ignored) throws Exception {
        RouteRequest read = RouteRequest.parse(args());
        check(read.desired == null && read.action.equals("status") && read.address.equals("AA:BB:CC:DD:EE:FF"));
        RouteRequest eld = RouteRequest.parse(args("action", "selectEld", "expectedBeforeCodecId", "2"));
        check(eld.desired == RouteRequest.ELD && eld.expected == 2);
        RouteRequest aac = RouteRequest.parse(args("action", "selectAac", "expectedBeforeCodecId", "0x8001004cff"));
        check(aac.desired == 2 && aac.expected == RouteRequest.ELD);
        check(RouteRequest.codecId("18446744073709551615") == -1L && RouteRequest.codecId("0xffffffffffffffff") == -1L);
        for (String bad : Arrays.asList("-1", "+2", " 2", "0x", "18446744073709551616", "0x10000000000000000", "2.0"))
            rejected(() -> RouteRequest.codecId(bad));
        rejected(() -> RouteRequest.parse(Collections.emptyMap()));
        rejected(() -> RouteRequest.parse(args("address", "AA:BB:CC:DD:EE")));
        rejected(() -> RouteRequest.parse(args("action", "selectEld")));
        rejected(() -> RouteRequest.parse(args("action", "connect")));
        rejected(() -> RouteRequest.parse(args("action", "status", "expectedBeforeCodecId", "2")));
        rejected(() -> RouteRequest.parse(args("random", "value")));
        CodecCarriers carriers = new CodecCarriers(Config.class, Type.class);
        Type type = new Type(5, RouteRequest.ELD);
        Config original = new Config(type, 10001, 3, 7, 2, Long.MIN_VALUE, Long.MAX_VALUE, -1, 0x123456789abcdefL);
        CodecCarriers.Config fields = carriers.read(original);
        Config copy = (Config) carriers.highestPriority(fields);
        check(copy != original && copy.type == type && copy.priority == 1000000 && original.priority == 10001);
        check(copy.rate == 3 && copy.bits == 7 && copy.channels == 2);
        check(copy.a == Long.MIN_VALUE && copy.b == Long.MAX_VALUE && copy.c == -1 && copy.d == original.d);
        check(carriers.read(copy).id == RouteRequest.ELD);
        check(CodecCarriers.choose(Collections.singletonList(fields), RouteRequest.ELD) == fields);
        rejected(() -> CodecCarriers.choose(Collections.emptyList(), RouteRequest.ELD));
        rejected(() -> CodecCarriers.choose(Arrays.asList(fields, fields), RouteRequest.ELD));
        rejected(() -> carriers.read(new Object()));
        CodecCarriers.expected(fields, RouteRequest.ELD);
        rejected(() -> CodecCarriers.expected(fields, 2));
        Map<Integer, Long> inventory = new HashMap<>(); inventory.put(5, RouteRequest.ELD); inventory.put(1, 2L);
        CodecCarriers.inInventory(inventory, fields);
        inventory.put(5, 2L);
        rejected(() -> CodecCarriers.inInventory(inventory, fields));
        check(!CodecCarriers.matches(null, RouteRequest.ELD, 5));
        check(CodecCarriers.matches(fields, RouteRequest.ELD, 5));
        check(!CodecCarriers.matches(fields, RouteRequest.ELD, 1));
        check(!CodecCarriers.matches(fields, RouteRequest.AAC, 5));
        System.out.println("PASS " + checks + " request, exact-ID/index, carrier preservation and readback-guard checks");
    }
}
