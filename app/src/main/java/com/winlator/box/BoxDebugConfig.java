package com.winlator.box;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;

public class BoxDebugConfig {
    public static final String PROFILE_BASELINE = WineDebugConfig.PROFILE_BASELINE;
    public static final String PROFILE_INIT_STATE = WineDebugConfig.PROFILE_INIT_STATE;
    public static final String PROFILE_DEEP_FOCUS = WineDebugConfig.PROFILE_DEEP_FOCUS;

    public static final String EXTRA_PROFILE = "box_debug_profile";
    public static final String EXTRA_FOCUS = "box_debug_focus";
    public static final String EXTRA_DURATION = "box_debug_duration_seconds";
    public static final String EXTRA_ENV_OVERRIDES = "box_debug_env_overrides_json";
    public static final String EXTRA_SOURCE = "box_debug_source";

    public String profile = "";
    public String focus = "";
    public int durationSeconds = 0;
    public String source = "";
    public final LinkedHashMap<String, String> envOverrides = new LinkedHashMap<>();

    public boolean isEnabled() {
        return !profile.isEmpty();
    }

    public static BoxDebugConfig none() {
        return new BoxDebugConfig();
    }

    public static BoxDebugConfig fromLaunchRequest(JSONObject json) {
        BoxDebugConfig config = new BoxDebugConfig();
        if (json == null) return config;
        config.profile = normalizeProfile(json.optString("debugProfile", ""));
        config.focus = safe(json.optString("debugFocus", ""));
        config.durationSeconds = Math.max(0, json.optInt("debugDurationSeconds", 0));
        config.source = safe(json.optString("debugSource", "http-launch"));

        JSONObject overrides = json.optJSONObject("debugEnvOverrides");
        if (overrides != null) {
            JSONArray names = overrides.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.optString(i, "");
                    if (!name.isEmpty()) config.envOverrides.put(name, overrides.optString(name, ""));
                }
            }
        }
        return config;
    }

    public static BoxDebugConfig fromIntent(Intent intent) {
        BoxDebugConfig config = new BoxDebugConfig();
        if (intent == null) return config;
        config.profile = normalizeProfile(intent.getStringExtra(EXTRA_PROFILE));
        config.focus = safe(intent.getStringExtra(EXTRA_FOCUS));
        config.durationSeconds = Math.max(0, intent.getIntExtra(EXTRA_DURATION, 0));
        config.source = safe(intent.getStringExtra(EXTRA_SOURCE));

        String raw = safe(intent.getStringExtra(EXTRA_ENV_OVERRIDES));
        if (!raw.isEmpty()) {
            try {
                JSONObject json = new JSONObject(raw);
                JSONArray names = json.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String name = names.optString(i, "");
                        if (!name.isEmpty()) config.envOverrides.put(name, json.optString(name, ""));
                    }
                }
            }
            catch (JSONException ignored) {}
        }
        return config;
    }

    public void applyToIntent(Intent intent) {
        if (intent == null || !isEnabled()) return;
        intent.putExtra(EXTRA_PROFILE, profile);
        intent.putExtra(EXTRA_FOCUS, focus);
        intent.putExtra(EXTRA_DURATION, durationSeconds);
        intent.putExtra(EXTRA_SOURCE, source);
        intent.putExtra(EXTRA_ENV_OVERRIDES, new JSONObject(envOverrides).toString());
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("debugProfile", profile);
            json.put("debugFocus", focus);
            json.put("debugDurationSeconds", durationSeconds);
            json.put("debugSource", source);
            json.put("debugEnvOverrides", new JSONObject(envOverrides));
        }
        catch (JSONException ignored) {}
        return json;
    }

    public String buildWineDebug() {
        return WineDebugConfig.buildWineDebug(
                profile,
                WineDebugConfig.parseChannels(WineDebugConfig.channelsForProfile(profile)));
    }

    private static String normalizeProfile(String profile) {
        return WineDebugConfig.normalizeRequestedProfile(profile);
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
