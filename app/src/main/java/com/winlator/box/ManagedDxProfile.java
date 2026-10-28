package com.winlator.box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A profile can make its verified DX layers the sole owner of prefix DLLs.
 * Version-only extraction at Activity startup must not overwrite those layers.
 */
public final class ManagedDxProfile {
    public static final String CAPABILITY = "managed-dx-refresh";
    public static final String NATIVE_LAYER_TYPE = "native-dx-wrapper-stack";

    private ManagedDxProfile() {}

    public static List<BoxSpec.Layer> ownedLayers(BoxSpec spec) {
        ArrayList<BoxSpec.Layer> layers = new ArrayList<>();
        if (spec == null) return layers;
        boolean owned = false;
        for (BoxSpec.Layer layer : spec.layers) {
            if (!isDxLayer(layer)) continue;
            layers.add(layer);
            owned |= layer.hasCapability(CAPABILITY);
        }
        if (!owned) layers.clear();
        layers.sort(Comparator.comparing(layer -> layer.type));
        return layers;
    }

    public static String cacheIdentity(BoxSpec spec) {
        List<BoxSpec.Layer> layers = ownedLayers(spec);
        if (layers.isEmpty()) return null;
        StringBuilder result = new StringBuilder("managed-dx-v1:system32=" +
                NativeSystemDxProfile.enabled(spec));
        for (BoxSpec.Layer layer : layers) {
            if (layer.checksum == null || !layer.checksum.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Managed DX layer has no SHA-256 identity");
            }
            result.append(';').append(layer.type).append('=')
                    .append(layer.source).append('@').append(layer.checksum)
                    .append("->").append(layer.target)
                    .append(":runtime=").append(layer.runtimeIdentifier)
                    .append(":compiler=").append(layer.wineBuiltinCompilerSha256);
        }
        return result.toString();
    }

    public static boolean hasNativeLayer(BoxSpec spec) {
        if (spec != null) {
            for (BoxSpec.Layer layer : spec.layers) {
                if (NATIVE_LAYER_TYPE.equals(layer.type)) return true;
            }
        }
        return false;
    }

    private static boolean isDxLayer(BoxSpec.Layer layer) {
        return layer != null && ("dx-wrapper-stack".equals(layer.type) ||
                NATIVE_LAYER_TYPE.equals(layer.type));
    }
}
