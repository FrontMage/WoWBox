package com.winlator.box;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for the Wine debug profile shown in Settings and used
 * by Box launches.
 */
public final class WineDebugConfig {
    public static final String PREF_ENABLED = "enable_wine_debug";
    public static final String PREF_PROFILE = "wine_debug_profile";
    public static final String PREF_LAST_ENABLED_PROFILE = "wine_debug_last_enabled_profile";
    public static final String PREF_CHANNELS = "wine_debug_channels";
    public static final String PREF_CUSTOM_CHANNELS = "wine_debug_custom_channels";

    public static final String PROFILE_DISABLED = "disabled";
    public static final String PROFILE_BASELINE = "baseline";
    public static final String PROFILE_INIT_STATE = "init-state";
    public static final String PROFILE_DEEP_FOCUS = "deep-focus";
    public static final String PROFILE_CUSTOM = "custom";

    public static final String DEFAULT_CUSTOM_CHANNELS = "warn,err,fixme";
    public static final String BASELINE_CHANNELS = "seh,unwind,vulkan";
    public static final String INIT_STATE_CHANNELS = "timestamp,pid,tid,relay,seh,unwind,vulkan";
    public static final String DEEP_FOCUS_CHANNELS = INIT_STATE_CHANNELS;

    private static final String RELAY_EXCLUDES =
            ",-relay=ntdll.RtlEnterCriticalSection" +
            ",-relay=ntdll.RtlLeaveCriticalSection" +
            ",-relay=kernelbase.OutputDebugStringA" +
            ",-relay=kernelbase.OutputDebugStringW" +
            ",-relay=ntdll.RtlFreeHeap" +
            ",-relay=ntdll.RtlAllocateHeap" +
            ",-relay=ntdll.memmove" +
            ",-relay=ntdll.memcmp" +
            ",-relay=kernel32.HeapFree" +
            ",-relay=kernel32.CompareStringOrdinal" +
            ",-relay=kernel32.lstrcmpiW" +
            ",-relay=ucrtbase.isspace" +
            ",-relay=ucrtbase.malloc" +
            ",-relay=ucrtbase.free";

    private WineDebugConfig() {}

    public static final class State {
        public final String profile;
        public final ArrayList<String> channels;

        public State(String profile, List<String> channels) {
            this.profile = normalizeStoredProfile(profile);
            this.channels = normalizeChannels(channels);
        }

        public boolean isEnabled() {
            return !PROFILE_DISABLED.equals(profile);
        }

        public String buildWineDebug() {
            return WineDebugConfig.buildWineDebug(profile, channels);
        }
    }

    public static State fromPreferences(SharedPreferences preferences) {
        boolean enabled = preferences.getBoolean(PREF_ENABLED, false);
        String storedProfile = preferences.getString(PREF_PROFILE, "");
        String profile = normalizeStoredProfile(storedProfile);

        // Migrate the old checkbox/channel-only model without changing behavior.
        if (storedProfile == null || storedProfile.trim().isEmpty()) {
            profile = enabled ? PROFILE_CUSTOM : PROFILE_DISABLED;
        }
        if (!enabled) profile = PROFILE_DISABLED;

        String channelsValue;
        if (PROFILE_CUSTOM.equals(profile) || PROFILE_DISABLED.equals(profile)) {
            channelsValue = preferences.getString(
                    PREF_CUSTOM_CHANNELS,
                    preferences.getString(PREF_CHANNELS, DEFAULT_CUSTOM_CHANNELS));
        }
        else {
            channelsValue = channelsForProfile(profile);
        }
        return new State(profile, parseChannels(channelsValue));
    }

    public static void putPreferences(
            SharedPreferences.Editor editor,
            String profile,
            List<String> channels) {
        profile = normalizeStoredProfile(profile);
        ArrayList<String> normalizedChannels = normalizeChannels(channels);
        boolean enabled = !PROFILE_DISABLED.equals(profile);

        editor.putBoolean(PREF_ENABLED, enabled);
        editor.putString(PREF_PROFILE, profile);
        if (enabled) editor.putString(PREF_LAST_ENABLED_PROFILE, profile);

        if (PROFILE_CUSTOM.equals(profile)) {
            String joined = joinChannels(normalizedChannels);
            editor.putString(PREF_CHANNELS, joined);
            editor.putString(PREF_CUSTOM_CHANNELS, joined);
        }
        else if (enabled) {
            editor.putString(PREF_CHANNELS, channelsForProfile(profile));
        }
    }

    public static void persistRequestedProfile(
            SharedPreferences preferences,
            String requestedProfile) {
        String profile = normalizeRequestedProfile(requestedProfile);
        if (profile.isEmpty()) return;

        State current = fromPreferences(preferences);
        List<String> channels = PROFILE_CUSTOM.equals(profile)
                ? current.channels
                : parseChannels(channelsForProfile(profile));
        SharedPreferences.Editor editor = preferences.edit();
        if (!preferences.contains(PREF_CUSTOM_CHANNELS)) {
            String customChannels =
                    (PROFILE_CUSTOM.equals(current.profile)
                            || PROFILE_DISABLED.equals(current.profile))
                    ? joinChannels(current.channels)
                    : DEFAULT_CUSTOM_CHANNELS;
            editor.putString(PREF_CUSTOM_CHANNELS, customChannels);
        }
        putPreferences(editor, profile, channels);
        editor.commit();
    }

    public static String normalizeRequestedProfile(String profile) {
        String value = profile != null ? profile.trim().toLowerCase(Locale.US) : "";
        if (PROFILE_DISABLED.equals(value) || "off".equals(value)) return PROFILE_DISABLED;
        if (PROFILE_BASELINE.equals(value)) return PROFILE_BASELINE;
        if (PROFILE_INIT_STATE.equals(value)) return PROFILE_INIT_STATE;
        if (PROFILE_DEEP_FOCUS.equals(value)) return PROFILE_DEEP_FOCUS;
        if (PROFILE_CUSTOM.equals(value)) return PROFILE_CUSTOM;
        return "";
    }

    public static String normalizeStoredProfile(String profile) {
        String normalized = normalizeRequestedProfile(profile);
        return normalized.isEmpty() ? PROFILE_DISABLED : normalized;
    }

    public static int profileToPosition(String profile) {
        profile = normalizeStoredProfile(profile);
        if (PROFILE_BASELINE.equals(profile)) return 1;
        if (PROFILE_INIT_STATE.equals(profile)) return 2;
        if (PROFILE_DEEP_FOCUS.equals(profile)) return 3;
        if (PROFILE_CUSTOM.equals(profile)) return 4;
        return 0;
    }

    public static String positionToProfile(int position) {
        switch (position) {
            case 1: return PROFILE_BASELINE;
            case 2: return PROFILE_INIT_STATE;
            case 3: return PROFILE_DEEP_FOCUS;
            case 4: return PROFILE_CUSTOM;
            default: return PROFILE_DISABLED;
        }
    }

    public static String channelsForProfile(String profile) {
        profile = normalizeStoredProfile(profile);
        if (PROFILE_BASELINE.equals(profile)) return BASELINE_CHANNELS;
        if (PROFILE_INIT_STATE.equals(profile)) return INIT_STATE_CHANNELS;
        if (PROFILE_DEEP_FOCUS.equals(profile)) return DEEP_FOCUS_CHANNELS;
        return "";
    }

    public static String buildWineDebug(String profile, List<String> channels) {
        profile = normalizeStoredProfile(profile);
        if (PROFILE_DISABLED.equals(profile)) return "-all";
        if (PROFILE_BASELINE.equals(profile)) {
            return "warn+seh,warn+unwind,warn+vulkan";
        }
        if (PROFILE_INIT_STATE.equals(profile) || PROFILE_DEEP_FOCUS.equals(profile)) {
            return "+timestamp,+pid,+tid,+relay,warn+seh,warn+unwind,warn+vulkan"
                    + RELAY_EXCLUDES;
        }

        StringBuilder result = new StringBuilder();
        for (String channel : normalizeChannels(channels)) {
            appendToken(result, "+" + channel);
        }
        return result.length() > 0 ? result.toString() : "-all";
    }

    public static ArrayList<String> parseChannels(String value) {
        if (value == null || value.trim().isEmpty()) return new ArrayList<>();
        return normalizeChannels(Arrays.asList(value.split(",")));
    }

    public static ArrayList<String> normalizeChannels(List<String> channels) {
        ArrayList<String> result = new ArrayList<>();
        if (channels == null) return result;
        for (String channel : channels) {
            if (channel == null) continue;
            String token = channel.trim();
            if (token.startsWith("+")) token = token.substring(1);
            if (token.isEmpty() || result.contains(token)) continue;
            result.add(token);
        }
        return result;
    }

    public static String joinChannels(List<String> channels) {
        return String.join(",", normalizeChannels(channels));
    }

    private static void appendToken(StringBuilder builder, String token) {
        if (builder.length() > 0) builder.append(',');
        builder.append(token);
    }
}
