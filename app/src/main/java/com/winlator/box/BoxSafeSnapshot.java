package com.winlator.box;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Constructs an intentionally limited summary suitable for reviewing before sharing. */
public final class BoxSafeSnapshot {
    private static final Set<String> LAYER_TYPES = new HashSet<>(Arrays.asList(
            "base-imagefs", "graphics-wrapper-stack", "frame-generation-stack", "prefix-template",
            "wine-runtime", "input-bridge-stack", "dx-wrapper-stack", "native-dx-wrapper-stack",
            "gpu-driver-stack", "cpu-emu-stack"));

    private BoxSafeSnapshot() {}

    /** Never copies arbitrary user-defined strings, paths, environment names or session data. */
    public static JSONObject create(JSONObject snapshot, String appVersion, int androidSdk) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("format", "wowbox-safe-summary-v1");
        if (appVersion != null && appVersion.matches("[A-Za-z0-9._-]{1,40}")) result.put("appVersion", appVersion);
        if (androidSdk > 0 && androidSdk < 1000) result.put("androidSdk", androidSdk);

        JSONObject source = snapshot != null ? snapshot : new JSONObject();
        JSONObject state = source.optJSONObject("state");
        if (state != null && state.has("installed")) result.put("installed", state.optBoolean("installed", false));

        JSONArray safeLayers = new JSONArray();
        JSONArray layers = source.optJSONArray("layers");
        Set<String> seen = new HashSet<>();
        if (layers != null) {
            for (int i = 0; i < layers.length(); i++) {
                JSONObject layer = layers.optJSONObject(i);
                if (layer == null) continue;
                String type = layer.optString("type", "");
                if (!LAYER_TYPES.contains(type) || !seen.add(type)) continue;
                JSONObject safeLayer = new JSONObject();
                safeLayer.put("type", type);
                safeLayer.put("recordedInstalled", layer.has("installedAt") && !layer.isNull("installedAt"));
                safeLayers.put(safeLayer);
            }
        }
        result.put("layers", safeLayers);

        JSONObject env = source.optJSONObject("env");
        JSONObject flags = new JSONObject();
        copyBinaryFlag(env, flags, "WINEESYNC");
        copyBinaryFlag(env, flags, "WINEFSYNC");
        if (flags.length() > 0) result.put("runtimeFlags", flags);

        JSONObject overlay = source.optJSONObject("runtimeOverlay");
        if (overlay != null) result.put("runtimeOverlayActive", overlay.length() > 0);
        JSONObject session = source.optJSONObject("currentSession");
        if (session != null) {
            String status = session.optString("status", "");
            if (status.equals("running") || status.equals("finished")) result.put("sessionStatus", status);
        }
        return result;
    }

    private static void copyBinaryFlag(JSONObject env, JSONObject flags, String name) throws JSONException {
        if (env == null) return;
        JSONObject item = env.optJSONObject(name);
        if (item == null) return;
        String value = item.optString("value", "");
        if (value.equals("0") || value.equals("1")) flags.put(name, value.equals("1"));
    }
}
