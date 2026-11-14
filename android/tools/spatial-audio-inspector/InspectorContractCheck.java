package me.kavishdevar.librepods.spatialinspector;

public final class InspectorContractCheck {
    public static final class FakeAudio {
        public boolean isSpatializerEnabled() { throw new AssertionError("must not invoke"); }
        public String getSpatializerImmersiveAudioLevel() { throw new AssertionError("must not invoke"); }
        public void setSpatializerEnabled(boolean value) { throw new AssertionError("must not invoke"); }
    }

    public static void main(String[] args) throws Exception {
        check(InspectorContract.address("12:34:56:78:9a:bc").equals("12:34:56:78:9A:BC"));
        check(InspectorContract.expectedUuid("12:34:56:78:9a:bc").toString()
            .equals("00000000-0000-0000-4254-123456789abc"));
        for (String invalid : new String[]{null, "", "12-34:56:78:9a:bc", "12:34:56:78:9a:bcX",
                "12:34:56:78:9a:bg", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF"}) {
            try { InspectorContract.address(invalid); throw new AssertionError("accepted invalid address"); }
            catch (IllegalArgumentException expected) { }
        }
        check(!InspectorContract.sensorUidPrivileged(0));
        check(!InspectorContract.sensorUidPrivileged(2000));
        check(InspectorContract.sensorUidPrivileged(1000));
        check(InspectorContract.sensorUidPrivileged(101000));
        check(InspectorContract.sensorUidPrivileged(1041));
        check(InspectorContract.audioGetter(FakeAudio.class, "isSpatializerEnabled", Boolean.TYPE)
            .getReturnType() == Boolean.TYPE);
        try {
            InspectorContract.audioGetter(FakeAudio.class, "getSpatializerImmersiveAudioLevel", Integer.TYPE);
            throw new AssertionError("accepted wrong return type");
        } catch (NoSuchMethodException expected) { }
        try {
            InspectorContract.audioGetter(FakeAudio.class, "setSpatializerEnabled", Void.TYPE, Boolean.TYPE);
            throw new AssertionError("accepted setter");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Inspector MAC/BT UUID, UID visibility, exact signatures and getter-only allowlist: passed");
    }

    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
