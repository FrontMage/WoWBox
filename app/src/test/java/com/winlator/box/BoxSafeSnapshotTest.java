package com.winlator.box;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BoxSafeSnapshotTest {
    @Test
    public void exportsOnlyKnownSafeFieldsWithoutMutatingUserSnapshot() throws Exception {
        JSONObject source = new JSONObject();
        source.put("spec", new JSONObject().put("account", "alice@example.com")
                .put("path", "/Users/alice/private").put("token", "secret-token"));
        source.put("state", new JSONObject().put("installed", true).put("lastLaunchPath", "C:\\Users\\alice"));
        source.put("mounts", new JSONArray().put(new JSONObject().put("hostPath", "/storage/alice")));
        source.put("layers", new JSONArray()
                .put(new JSONObject().put("type", "wine-runtime").put("installedAt", 1)
                        .put("source", "asset://private-alice-token"))
                .put(new JSONObject().put("type", "private-alice-layer").put("installedAt", 2))
                .put(new JSONObject().put("type", "wine-runtime").put("installedAt", 3)));
        source.put("env", new JSONObject().put("PRIVATE_SECRET", new JSONObject().put("value", "secret-token"))
                .put("WINEESYNC", new JSONObject().put("value", "1"))
                .put("WINEFSYNC", new JSONObject().put("value", "secret-token")));
        source.put("currentSession", new JSONObject().put("status", "running").put("sessionId", "alice-session"));
        String original = source.toString();

        JSONObject result = BoxSafeSnapshot.create(source, "0.2.0-preview", 33);

        assertEquals(original, source.toString());
        assertEquals("0.2.0-preview", result.getString("appVersion"));
        assertTrue(result.getBoolean("installed"));
        assertEquals("running", result.getString("sessionStatus"));
        assertEquals(1, result.getJSONArray("layers").length());
        assertEquals("wine-runtime", result.getJSONArray("layers").getJSONObject(0).getString("type"));
        assertTrue(result.getJSONObject("runtimeFlags").getBoolean("WINEESYNC"));
        assertFalse(result.getJSONObject("runtimeFlags").has("WINEFSYNC"));
        for (String privateValue : new String[]{"alice", "secret", "/Users", "/storage", "PRIVATE_SECRET"}) {
            assertFalse("Unexpected private value " + privateValue, result.toString().contains(privateValue));
        }
    }

    @Test
    public void invalidOrMissingValuesAreNotEchoed() throws Exception {
        JSONObject input = new JSONObject().put("state", new JSONObject().put("installed", false))
                .put("layers", new JSONArray().put(new JSONObject().put("type", "gpu-driver-stack")
                        .put("installedAt", JSONObject.NULL)))
                .put("currentSession", new JSONObject().put("status", "account@example.com"));
        JSONObject result = BoxSafeSnapshot.create(input, "invalid@example.com", -1);
        assertFalse(result.has("appVersion"));
        assertFalse(result.has("androidSdk"));
        assertFalse(result.has("sessionStatus"));
        assertFalse(result.getJSONArray("layers").getJSONObject(0).getBoolean("recordedInstalled"));
        assertFalse(result.getBoolean("installed"));
        assertEquals("wowbox-safe-summary-v1", BoxSafeSnapshot.create(null, null, 0).getString("format"));
    }
}
