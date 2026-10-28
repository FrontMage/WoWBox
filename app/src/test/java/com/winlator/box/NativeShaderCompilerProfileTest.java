package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeShaderCompilerProfileTest {
    private BoxSpec spec() {
        BoxSpec spec = new BoxSpec();
        BoxSpec.Layer wine = new BoxSpec.Layer();
        wine.type = "wine-runtime";
        wine.runtimeIdentifier = "proton-11.0-1-arm64ec";
        spec.layers.add(wine);
        BoxSpec.Layer nativeDx = new BoxSpec.Layer();
        nativeDx.type = ManagedDxProfile.NATIVE_LAYER_TYPE;
        nativeDx.capabilities.add(NativeShaderCompilerProfile.CAPABILITY);
        nativeDx.runtimeIdentifier = wine.runtimeIdentifier;
        nativeDx.wineBuiltinCompilerSha256 =
                "75bada9895553f133adc51a71461d20947789181550505935ca5fc2f894f637c";
        spec.layers.add(nativeDx);
        return spec;
    }

    @Test
    public void ordinaryProfileRetainsNativeDirect3dDefault() {
        assertEquals("direct3d=1,vcrun2010=1",
                NativeShaderCompilerProfile.winComponents(new BoxSpec(), "direct3d=1,vcrun2010=1"));
    }

    @Test
    public void explicitNativePolicyChangesOnlyDirect3d() {
        assertEquals("direct3d=0,vcrun2010=1,wmdecoder=1",
                NativeShaderCompilerProfile.winComponents(spec(),
                        "direct3d=1,vcrun2010=1,wmdecoder=1"));
    }

    @Test
    public void compilerUsesTheSelectedRuntimeIdentity() {
        BoxSpec spec = spec();
        assertEquals("proton-11.0-1-arm64ec",
                NativeShaderCompilerProfile.runtimeIdentifier(spec, NativeShaderCompilerProfile.find(spec)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsACompilerFromAnotherRuntime() {
        BoxSpec spec = spec();
        NativeShaderCompilerProfile.find(spec).runtimeIdentifier = "other";
        NativeShaderCompilerProfile.runtimeIdentifier(spec, NativeShaderCompilerProfile.find(spec));
    }

    @Test
    public void removesOnlyKnownNativeOverrideForms() {
        assertTrue(NativeShaderCompilerProfile.isOwnedNativeOverride("native,builtin"));
        assertTrue(NativeShaderCompilerProfile.isOwnedNativeOverride("n,b"));
        assertFalse(NativeShaderCompilerProfile.isOwnedNativeOverride("builtin,native"));
        assertFalse(NativeShaderCompilerProfile.isOwnedNativeOverride("disabled"));
    }

    @Test
    public void compilerOwnedPolicyDoesNotReconcileTheOther48Direct3dOverrides() {
        assertTrue(com.winlator.core.WineUtils.skipsComponentOverride("direct3d", true));
        assertFalse(com.winlator.core.WineUtils.skipsComponentOverride("vcrun2010", true));
        assertFalse(com.winlator.core.WineUtils.skipsComponentOverride("direct3d", false));
    }
}
