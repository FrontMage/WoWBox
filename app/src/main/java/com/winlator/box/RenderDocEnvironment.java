package com.winlator.box;

import com.winlator.core.EnvVars;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Final launch policy. Call only after all container and debug environment merges. */
public final class RenderDocEnvironment {
    public static final String LAYER = "VK_LAYER_RENDERDOC_Capture";
    public static final String VALIDATED_WINE_ARCHIVE =
            "813c6222e062d410d76491f00eb68c135f0ff02074ad1adf2a599e297fa3bbf6";
    public static final String VALIDATED_UNIX_NTDLL =
            "9766b6132af7b4a8c8fe346ee4202b5d0ae2d47fa0cc834db12c1d5ef2efccbe";

    private RenderDocEnvironment() {}

    public static boolean isValidatedRuntime(boolean uu, boolean box, boolean arm64ec,
            String archive, String unixNtdll) {
        return uu && box && arm64ec && VALIDATED_WINE_ARCHIVE.equals(archive)
                && VALIDATED_UNIX_NTDLL.equals(unixNtdll);
    }

    public static void apply(EnvVars env, boolean enabled, String layerDirectory,
            String captureRoot, String appFilesDirectory) {
        ArrayList<String> layers = new ArrayList<>();
        for (String layer : env.get("VK_INSTANCE_LAYERS").split(":")) {
            if (!layer.isEmpty() && !LAYER.equals(layer)) layers.add(layer);
        }
        if (enabled) layers.add(LAYER);
        env.put("VK_INSTANCE_LAYERS", String.join(":", layers));
        env.put("ENABLE_VULKAN_RENDERDOC_CAPTURE", enabled ? "1" : "0");
        // Explicit empty values suppress Android property fallback in the managed layer.
        if (enabled) {
            env.remove("DISABLE_VULKAN_RENDERDOC_CAPTURE");
            env.remove("DISABLE_VULKAN_RENDERDOC_CAPTURE_1_44");
        }
        else {
            env.put("DISABLE_VULKAN_RENDERDOC_CAPTURE", "1");
            env.put("DISABLE_VULKAN_RENDERDOC_CAPTURE_1_44", "1");
        }
        env.put("RENDERDOC_CAPFILE", "");
        env.put("RENDERDOC_TEMP", enabled ? captureRoot : "");
        env.put("RENDERDOC_DEBUG_LOG_FILE", "");
        env.put("RENDERDOC_CAPOPTS", "");
        if (enabled) env.put("RENDERDOC_DISABLE_ANDROID_KEEPALIVE", "1");
        else env.remove("RENDERDOC_DISABLE_ANDROID_KEEPALIVE");

        LinkedHashSet<String> directories = new LinkedHashSet<>();
        for (String directory : env.get("VK_LAYER_PATH").split(":")) {
            if (!directory.isEmpty() && !isOwnedLayerDirectory(directory, appFilesDirectory)) directories.add(directory);
        }
        if (enabled) directories.add(layerDirectory);
        env.put("VK_LAYER_PATH", String.join(":", directories));
    }

    static boolean isOwnedLayerDirectory(String value, String appFilesDirectory) {
        if (appFilesDirectory == null || appFilesDirectory.isEmpty()) return false;
        String prefix = appFilesDirectory + "/diagnostics/";
        if (value.startsWith(prefix + "renderdoc-standard-layer-")
                || value.startsWith(prefix + "renderdoc/")) return true;
        // Android's user-0 /data/data alias names the same private application directory.
        if (appFilesDirectory.startsWith("/data/user/0/")) {
            String alias = "/data/data/" + appFilesDirectory.substring("/data/user/0/".length())
                    + "/diagnostics/";
            return value.startsWith(alias + "renderdoc-standard-layer-")
                    || value.startsWith(alias + "renderdoc/");
        }
        return false;
    }
}
