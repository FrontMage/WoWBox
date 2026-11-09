package com.winlator.box;

/** Selects the verified RedMagic driver only when constructing a fresh Box spec. */
final class BoxRedMagicGpuPolicy {
    private static final String RELEASE_BOX_ID = "frontmage-bnet-arm64";
    private static final String DEFAULT_VERSION = "26.1.5-frontmage-ir3-nonstrict";
    private static final String DEFAULT_SOURCE = "asset://graphics_driver/adrenotools-turnip26.1.5-frontmage-ir3-nonstrict-ebad131-abc97b08.tzst";
    private static final String DEFAULT_CHECKSUM = "abc97b08fb3de2d54655e3493b34ff5a5a4f061878eeca88d657103581803c5a";
    private static final String DEFAULT_TARGET = "turnip26.1.5-frontmage-ir3-nonstrict-ebad131-abc97b08";
    private static final String REDMAGIC_VERSION = "stevenmxz-gen8-v39";
    private static final String REDMAGIC_SOURCE = "asset://graphics_driver/adrenotools-stevenmxz-turnip-gen8-v39-ad2b2d54.tzst";
    private static final String REDMAGIC_CHECKSUM = "ad2b2d546e77061d68b35164a0dd092ae5bb72d71b931e5a053eae9053ccdf4e";
    private static final String REDMAGIC_TARGET = "stevenmxz-turnip-gen8-v39-ad2b2d54";

    private BoxRedMagicGpuPolicy() {}

    /** Called only for a new spec before it is first persisted; never migrate existing specs. */
    static boolean applyFreshInstall(BoxSpec spec, boolean targetDevice, String applicationId) {
        if (!targetDevice || !"com.winlator.llm".equals(applicationId) || spec == null || !RELEASE_BOX_ID.equals(spec.boxId) ||
                spec.graphics == null || !DEFAULT_VERSION.equals(spec.graphics.turnipVersion)) return false;
        BoxSpec.Layer gpu = null;
        for (BoxSpec.Layer layer : spec.layers) {
            if (!"gpu-driver-stack".equals(layer.type)) continue;
            if (gpu != null) throw new IllegalStateException("ambiguous_gpu_driver_stack");
            gpu = layer;
        }
        if (gpu == null || !DEFAULT_SOURCE.equals(gpu.source) ||
                !DEFAULT_CHECKSUM.equals(gpu.checksum) || !DEFAULT_TARGET.equals(gpu.target) ||
                !"tzst".equals(gpu.archive) || !"preserve".equals(gpu.replacePolicy)) return false;
        BoxSpec.TurnipCandidate candidate = null;
        for (BoxSpec.TurnipCandidate item : spec.graphics.turnipCandidates) {
            if (!REDMAGIC_VERSION.equals(item.version)) continue;
            if (candidate != null) throw new IllegalStateException("ambiguous_redmagic_gpu_candidate");
            candidate = item;
        }
        if (candidate == null || !REDMAGIC_SOURCE.equals(candidate.source) ||
                !REDMAGIC_CHECKSUM.equals(candidate.checksum) || !REDMAGIC_TARGET.equals(candidate.driverId)) {
            throw new IllegalStateException("invalid_redmagic_gpu_candidate");
        }
        gpu.source = candidate.source;
        gpu.checksum = candidate.checksum;
        gpu.target = candidate.driverId;
        spec.graphics.turnipVersion = candidate.version;
        return true;
    }
}
