package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.winlator.core.EnvVars;

import org.junit.Test;

public class BoxGraphicsCacheDefaultsTest {
    private static BoxRuntime.EnvSnapshot ordinaryDefaults() {
        BoxRuntime.EnvSnapshot snapshot = new BoxRuntime.EnvSnapshot();
        BoxGraphicsCacheDefaults.addTo(snapshot, "frontmage-bnet-arm64", false, false, false);
        return snapshot;
    }

    @Test
    public void oldSpecWithoutKeysGetsDefaults() {
        BoxRuntime.EnvSnapshot snapshot = ordinaryDefaults();
        assertEquals("d3d11.cachedDynamicResources = vic", snapshot.values.get("DXVK_CONFIG"));
        assertEquals("force_host_cached", snapshot.values.get("VKD3D_CONFIG"));
        assertEquals("graphics-default", snapshot.sources.get("DXVK_CONFIG"));
    }

    @Test
    public void explicitBaseAndSpecOverridesIncludingEmptyArePreserved() {
        BoxRuntime.EnvSnapshot snapshot = ordinaryDefaults();
        snapshot.add("base", "DXVK_CONFIG", "");
        snapshot.add("overrides", "VKD3D_CONFIG", "custom");
        assertEquals("", snapshot.values.get("DXVK_CONFIG"));
        assertEquals("base", snapshot.sources.get("DXVK_CONFIG"));
        assertEquals("custom", snapshot.values.get("VKD3D_CONFIG"));
        assertEquals("overrides", snapshot.sources.get("VKD3D_CONFIG"));
    }

    @Test
    public void runtimeOverrideWinsAndSpacesSurviveContainerRoundTrip() {
        BoxRuntime.EnvSnapshot snapshot = ordinaryDefaults();
        snapshot.add("base", "VKD3D_CONFIG", "base");
        snapshot.add("overrides", "VKD3D_CONFIG", "override");
        snapshot.add("runtime", "VKD3D_CONFIG", "runtime");
        EnvVars env = new EnvVars();
        for (String key : snapshot.values.keySet()) env.put(key, snapshot.values.get(key));
        EnvVars restored = new EnvVars(env.toString());
        assertEquals("d3d11.cachedDynamicResources = vic", restored.get("DXVK_CONFIG"));
        assertEquals("runtime", restored.get("VKD3D_CONFIG"));
        assertEquals("runtime", snapshot.sources.get("VKD3D_CONFIG"));
    }

    @Test
    public void doesNotApplyToOtherProfiles() {
        BoxRuntime.EnvSnapshot snapshot = new BoxRuntime.EnvSnapshot();
        BoxGraphicsCacheDefaults.addTo(snapshot, "other-box", false, false, false);
        BoxGraphicsCacheDefaults.addTo(snapshot, "frontmage-bnet-arm64", true, false, false);
        BoxGraphicsCacheDefaults.addTo(snapshot, "frontmage-bnet-arm64", false, true, false);
        BoxGraphicsCacheDefaults.addTo(snapshot, "frontmage-bnet-arm64", false, false, true);
        assertFalse(snapshot.values.containsKey("DXVK_CONFIG"));
        assertFalse(snapshot.values.containsKey("VKD3D_CONFIG"));
    }
}
