package com.winlator.box;

import android.os.Build;

/** Restrict device-specific graphics adaptations to the validated hardware. */
public final class RedMagicDevicePolicy {
    private RedMagicDevicePolicy() {}

    public static boolean matchesDevice(String model, String socModel) {
        return "NX809J".equalsIgnoreCase(model) && "SM8850".equalsIgnoreCase(socModel);
    }

    public static boolean isTarget() {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            // SOC_MODEL is public since API 31; this project compiles against API 30.
            Object soc = Build.class.getField("SOC_MODEL").get(null);
            return soc instanceof String && matchesDevice(Build.MODEL, (String)soc);
        } catch (ReflectiveOperationException | SecurityException | LinkageError ignored) {
            return false;
        }
    }
}
