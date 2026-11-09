package com.winlator.box;

import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyGpuLaunchGateTest {
    private JSONObject status(boolean eligible, boolean selected) throws Exception {
        return new JSONObject().put("eligible", eligible).put("selected", selected);
    }
    private JSONObject layers(boolean ok, int count) throws Exception {
        JSONArray a = new JSONArray(); for (int i = 0; i < count; i++) a.put(new JSONObject());
        return new JSONObject().put("ok", ok).put("layers", a);
    }
    @Test public void eligibleColdOverrideAndFallbackGetTheAdditionalGate() throws Exception {
        assertTrue(LegacyGpuLaunchGate.shouldVerify(status(true, true), false, new HashMap<>()));
        assertTrue(LegacyGpuLaunchGate.shouldVerify(status(true, true), true, new HashMap<>()));
        assertFalse(LegacyGpuLaunchGate.shouldVerify(status(false, false), false, new HashMap<>()));
        assertTrue(LegacyGpuLaunchGate.shouldVerify(status(true, false), false, new HashMap<>()));
        HashMap<String,String> custom = new HashMap<>(); custom.put("VK_ICD_FILENAMES", "");
        assertFalse(LegacyGpuLaunchGate.shouldVerify(status(true, true), false, custom));
    }
    @Test public void fallbackCannotBypassInvalidOverlayEvenWithNineGoodLayers() throws Exception {
        JSONObject fallback = status(true,false).put("ready",true).put("fallbackToOriginal",true);
        assertTrue(LegacyGpuLaunchGate.shouldVerify(fallback,false,new HashMap<>()));
        try { LegacyGpuLaunchGate.requireVerified(new JSONObject().put("ok",false),layers(true,9)); fail("expected rejection"); }
        catch (IllegalStateException expected) { assertEquals("redmagic_gpu_layers_unverified",expected.getMessage()); }
        LegacyGpuLaunchGate.requireVerified(new JSONObject().put("ok",true),layers(true,9));
    }
    @Test public void initializingRunningOrShuttingDownInstancesCannotBypass() throws Exception {
        JSONObject eligible = status(true,true);
        assertTrue(LegacyGpuLaunchGate.shouldVerify(eligible, false, new HashMap<>()));
        assertTrue(LegacyGpuLaunchGate.shouldVerify(eligible, true, new HashMap<>()));
        assertTrue(LegacyGpuLaunchGate.shouldVerify(status(true,false), true, new HashMap<>()));
    }
    @Test public void orphanWineBlocksOnlyEligibleNoActivityRoute() {
        LegacyGpuLaunchGate.requireNoOrphan(false,false,true);
        LegacyGpuLaunchGate.requireNoOrphan(true,true,true);
        LegacyGpuLaunchGate.requireNoOrphan(true,false,false);
        try { LegacyGpuLaunchGate.requireNoOrphan(true,false,true); fail("expected orphan rejection"); }
        catch (IllegalStateException expected) { assertEquals("redmagic_gpu_existing_wine_active",expected.getMessage()); }
    }
    @Test public void acceptsOnlyVerifiedOverlayAndAllNineLayers() throws Exception {
        JSONObject ok = new JSONObject().put("ok", true);
        LegacyGpuLaunchGate.requireVerified(ok, layers(true,9));
        for (JSONObject[] pair : new JSONObject[][] {
                {new JSONObject().put("ok",false), layers(true,9)},
                {ok, layers(false,9)}, {ok, layers(true,8)}, {ok, layers(true,10)},
                {null, layers(true,9)}, {ok, null}}) {
            try { LegacyGpuLaunchGate.requireVerified(pair[0], pair[1]); fail("expected rejection"); }
            catch (IllegalStateException expected) { assertEquals("redmagic_gpu_layers_unverified", expected.getMessage()); }
        }
    }
}
