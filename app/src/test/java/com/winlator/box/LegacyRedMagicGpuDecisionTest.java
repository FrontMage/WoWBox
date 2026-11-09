package com.winlator.box;

import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyRedMagicGpuDecisionTest {
    private BoxSpec spec() {
        BoxSpec result = new BoxSpec(); result.boxId = "frontmage-bnet-arm64";
        result.graphics.turnipVersion = LegacyRedMagicGpuDecision.OLD_VERSION;
        BoxSpec.Layer layer = new BoxSpec.Layer(); layer.type = "gpu-driver-stack";
        layer.source = LegacyRedMagicGpuDecision.OLD_SOURCE; layer.checksum = LegacyRedMagicGpuDecision.OLD_SHA;
        layer.target = LegacyRedMagicGpuDecision.OLD_ID; layer.archive = "tzst"; layer.replacePolicy = "preserve";
        result.layers.add(layer);
        BoxSpec.Layer wine = new BoxSpec.Layer(); wine.type = "wine-runtime";
        wine.source = LegacyRedMagicGpuDecision.WINE_SOURCE; wine.checksum = LegacyRedMagicGpuDecision.WINE_SHA;
        wine.runtimeIdentifier = LegacyRedMagicGpuDecision.WINE_ID; wine.target = "/opt/" + LegacyRedMagicGpuDecision.WINE_ID;
        result.layers.add(wine);
        for (String type : new String[]{"base-imagefs","graphics-wrapper-stack","frame-generation-stack",
                "prefix-template","input-bridge-stack","dx-wrapper-stack","cpu-emu-stack"}) {
            BoxSpec.Layer other = new BoxSpec.Layer(); other.type = type; other.source = "asset://" + type; result.layers.add(other);
        }
        return result;
    }
    private JSONObject state() throws Exception {
        JSONArray rows = new JSONArray();
        for (BoxSpec.Layer layer : spec().layers) rows.put(new JSONObject().put("type",layer.type)
                .put("source",layer.source).put("checksum",layer.checksum).put("target",layer.target)
                .put("runtimeIdentifier",layer.runtimeIdentifier).put("winePrefixDeltaManifest",layer.winePrefixDeltaManifest)
                .put("winePrefixDeltaChecksum",layer.winePrefixDeltaChecksum).put("wineBuiltinCompilerSha256",layer.wineBuiltinCompilerSha256)
                .put("installedAt",1));
        return new JSONObject().put("installed", true).put("installedLayers", rows);
    }
    @Test public void onlyExactOldDefaultOnTargetIsEligible() throws Exception {
        assertTrue(LegacyRedMagicGpuDecision.applies(spec(), state(), new HashMap<>(), true, "com.winlator.llm"));
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(), state(), new HashMap<>(), false, "com.winlator.llm"));
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(), state(), new HashMap<>(), true, "com.other"));
        BoxSpec custom = spec(); custom.layers.get(0).checksum = "custom";
        assertFalse(LegacyRedMagicGpuDecision.applies(custom, state(), new HashMap<>(), true, "com.winlator.llm"));
    }
    @Test public void anyExplicitDriverSelectionOrOverrideWinsIncludingEmpty() throws Exception {
        JSONObject state = state().put("selectedTurnipVersion", LegacyRedMagicGpuDecision.OLD_VERSION);
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(), state, new HashMap<>(), true, "com.winlator.llm"));
        HashMap<String,String> env = new HashMap<>(); env.put("ADRENOTOOLS_DRIVER_NAME", "");
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(), state(), env, true, "com.winlator.llm"));
        env.clear(); env.put("VK_ICD_FILENAMES", "");
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(), state(), env, true, "com.winlator.llm"));
    }
    @Test public void mismatchedOrDuplicateStateFailsClosed() throws Exception {
        JSONObject state = state(); state.getJSONArray("installedLayers").getJSONObject(0).put("checksum", "bad");
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(), state, new HashMap<>(), true, "com.winlator.llm"));
        state = state(); state.getJSONArray("installedLayers").put(state.getJSONArray("installedLayers").getJSONObject(0));
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(), state, new HashMap<>(), true, "com.winlator.llm"));
    }
    @Test public void completedLegacyInstallBecomesEligibleWithoutChangingState() throws Exception {
        JSONObject state = state(); state.put("installed",false); String before=state.toString();
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(),state,new HashMap<>(),true,"com.winlator.llm"));
        assertEquals(before,state.toString()); state.put("installed",true); before=state.toString();
        assertTrue(LegacyRedMagicGpuDecision.applies(spec(),state,new HashMap<>(),true,"com.winlator.llm")); assertEquals(before,state.toString());
    }
    @Test public void customAndNonArm64ecWineRemainIneligible() throws Exception {
        BoxSpec custom=spec(); custom.layers.get(1).runtimeIdentifier="custom-x86_64-wine";
        assertFalse(LegacyRedMagicGpuDecision.applies(custom,state(),new HashMap<>(),true,"com.winlator.llm"));
        BoxSpec changed=spec(); changed.layers.get(1).checksum="another-wine";
        assertFalse(LegacyRedMagicGpuDecision.applies(changed,state(),new HashMap<>(),true,"com.winlator.llm"));
    }
    @Test public void exactPatchedWineKeepsEligibleButMixedIdentityFailsClosed() throws Exception {
        BoxSpec patched = spec();
        BoxSpec.Layer wine = patched.layers.get(1);
        wine.source = LegacyRedMagicGpuDecision.RWOA_WINE_SOURCE;
        wine.checksum = LegacyRedMagicGpuDecision.RWOA_WINE_SHA;
        JSONObject state = state();
        JSONObject row = state.getJSONArray("installedLayers").getJSONObject(1);
        row.put("source", wine.source).put("checksum", wine.checksum);
        assertTrue(LegacyRedMagicGpuDecision.applies(patched, state, new HashMap<>(), true, "com.winlator.llm"));
        wine.source = LegacyRedMagicGpuDecision.WINE_SOURCE;
        row.put("source", wine.source);
        assertFalse(LegacyRedMagicGpuDecision.applies(patched, state, new HashMap<>(), true, "com.winlator.llm"));
        wine.source = LegacyRedMagicGpuDecision.RWOA_WINE_SOURCE;
        wine.checksum = LegacyRedMagicGpuDecision.WINE_SHA;
        row.put("source", wine.source).put("checksum", wine.checksum);
        assertFalse(LegacyRedMagicGpuDecision.applies(patched, state, new HashMap<>(), true, "com.winlator.llm"));
    }
    @Test public void extraMissingDuplicateOrChangedLayerKeepsOriginalDriver() throws Exception {
        BoxSpec extra=spec(); BoxSpec.Layer custom=new BoxSpec.Layer(); custom.type="custom-layer"; extra.layers.add(custom);
        assertFalse(LegacyRedMagicGpuDecision.applies(extra,state(),new HashMap<>(),true,"com.winlator.llm"));
        BoxSpec missing=spec(); missing.layers.remove(8);
        assertFalse(LegacyRedMagicGpuDecision.applies(missing,state(),new HashMap<>(),true,"com.winlator.llm"));
        BoxSpec duplicate=spec(); duplicate.layers.get(8).type=duplicate.layers.get(7).type;
        assertFalse(LegacyRedMagicGpuDecision.applies(duplicate,state(),new HashMap<>(),true,"com.winlator.llm"));
        JSONObject extraState=state(); extraState.getJSONArray("installedLayers").put(new JSONObject().put("type","custom-layer"));
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(),extraState,new HashMap<>(),true,"com.winlator.llm"));
        JSONObject missingState=state(); missingState.getJSONArray("installedLayers").remove(8);
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(),missingState,new HashMap<>(),true,"com.winlator.llm"));
        JSONObject duplicateState=state(); JSONArray rows=duplicateState.getJSONArray("installedLayers"); rows.put(8,new JSONObject(rows.getJSONObject(7).toString()));
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(),duplicateState,new HashMap<>(),true,"com.winlator.llm"));
        JSONObject changed=state(); changed.getJSONArray("installedLayers").getJSONObject(3).put("source","custom");
        assertFalse(LegacyRedMagicGpuDecision.applies(spec(),changed,new HashMap<>(),true,"com.winlator.llm"));
    }
}
