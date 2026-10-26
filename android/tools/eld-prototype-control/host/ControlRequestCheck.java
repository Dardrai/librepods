package me.kavishdevar.librepods.eldcontrol;
import java.util.HashMap;
import java.util.Map;
public final class ControlRequestCheck {
    public static void main(String[] args) {
        if (ControlRequest.parse(new HashMap<>()).desired != null) throw new AssertionError();
        Map<String,String> values = new HashMap<>(); values.put("action","enable");
        reject(values); values.put("expectedBefore","false");
        if (!ControlRequest.parse(values).desired) throw new AssertionError();
        values.put("action","restore"); values.put("enabled","false");
        if (ControlRequest.parse(values).desired) throw new AssertionError();
        values.put("enabled","1"); reject(values);
        values.put("action","unknown"); reject(values);
        if (!ControlRequest.restoreCommand(false,true).contains("-e enabled false -e expectedBefore true")) throw new AssertionError();
        System.out.println("ControlRequest checks passed");
    }
    private static void reject(Map<String,String> value) {
        try { ControlRequest.parse(value); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
    }
}
