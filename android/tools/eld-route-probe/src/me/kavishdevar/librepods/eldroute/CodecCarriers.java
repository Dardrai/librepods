// SPDX-License-Identifier: GPL-3.0-or-later
package me.kavishdevar.librepods.eldroute;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Copies one existing extended carrier; never manufactures a codec identity. */
final class CodecCarriers {
    final Class<?> configClass, typeClass;
    private final Method[] fields = new Method[9];
    private final Method extended, index, id, name;
    private final Constructor<?> constructor;
    static final class Config {
        final Object source, type;
        final int[] integers;
        final long[] specifics;
        final long id;
        final String name;
        Config(Object source, Object type, int[] integers, long[] specifics, long id, String name) {
            this.source = source; this.type = type; this.integers = integers; this.specifics = specifics;
            this.id = id; this.name = name;
        }
        int index() { return integers[0]; }
    }
    CodecCarriers(Class<?> configClass, Class<?> typeClass) throws Exception {
        this.configClass = configClass; this.typeClass = typeClass;
        String[] names = {"getCodecType", "getCodecPriority", "getSampleRate", "getBitsPerSample", "getChannelMode",
                "getCodecSpecific1", "getCodecSpecific2", "getCodecSpecific3", "getCodecSpecific4"};
        for (int i = 0; i < fields.length; ++i) fields[i] = method(configClass, names[i], i < 5 ? int.class : long.class);
        extended = method(configClass, "getExtendedCodecType", typeClass);
        index = method(typeClass, "getNativeCodecType", int.class);
        id = method(typeClass, "getCodecId", long.class);
        name = method(typeClass, "getCodecName", String.class);
        constructor = configClass.getDeclaredConstructor(typeClass, int.class, int.class, int.class, int.class,
                long.class, long.class, long.class, long.class);
        constructor.setAccessible(true);
    }
    static Method method(Class<?> owner, String name, Class<?> result, Class<?>... parameters) throws Exception {
        Method found = owner.getDeclaredMethod(name, parameters);
        if (found.getReturnType() != result || Modifier.isStatic(found.getModifiers()) || found.isVarArgs())
            throw new IllegalStateException("Unexpected " + owner.getName() + "." + name + " signature");
        found.setAccessible(true);
        return found;
    }
    Config read(Object value) throws Exception {
        if (value == null || value.getClass() != configClass) throw new IllegalStateException("Unexpected codec carrier");
        Object type = extended.invoke(value);
        if (type == null || type.getClass() != typeClass) throw new IllegalStateException("Missing extended codec type");
        int[] integers = new int[5]; long[] specifics = new long[4];
        for (int i = 0; i < 5; ++i) integers[i] = (Integer) fields[i].invoke(value);
        for (int i = 0; i < 4; ++i) specifics[i] = (Long) fields[i + 5].invoke(value);
        if ((Integer) index.invoke(type) != integers[0]) throw new IllegalStateException("Codec index getters disagree");
        String label = (String) name.invoke(type);
        if (label == null || label.length() > 128) throw new IllegalStateException("Invalid codec name");
        return new Config(value, type, integers, specifics, (Long) id.invoke(type), label);
    }
    Object highestPriority(Config before) throws Exception {
        Object copy = constructor.newInstance(before.type, 1000000, before.integers[2], before.integers[3],
                before.integers[4], before.specifics[0], before.specifics[1], before.specifics[2], before.specifics[3]);
        Config after = read(copy);
        int[] expected = before.integers.clone(); expected[1] = 1000000;
        if (after.type != before.type || after.id != before.id || !Arrays.equals(after.integers, expected) ||
                !Arrays.equals(after.specifics, before.specifics)) throw new IllegalStateException("Carrier copy changed fields");
        return copy;
    }
    static Config choose(List<Config> selectable, long desired) {
        Config chosen = null;
        for (Config candidate : selectable) if (candidate.id == desired) {
            if (chosen != null) throw new IllegalStateException("Ambiguous selectable codec ID");
            chosen = candidate;
        }
        if (chosen == null) throw new IllegalStateException("Requested codec is not currently selectable");
        return chosen;
    }
    static void expected(Config current, long expected) {
        if (current == null || current.id != expected) throw new IllegalStateException("Current codec changed; expectedBeforeCodecId does not match");
    }
    static void inInventory(Map<Integer, Long> inventory, Config value) {
        Long id = inventory.get(value.index());
        if (id == null || id != value.id) throw new IllegalStateException("Codec ID/index pair disagrees with native inventory");
    }
    static boolean matches(Config current, long desired, int index) {
        return current != null && current.id == desired && current.index() == index;
    }
}
