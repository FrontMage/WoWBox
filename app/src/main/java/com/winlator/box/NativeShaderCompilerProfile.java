package com.winlator.box;

/** Shader compiler policy belongs only to the explicit native WoW profile. */
public final class NativeShaderCompilerProfile {
    public static final String CAPABILITY = "wine-builtin-shader-compiler";
    public static final String FILENAME = "d3dcompiler_47.dll";

    private NativeShaderCompilerProfile() {}

    public static BoxSpec.Layer find(BoxSpec spec) {
        if (spec != null) {
            for (BoxSpec.Layer layer : spec.layers) {
                if (ManagedDxProfile.NATIVE_LAYER_TYPE.equals(layer.type) &&
                        layer.hasCapability(CAPABILITY)) return layer;
            }
        }
        return null;
    }

    public static String winComponents(BoxSpec spec, String current) {
        if (find(spec) == null) return current;
        String[] values = current.split(",", -1);
        for (int i = 0; i < values.length; i++) {
            if (values[i].startsWith("direct3d=")) {
                values[i] = "direct3d=0";
                return String.join(",", values);
            }
        }
        throw new IllegalArgumentException("Native profile has no Direct3D component setting");
    }

    public static String runtimeIdentifier(BoxSpec spec, BoxSpec.Layer nativeLayer) {
        if (nativeLayer == null || !nativeLayer.hasCapability(CAPABILITY)) {
            throw new IllegalArgumentException("Native shader compiler capability is missing");
        }
        if (!nativeLayer.wineBuiltinCompilerSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Native shader compiler SHA-256 is missing");
        }
        for (BoxSpec.Layer layer : spec.layers) {
            if (!"wine-runtime".equals(layer.type)) continue;
            if (layer.runtimeIdentifier.isEmpty() ||
                    !layer.runtimeIdentifier.equals(nativeLayer.runtimeIdentifier)) {
                throw new IllegalArgumentException("Native shader compiler runtime does not match Wine");
            }
            return layer.runtimeIdentifier;
        }
        throw new IllegalArgumentException("Native shader compiler Wine runtime is missing");
    }

    public static boolean isOwnedNativeOverride(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(java.util.Locale.US).replace(" ", "");
        return "native,builtin".equals(normalized) || "n,b".equals(normalized) ||
                "native".equals(normalized) || "n".equals(normalized);
    }
}
