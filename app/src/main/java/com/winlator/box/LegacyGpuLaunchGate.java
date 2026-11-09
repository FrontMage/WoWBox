package com.winlator.box;

import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Scope additional launch verification to the eligible legacy GPU path. */
final class LegacyGpuLaunchGate {
    private LegacyGpuLaunchGate() {}
    static boolean isExplicitGpu(Map<String, String> launchEnv) {
        if (launchEnv == null) return false;
        for (String key : GpuExplicitEnvironmentPolicy.KEYS) if (launchEnv.containsKey(key)) return true;
        return false;
    }
    static boolean shouldVerify(JSONObject status, boolean warm, Map<String, String> launchEnv) {
        // No lifecycle shortcut: currentInstance may still be initializing or
        // shutting down. Even a warm launch must not bypass layer verification.
        return !isExplicitGpu(launchEnv) && status != null && status.optBoolean("eligible", false);
    }
    static void requireNoOrphan(boolean eligible, boolean activityExists, boolean wineActive) {
        if (eligible && !activityExists && wineActive) throw new IllegalStateException("redmagic_gpu_existing_wine_active");
    }
    static void requireVerified(JSONObject overlay, JSONObject layers) {
        JSONArray entries = layers != null ? layers.optJSONArray("layers") : null;
        if (overlay == null || !overlay.optBoolean("ok", false) || layers == null ||
                !layers.optBoolean("ok", false) || entries == null || entries.length() != 9) {
            throw new IllegalStateException("redmagic_gpu_layers_unverified");
        }
    }
}
