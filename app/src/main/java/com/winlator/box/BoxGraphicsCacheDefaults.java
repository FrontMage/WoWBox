package com.winlator.box;

/** Effective graphics defaults for ordinary WoW Box, including older specs. */
final class BoxGraphicsCacheDefaults {
    static final String DXVK_CONFIG = "d3d11.cachedDynamicResources = vic";
    static final String VKD3D_CONFIG = "force_host_cached";

    private BoxGraphicsCacheDefaults() {}

    static void addTo(BoxRuntime.EnvSnapshot snapshot, String boxId,
                      boolean uuProbe, boolean nativeArm, boolean agentDriveProbe) {
        if (!"frontmage-bnet-arm64".equals(boxId) || uuProbe || nativeArm || agentDriveProbe) {
            return;
        }
        snapshot.add("graphics-default", "DXVK_CONFIG", DXVK_CONFIG);
        snapshot.add("graphics-default", "VKD3D_CONFIG", VKD3D_CONFIG);
    }
}
