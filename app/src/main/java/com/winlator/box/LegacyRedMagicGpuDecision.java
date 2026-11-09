package com.winlator.box;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure decision: never rewrite a persisted spec or user-selected driver. */
final class LegacyRedMagicGpuDecision {
    static final String OLD_VERSION = "26.1.5-frontmage-ir3-nonstrict";
    static final String OLD_SOURCE = "asset://graphics_driver/adrenotools-turnip26.1.5-frontmage-ir3-nonstrict-ebad131-abc97b08.tzst";
    static final String OLD_SHA = "abc97b08fb3de2d54655e3493b34ff5a5a4f061878eeca88d657103581803c5a";
    static final String OLD_ID = "turnip26.1.5-frontmage-ir3-nonstrict-ebad131-abc97b08";
    static final String NEW_ID = "stevenmxz-turnip-gen8-v39-ad2b2d54";
    static final String NEW_ASSET = "graphics_driver/adrenotools-stevenmxz-turnip-gen8-v39-ad2b2d54.tzst";
    static final String NEW_ARCHIVE_SHA = "ad2b2d546e77061d68b35164a0dd092ae5bb72d71b931e5a053eae9053ccdf4e";
    static final String NEW_META_SHA = "d070460871e35467a83a34f1cd716963ca37f3dea38a3ea088f67ef2ca7ce7b5";
    static final String NEW_LIB_SHA = "398b838b5d0b82b46ba2a209f8c2bd47bf3245c1059d5eed950b1db342b5cb39";
    static final String NEW_LIB = "libvulkan_freedreno.so";
    static final String WINE_SOURCE = "asset://wine-runtime-wowbox-mainline-openfonts-56f206c4.txz";
    static final String WINE_SHA = "56f206c4eb1da0d297ab6e73041e000793b05d285b7edf96e9e84914babe9de1";
    static final String WINE_ID = "proton-11.0-1-arm64ec-wowbox-openfonts-9ac94fff";
    private static final Set<String> RELEASE_LAYER_TYPES = new HashSet<>(Arrays.asList(
            "base-imagefs", "graphics-wrapper-stack", "frame-generation-stack", "prefix-template",
            "wine-runtime", "input-bridge-stack", "dx-wrapper-stack", "gpu-driver-stack", "cpu-emu-stack"));

    private LegacyRedMagicGpuDecision() {}

    static boolean applies(BoxSpec spec, JSONObject state, Map<String, String> env,
                           boolean target, String packageName) {
        if (!target || !"com.winlator.llm".equals(packageName) || spec == null ||
                !"frontmage-bnet-arm64".equals(spec.boxId) || spec.graphics == null ||
                !OLD_VERSION.equals(spec.graphics.turnipVersion) || state == null ||
                !state.optBoolean("installed", false)) return false;
        for (String key : new String[]{"selectedTurnipVersion", "selectedTurnipSource", "selectedTurnipAt"}) {
            if (state.has(key)) return false; // even an explicit choice of the old version wins
        }
        if (env != null) for (String key : new String[]{"ADRENOTOOLS_DRIVER_PATH", "ADRENOTOOLS_DRIVER_NAME",
                "VK_ICD_FILENAMES", "VK_DRIVER_FILES", "VK_ADD_DRIVER_FILES"}) {
            if (env.containsKey(key)) return false;
        }
        if (!hasExactReleaseLayerShape(spec, state)) return false;
        BoxSpec.Layer gpu = null, wine = null;
        for (BoxSpec.Layer layer : spec.layers) {
            if ("gpu-driver-stack".equals(layer.type)) {
                if (gpu != null) return false;
                gpu = layer;
            } else if ("wine-runtime".equals(layer.type)) {
                if (wine != null) return false;
                wine = layer;
            }
        }
        // Late GPU selection is proven only for the shipped ARM64EC contract.
        if (wine == null || !WINE_SOURCE.equals(wine.source) || !WINE_SHA.equals(wine.checksum) ||
                !WINE_ID.equals(wine.runtimeIdentifier) || !("/opt/" + WINE_ID).equals(wine.target)) return false;
        if (gpu == null || !OLD_SOURCE.equals(gpu.source) || !OLD_SHA.equals(gpu.checksum) ||
                !OLD_ID.equals(gpu.target) || !"tzst".equals(gpu.archive) ||
                !"preserve".equals(gpu.replacePolicy)) return false;
        return true;
    }

    /** Additional, missing or duplicate layers are a user configuration, not a default. */
    private static boolean hasExactReleaseLayerShape(BoxSpec spec, JSONObject state) {
        if (spec.layers.size() != RELEASE_LAYER_TYPES.size()) return false;
        HashMap<String, BoxSpec.Layer> byType = new HashMap<>();
        for (BoxSpec.Layer layer : spec.layers) {
            if (layer == null || !RELEASE_LAYER_TYPES.contains(layer.type) || byType.put(layer.type, layer) != null) return false;
        }
        JSONArray installed = state.optJSONArray("installedLayers");
        if (installed == null || installed.length() != RELEASE_LAYER_TYPES.size()) return false;
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < installed.length(); i++) {
            JSONObject row = installed.optJSONObject(i);
            if (row == null) return false;
            String type = row.optString("type", "");
            BoxSpec.Layer expected = byType.get(type);
            if (expected == null || !seen.add(type) || !row.has("installedAt") || row.isNull("installedAt") ||
                    !BoxDebugServer.installedLayerIdentityMatches(expected, row)) return false;
        }
        return seen.size() == RELEASE_LAYER_TYPES.size();
    }
}
