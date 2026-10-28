package com.winlator.box;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import org.junit.Test;

public class NativeSystemDxProfileTest {
    private BoxSpec spec(boolean system32) {
        BoxSpec spec = new BoxSpec();
        BoxSpec.Layer nativeDx = new BoxSpec.Layer();
        nativeDx.type = ManagedDxProfile.NATIVE_LAYER_TYPE;
        if (system32) nativeDx.capabilities.add(NativeSystemDxProfile.CAPABILITY);
        spec.layers.add(nativeDx);
        return spec;
    }

    @Test
    public void relinquishesOnlyTheTwoSystem32Files() {
        BoxSpec spec = spec(true);
        assertTrue(NativeSystemDxProfile.nativeOwnsLegacyEntry(spec, "system32/dxgi.dll"));
        assertTrue(NativeSystemDxProfile.nativeOwnsLegacyEntry(spec, "./system32/d3d11.dll"));
        assertFalse(NativeSystemDxProfile.nativeOwnsLegacyEntry(spec, "syswow64/dxgi.dll"));
        assertFalse(NativeSystemDxProfile.nativeOwnsLegacyEntry(spec, "system32/d3d9.dll"));
    }

    @Test
    public void ordinaryAndPathOnlyProfilesRetainAllLegacyFiles() {
        assertFalse(NativeSystemDxProfile.nativeOwnsLegacyEntry(new BoxSpec(), "system32/dxgi.dll"));
        assertFalse(NativeSystemDxProfile.nativeOwnsLegacyEntry(spec(false), "system32/dxgi.dll"));
    }

    private LinkedHashMap<String, String> remaining() {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) files.put("retained-" + i, "hash-" + i);
        return files;
    }

    @Test
    public void permitsOwnershipTransferWhenEveryRemainingByteHashIsUnchanged() {
        LinkedHashMap<String, String> baseline = remaining();
        baseline.put("system32/dxgi.dll", "old-x64");
        baseline.put("system32/d3d11.dll", "old-x64");
        NativeSystemDxProfile.requireUnchangedRemaining(baseline, remaining());
    }

    @Test(expected = IllegalStateException.class)
    public void refusesToAdoptAnUnrelatedRemainingFileChange() {
        LinkedHashMap<String, String> changed = remaining();
        changed.put("retained-4", "tampered");
        NativeSystemDxProfile.requireUnchangedRemaining(remaining(), changed);
    }
}
