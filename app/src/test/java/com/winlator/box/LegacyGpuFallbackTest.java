package com.winlator.box;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyGpuFallbackTest {
    private JSONObject layers(int count, boolean ok) throws Exception {
        JSONArray a = new JSONArray();
        if (count > 0) {
            String root="@app/contents/adrenotools/"+LegacyRedMagicGpuDecision.OLD_ID+"/";
            JSONArray files=new JSONArray().put(new JSONObject().put("path",root+"meta.json").put("exists",true).put("sha256","cc23faad77d081f97522e1fff190ee13f8b1e10eebc9047ae505cf248ee8cb98"))
                    .put(new JSONObject().put("path",root+"vulkan.ad07xx.so").put("exists",true).put("sha256","27f6d9e712ac260c75a2acfdf619ffbc967c76baa5d1c3de8657210167f80a80"));
            a.put(new JSONObject().put("type","gpu-driver-stack").put("source",LegacyRedMagicGpuDecision.OLD_SOURCE).put("target",LegacyRedMagicGpuDecision.OLD_ID)
                    .put("installed",true).put("sourceMatches",true).put("identityMatches",true).put("criticalBaselineAvailable",true)
                    .put("criticalFilesPresent",true).put("criticalFilesMatch",true).put("baselineCriticalFiles",new JSONArray(files.toString())).put("criticalFiles",files));
        }
        for (int i=1;i<count;i++) a.put(new JSONObject());
        return new JSONObject().put("ok",ok).put("layers",a);
    }
    @Test public void missingBaselineOrMatchingButTamperedFilesCannotFallback() throws Exception {
        JSONObject noBaseline=layers(9,true); noBaseline.getJSONArray("layers").getJSONObject(0).put("criticalBaselineAvailable",false);
        assertFalse(LegacyGpuFallback.apply(missing(),noBaseline).getBoolean("ready"));
        JSONObject tampered=layers(9,true); JSONObject gpu=tampered.getJSONArray("layers").getJSONObject(0);
        gpu.getJSONArray("baselineCriticalFiles").getJSONObject(1).put("sha256","attacker"); gpu.getJSONArray("criticalFiles").getJSONObject(1).put("sha256","attacker");
        assertFalse(LegacyGpuFallback.apply(missing(),tampered).getBoolean("ready"));
        JSONObject unsafe=layers(9,true); unsafe.getJSONArray("layers").getJSONObject(0).getJSONArray("criticalFiles").getJSONObject(0).put("unsafe",true);
        assertFalse(LegacyGpuFallback.apply(missing(),unsafe).getBoolean("ready"));
    }
    private JSONObject missing() throws Exception { return new JSONObject().put("eligible",true).put("selected",false).put("overrideAvailable",false).put("ready",false); }
    @Test public void missingOverrideUsesOnlyVerifiedNineOriginalLayers() throws Exception {
        JSONObject status = LegacyGpuFallback.apply(missing(),layers(9,true));
        assertTrue(status.getBoolean("ready")); assertTrue(status.getBoolean("fallbackToOriginal")); assertEquals(LegacyRedMagicGpuDecision.OLD_ID,status.getString("driverId"));
        assertFalse(LegacyGpuFallback.apply(missing(),layers(8,true)).getBoolean("ready"));
        assertFalse(LegacyGpuFallback.apply(missing(),layers(9,false)).getBoolean("ready"));
    }
    @Test public void verifiedOverrideAndExplicitChoiceAreNeverReplacedByFallback() throws Exception {
        JSONObject available = new JSONObject().put("eligible",true).put("selected",true).put("overrideAvailable",true).put("driverId",LegacyRedMagicGpuDecision.NEW_ID);
        assertEquals(LegacyRedMagicGpuDecision.NEW_ID,LegacyGpuFallback.apply(available,layers(9,true)).getString("driverId"));
        JSONObject explicit = new JSONObject().put("eligible",false).put("selected",false).put("ready",true);
        assertFalse(LegacyGpuFallback.apply(explicit,layers(9,true)).has("fallbackToOriginal"));
    }
}
