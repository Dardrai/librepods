// SPDX-License-Identifier: GPL-3.0-or-later
package me.kavishdevar.librepods.eldroute;

import java.util.Locale;
import java.util.Map;

final class RouteRequest {
    static final long ELD = 0x8001004cffL, AAC = 2;
    final String action, address;
    final Long expected, desired;
    private RouteRequest(String action, String address, Long expected, Long desired) {
        this.action = action; this.address = address; this.expected = expected; this.desired = desired;
    }
    static RouteRequest parse(Map<String, String> args) {
        for (String key : args.keySet())
            if (!key.equals("action") && !key.equals("address") && !key.equals("expectedBeforeCodecId"))
                throw new IllegalArgumentException("Unknown argument: " + key);
        String action = args.getOrDefault("action", "status"), address = args.get("address");
        if (address == null || !address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))
            throw new IllegalArgumentException("An explicit Bluetooth address is required");
        Long desired;
        if (action.equals("status") || action.equals("inventory")) desired = null;
        else if (action.equals("selectEld")) desired = ELD;
        else if (action.equals("selectAac")) desired = AAC;
        else throw new IllegalArgumentException("Use status, inventory, selectEld or selectAac");
        String expected = args.get("expectedBeforeCodecId");
        if (desired == null && expected != null) throw new IllegalArgumentException("Read actions take no expected codec ID");
        return new RouteRequest(action, address.toUpperCase(Locale.ROOT),
                desired == null ? null : codecId(expected), desired);
    }
    static long codecId(String value) {
        if (value == null || !value.matches("(0[xX][0-9a-fA-F]{1,16}|[0-9]{1,20})"))
            throw new IllegalArgumentException("A write requires expectedBeforeCodecId as unsigned decimal or 0x hex");
        return value.startsWith("0x") || value.startsWith("0X")
                ? Long.parseUnsignedLong(value.substring(2), 16) : Long.parseUnsignedLong(value, 10);
    }
}
