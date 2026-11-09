package com.winlator.box;

import org.junit.Test;
import static org.junit.Assert.*;

public class BoxRedMagicGpuPolicyTest {
    private static BoxSpec fresh() {
        BoxSpec spec = new BoxSpec();
        spec.boxId = "frontmage-bnet-arm64";
        spec.graphics.turnipVersion = "26.1.5-frontmage-ir3-nonstrict";
        BoxSpec.Layer gpu = new BoxSpec.Layer();
        gpu.type = "gpu-driver-stack";
        gpu.source = "asset://graphics_driver/adrenotools-turnip26.1.5-frontmage-ir3-nonstrict-ebad131-abc97b08.tzst";
        gpu.archive = "tzst";
        gpu.checksum = "abc97b08fb3de2d54655e3493b34ff5a5a4f061878eeca88d657103581803c5a";
        gpu.target = "turnip26.1.5-frontmage-ir3-nonstrict-ebad131-abc97b08";
        gpu.replacePolicy = "preserve";
        spec.layers.add(gpu);
        BoxSpec.TurnipCandidate v39 = new BoxSpec.TurnipCandidate();
        v39.version = "stevenmxz-gen8-v39";
        v39.source = "asset://graphics_driver/adrenotools-stevenmxz-turnip-gen8-v39-ad2b2d54.tzst";
        v39.checksum = "ad2b2d546e77061d68b35164a0dd092ae5bb72d71b931e5a053eae9053ccdf4e";
        v39.driverId = "stevenmxz-turnip-gen8-v39-ad2b2d54";
        spec.graphics.turnipCandidates.add(v39);
        return spec;
    }

    @Test public void matchingFreshDeviceSelectsExactImmutableCandidate() {
        BoxSpec spec = fresh();
        assertTrue(BoxRedMagicGpuPolicy.applyFreshInstall(spec, true, "com.winlator.llm"));
        assertEquals("stevenmxz-gen8-v39", spec.graphics.turnipVersion);
        assertEquals("ad2b2d546e77061d68b35164a0dd092ae5bb72d71b931e5a053eae9053ccdf4e", spec.layers.get(0).checksum);
        assertEquals("stevenmxz-turnip-gen8-v39-ad2b2d54", spec.layers.get(0).target);
        assertFalse(BoxRedMagicGpuPolicy.applyFreshInstall(spec, true, "com.winlator.llm"));
    }

    @Test public void otherDeviceAndCustomDriverRemainUnchanged() {
        BoxSpec other = fresh();
        assertFalse(BoxRedMagicGpuPolicy.applyFreshInstall(other, false, "com.winlator.llm"));
        assertEquals("26.1.5-frontmage-ir3-nonstrict", other.graphics.turnipVersion);
        BoxSpec custom = fresh();
        custom.layers.get(0).checksum = "user-custom";
        assertFalse(BoxRedMagicGpuPolicy.applyFreshInstall(custom, true, "com.winlator.llm"));
        assertEquals("user-custom", custom.layers.get(0).checksum);
    }

    @Test public void corruptCandidateFailsBeforeChangingFreshSpec() {
        BoxSpec spec = fresh();
        spec.graphics.turnipCandidates.get(0).checksum = "unexpected";
        try { BoxRedMagicGpuPolicy.applyFreshInstall(spec, true, "com.winlator.llm"); fail("expected rejection"); }
        catch (IllegalStateException expected) { assertEquals("invalid_redmagic_gpu_candidate", expected.getMessage()); }
        assertEquals("26.1.5-frontmage-ir3-nonstrict", spec.graphics.turnipVersion);
        assertEquals("abc97b08fb3de2d54655e3493b34ff5a5a4f061878eeca88d657103581803c5a", spec.layers.get(0).checksum);
    }

    @Test public void duplicateGpuLayersAreRejected() {
        BoxSpec spec = fresh();
        BoxSpec.Layer duplicate = new BoxSpec.Layer();
        duplicate.type = "gpu-driver-stack";
        spec.layers.add(duplicate);
        try { BoxRedMagicGpuPolicy.applyFreshInstall(spec, true, "com.winlator.llm"); fail("expected rejection"); }
        catch (IllegalStateException expected) { assertEquals("ambiguous_gpu_driver_stack", expected.getMessage()); }
    }

    @Test public void nonstandardPackageDoesNotSwitchDriver() {
        BoxSpec spec = fresh();
        assertFalse(BoxRedMagicGpuPolicy.applyFreshInstall(spec, true, "com.blizzard.diablo.immortal"));
        assertEquals("26.1.5-frontmage-ir3-nonstrict", spec.graphics.turnipVersion);
    }
}
