package com.winlator.box;
import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BoxRwoaBaselineTest {
    private JSONObject row(String type) throws Exception {
        JSONArray baseline = new JSONArray().put(new JSONObject().put("path", "/one"))
                .put(new JSONObject().put("path", "/two"));
        return new JSONObject().put("type", type).put("identityMatches", true)
                .put("criticalBaselineAvailable", true).put("criticalFilesPresent", true)
                .put("criticalFilesMatch", true).put("winePrefixDeltaMatches", true)
                .put("baselineCriticalFiles", baseline).put("criticalFiles", new JSONArray(baseline.toString()));
    }
    private JSONObject report() throws Exception {
        JSONArray layers = new JSONArray().put(row("wine-runtime")).put(row("prefix-template"));
        for (String type : new String[]{"base-imagefs", "graphics-wrapper-stack", "frame-generation-stack",
                "input-bridge-stack", "dx-wrapper-stack", "gpu-driver-stack", "cpu-emu-stack"}) {
            layers.put(row(type));
        }
        return new JSONObject().put("ok", true).put("layers", layers);
    }
    private void rejected(JSONObject value) {
        try { BoxInstaller.requireRwoaVerifiedBaseline(value); fail(); }
        catch (IllegalStateException expected) { /* fail closed */ }
    }
    @Test public void completeMatchingBaselinesAccepted() throws Exception {
        BoxInstaller.requireRwoaVerifiedBaseline(report());
    }
    @Test public void missingEmptyIncompleteDuplicateAndTamperedBaselinesRejected() throws Exception {
        JSONObject r = report(); r.getJSONArray("layers").getJSONObject(0).put("baselineCriticalFiles", JSONObject.NULL); rejected(r);
        r = report(); r.getJSONArray("layers").getJSONObject(0).put("baselineCriticalFiles", new JSONArray()); rejected(r);
        r = report(); r.getJSONArray("layers").getJSONObject(0).getJSONArray("baselineCriticalFiles").remove(0); rejected(r);
        r = report(); r.getJSONArray("layers").getJSONObject(0).getJSONArray("baselineCriticalFiles").getJSONObject(1).put("path", "/one"); rejected(r);
        r = report(); r.getJSONArray("layers").getJSONObject(1).put("criticalFilesMatch", false); rejected(r);
        r = report(); r.getJSONArray("layers").getJSONObject(1).put("identityMatches", false); rejected(r);
        r = report(); r.getJSONArray("layers").getJSONObject(1).put("winePrefixDeltaMatches", false); rejected(r);
        r = report(); r.getJSONArray("layers").getJSONObject(1).getJSONArray("criticalFiles").getJSONObject(1).put("path", "/unknown"); rejected(r);
        r = report(); r.getJSONArray("layers").remove(2); rejected(r);
        r = report(); r.getJSONArray("layers").put(row("gpu-driver-stack")); rejected(r);
        r = report(); r.getJSONArray("layers").put(row("custom-layer")); rejected(r);
    }
}
