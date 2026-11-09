package com.winlator.box;

import com.winlator.core.EnvVars;
import java.util.Map;

/** Preserve explicit driver environment exactly, including missing and empty values. */
public final class GpuExplicitEnvironmentPolicy {
    public static final String[] KEYS = {"ADRENOTOOLS_DRIVER_PATH", "ADRENOTOOLS_DRIVER_NAME",
            "VK_ICD_FILENAMES", "VK_DRIVER_FILES", "VK_ADD_DRIVER_FILES"};
    private GpuExplicitEnvironmentPolicy() {}
    public static boolean shouldDefer(boolean boxSession, boolean arm64ec) { return boxSession && arm64ec; }
    private static boolean has(Map<String, String> user, EnvVars launch, String key) {
        return (user != null && user.containsKey(key)) || (launch != null && launch.has(key));
    }
    /** VK_ADD_DRIVER_FILES is additive: retain the user's original default driver. */
    public static boolean onlyAdditiveIcd(Map<String, String> user, EnvVars launch) {
        return has(user, launch, "VK_ADD_DRIVER_FILES") && !has(user, launch, "VK_ICD_FILENAMES") &&
                !has(user, launch, "VK_DRIVER_FILES") && !has(user, launch, "ADRENOTOOLS_DRIVER_PATH") &&
                !has(user, launch, "ADRENOTOOLS_DRIVER_NAME");
    }
    public static boolean apply(Map<String, String> user, EnvVars launch, EnvVars merged) {
        boolean explicit = false;
        for (String key : KEYS) {
            if ((user != null && user.containsKey(key)) || (launch != null && launch.has(key))) explicit = true;
        }
        if (!explicit) return false;
        for (String key : new String[]{"ADRENOTOOLS_DRIVER_PATH", "ADRENOTOOLS_DRIVER_NAME"}) {
            merged.remove(key);
            // Launch env is the explicit container/shortcut/runtime/debug merge.
            if (launch != null && launch.has(key)) merged.put(key, launch.get(key));
            else if (user != null && user.containsKey(key)) merged.put(key, user.get(key) == null ? "" : user.get(key));
        }
        String[] icdKeys = {"VK_ICD_FILENAMES", "VK_DRIVER_FILES", "VK_ADD_DRIVER_FILES"};
        boolean replaceIcd = has(user,launch,"VK_ICD_FILENAMES") || has(user,launch,"VK_DRIVER_FILES");
        if (replaceIcd) for (String key : icdKeys) {
            // Avoid mixing a generated legacy ICD with an explicit modern one.
            merged.remove(key);
            if (launch != null && launch.has(key)) merged.put(key, launch.get(key));
            else if (user != null && user.containsKey(key)) merged.put(key, user.get(key) == null ? "" : user.get(key));
        }
        if (!replaceIcd && has(user,launch,"VK_ADD_DRIVER_FILES")) {
            merged.put("VK_ADD_DRIVER_FILES", launch != null && launch.has("VK_ADD_DRIVER_FILES") ?
                    launch.get("VK_ADD_DRIVER_FILES") : user.get("VK_ADD_DRIVER_FILES"));
        }
        return true;
    }
}
