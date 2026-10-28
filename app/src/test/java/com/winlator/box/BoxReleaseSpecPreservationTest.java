package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BoxReleaseSpecPreservationTest {
    @Test public void keepsCustomTargetsEnvironmentAndUnknownFieldsWhenUpdatingLayers() throws Exception {
        JSONObject original = new JSONObject();
        original.put("schemaVersion", 1);
        original.put("boxId", "frontmage-bnet-arm64");
        original.put("futureUserField", new JSONObject().put("opaque", "keep-me"));
        JSONArray layers = new JSONArray();
        for (String name : new String[]{"wine-runtime", "prefix-template", "cpu-emu-stack", "gpu-driver-stack"}) {
            layers.put(new JSONObject().put("type", name).put("source", "old-" + name)
                    .put("privateExtension", "must-keep-" + name));
        }
        original.put("layers", layers);
        JSONObject target = new JSONObject().put("id", "my-custom-target").put("kind", "executable")
                .put("guestPath", "D:\\user.exe").put("args", new JSONArray().put("--disable-gpu"))
                .put("userExtension", "preserve");
        JSONObject launch = new JSONObject().put("defaultTargetId", "my-custom-target")
                .put("targets", new JSONArray().put(target)).put("extraLaunchField", "custom");
        JSONObject env = new JSONObject().put("base", new JSONObject().put("WINEDEBUG", "+seh"))
                .put("overrides", new JSONObject().put("CUSTOM_ENV", "example"))
                .put("userExtension", "preserve-env");
        original.put("launch", launch); original.put("env", env);
        BoxSpec candidate = BoxSpec.fromJson(new JSONObject(original.toString()));
        for (BoxSpec.Layer layer : candidate.layers) {
            if (!"gpu-driver-stack".equals(layer.type)) layer.source = "new-" + layer.type;
        }
        JSONObject result = new JSONObject(BoxInstaller.releaseSpecPreservingUserFields(original.toString(), candidate));
        assertEquals("keep-me", result.getJSONObject("futureUserField").getString("opaque"));
        JSONObject resultLaunch = result.getJSONObject("launch");
        assertEquals("my-custom-target", resultLaunch.getString("defaultTargetId"));
        assertEquals("custom", resultLaunch.getString("extraLaunchField"));
        JSONObject resultTarget = resultLaunch.getJSONArray("targets").getJSONObject(0);
        assertEquals("my-custom-target", resultTarget.getString("id"));
        assertEquals("D:\\user.exe", resultTarget.getString("guestPath"));
        assertEquals("--disable-gpu", resultTarget.getJSONArray("args").getString(0));
        assertEquals("preserve", resultTarget.getString("userExtension"));
        JSONObject resultEnv = result.getJSONObject("env");
        assertEquals("+seh", resultEnv.getJSONObject("base").getString("WINEDEBUG"));
        assertEquals("example", resultEnv.getJSONObject("overrides").getString("CUSTOM_ENV"));
        assertEquals("preserve-env", resultEnv.getString("userExtension"));
        JSONArray resultLayers = result.getJSONArray("layers");
        for (int i = 0; i < 3; i++) {
            JSONObject item = resultLayers.getJSONObject(i);
            assertEquals("new-" + item.getString("type"), item.getString("source"));
            assertEquals("must-keep-" + item.getString("type"), item.getString("privateExtension"));
        }
        assertEquals("must-keep-gpu-driver-stack", resultLayers.getJSONObject(3).getString("privateExtension"));
    }

    @Test public void rejectsMissingOrDuplicateManagedLayers() throws Exception {
        JSONObject original = new JSONObject().put("boxId", "frontmage-bnet-arm64")
                .put("layers", new JSONArray().put(new JSONObject().put("type", "wine-runtime"))
                        .put(new JSONObject().put("type", "wine-runtime"))
                        .put(new JSONObject().put("type", "prefix-template"))
                        .put(new JSONObject().put("type", "cpu-emu-stack")));
        BoxSpec candidate = BoxSpec.fromJson(new JSONObject(original.toString()));
        try { BoxInstaller.releaseSpecPreservingUserFields(original.toString(), candidate); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("duplicate")); }
    }
}
