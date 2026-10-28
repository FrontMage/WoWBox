package com.winlator.box;

import java.util.Map;

/** System32 DXGI/D3D11 ownership for the separate ARM64-only game profile. */
public final class NativeSystemDxProfile {
    public static final String CAPABILITY = "native-system32-dx";

    private NativeSystemDxProfile() {}

    public static boolean enabled(BoxSpec spec) {
        if (spec != null) {
            for (BoxSpec.Layer layer : spec.layers) {
                if (ManagedDxProfile.NATIVE_LAYER_TYPE.equals(layer.type) &&
                        layer.hasCapability(CAPABILITY)) return true;
            }
        }
        return false;
    }

    public static boolean nativeOwnsLegacyEntry(BoxSpec spec, String relative) {
        if (!enabled(spec)) return false;
        String path = relative.replace('\\', '/');
        if (path.startsWith("./")) path = path.substring(2);
        return "system32/dxgi.dll".equalsIgnoreCase(path) ||
                "system32/d3d11.dll".equalsIgnoreCase(path);
    }

    public static void requireUnchangedRemaining(Map<String, String> baseline,
                                                 Map<String, String> remaining) {
        if (baseline == null || remaining == null || remaining.size() != 10) {
            throw new IllegalStateException("Legacy DX remaining hash baseline is incomplete");
        }
        for (Map.Entry<String, String> entry : remaining.entrySet()) {
            String expected = baseline.get(entry.getKey());
            if (expected == null || entry.getValue() == null || entry.getValue().isEmpty() ||
                    !entry.getValue().equalsIgnoreCase(expected)) {
                throw new IllegalStateException("Legacy DX remaining file changed: " + entry.getKey());
            }
        }
    }
}
