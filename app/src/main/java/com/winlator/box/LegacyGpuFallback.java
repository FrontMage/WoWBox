package com.winlator.box;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Report a safe original-driver fallback only after all original layers verify. */
final class LegacyGpuFallback {
    private static final String META_SHA = "cc23faad77d081f97522e1fff190ee13f8b1e10eebc9047ae505cf248ee8cb98";
    private static final String LIB_SHA = "27f6d9e712ac260c75a2acfdf619ffbc967c76baa5d1c3de8657210167f80a80";
    private static final String ROOT = "@app/contents/adrenotools/" + LegacyRedMagicGpuDecision.OLD_ID + "/";
    private LegacyGpuFallback() {}
    static JSONObject apply(JSONObject status, JSONObject layers) throws JSONException {
        if (!status.optBoolean("eligible", false) || status.optBoolean("overrideAvailable", false)) return status;
        JSONArray entries = layers != null ? layers.optJSONArray("layers") : null;
        boolean oldVerified = layers != null && layers.optBoolean("ok", false) && entries != null && entries.length() == 9 && trustedGpu(entries);
        status.put("fallbackToOriginal", oldVerified);
        status.put("ready", oldVerified);
        status.put("driverId", oldVerified ? LegacyRedMagicGpuDecision.OLD_ID : "");
        status.put("reason", oldVerified ? "override_unavailable_using_verified_original" : "override_and_original_unverified");
        return status;
    }

    private static boolean trustedGpu(JSONArray entries) {
        JSONObject gpu = null;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject row = entries.optJSONObject(i);
            if (row == null || !"gpu-driver-stack".equals(row.optString("type"))) continue;
            if (gpu != null) return false;
            gpu = row;
        }
        if (gpu == null || !LegacyRedMagicGpuDecision.OLD_SOURCE.equals(gpu.optString("source")) ||
                !LegacyRedMagicGpuDecision.OLD_ID.equals(gpu.optString("target")) ||
                !gpu.optBoolean("installed", false) || !gpu.optBoolean("sourceMatches", false) ||
                !gpu.optBoolean("identityMatches", false) || !gpu.optBoolean("criticalBaselineAvailable", false) ||
                !gpu.optBoolean("criticalFilesPresent", false) || !gpu.optBoolean("criticalFilesMatch", false)) return false;
        return trustedFiles(gpu.optJSONArray("baselineCriticalFiles")) && trustedFiles(gpu.optJSONArray("criticalFiles"));
    }

    private static boolean trustedFiles(JSONArray files) {
        if (files == null || files.length() != 2) return false;
        boolean meta = false, lib = false;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null || !file.optBoolean("exists", false) || file.optBoolean("unsafe", false)) return false;
            String path = file.optString("path"), sha = file.optString("sha256");
            if ((ROOT + "meta.json").equals(path) && META_SHA.equals(sha) && !meta) meta = true;
            else if ((ROOT + "vulkan.ad07xx.so").equals(path) && LIB_SHA.equals(sha) && !lib) lib = true;
            else return false;
        }
        return meta && lib;
    }
}
