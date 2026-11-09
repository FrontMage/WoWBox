package com.winlator.box;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BoxRwoaStatePreservationTest {
    @Test public void updatedRecordPreservesUserAndUnknownFields() throws Exception {
        JSONObject wine = new JSONObject().put("type", "wine-runtime").put("source", "old")
                .put("customExtension", "keep").put("installedAt", 1);
        JSONObject other = new JSONObject().put("type", "gpu-driver-stack").put("custom", "untouched");
        JSONObject state = new JSONObject().put("userSettings", new JSONObject().put("flag", "keep"))
                .put("installedLayers", new JSONArray().put(wine).put(other));
        BoxSpec.Layer newWine = new BoxSpec.Layer();
        newWine.type = "wine-runtime"; newWine.source = "new"; newWine.checksum = "checksum";
        JSONArray critical = new JSONArray().put(new JSONObject().put("sha256", "new"));
        BoxInstaller.replaceRwoaLayerRecord(state, newWine, critical);
        assertEquals("keep", state.getJSONObject("userSettings").getString("flag"));
        assertEquals("keep", wine.getString("customExtension"));
        assertEquals("new", wine.getString("source"));
        assertEquals(critical.toString(), wine.getJSONArray("criticalFiles").toString());
        assertEquals("untouched", other.getString("custom"));
    }
    @Test public void duplicateRecordsFailClosed() throws Exception {
        BoxSpec.Layer layer = new BoxSpec.Layer(); layer.type = "wine-runtime";
        JSONObject state = new JSONObject().put("installedLayers", new JSONArray()
                .put(new JSONObject().put("type", layer.type))
                .put(new JSONObject().put("type", layer.type)));
        try { BoxInstaller.replaceRwoaLayerRecord(state, layer, new JSONArray()); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("duplicate")); }
    }
}
