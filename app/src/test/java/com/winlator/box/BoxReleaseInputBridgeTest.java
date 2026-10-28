package com.winlator.box;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class BoxReleaseInputBridgeTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void sourceMustExistAndMatchExpectedHash() throws Exception {
        File source = temp.newFile("xinput1_3.dll");
        Files.write(source.toPath(), "bridge".getBytes(StandardCharsets.UTF_8));
        String hash = WinePrefixDeltaReconciler.sha256(source);
        BoxInstaller.requireReleaseInputFile(source, hash);
        try { BoxInstaller.requireReleaseInputFile(source, "bad-hash"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("input_bridge_mismatch")); }
        assertTrue(source.delete());
        try { BoxInstaller.requireReleaseInputFile(source, hash); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("input_bridge_mismatch")); }
    }

    @Test public void inputLayerAndMigrationStateDescribeNewBridge() throws Exception {
        BoxSpec.Layer input = new BoxSpec.Layer();
        input.type = "input-bridge-stack";
        input.source = "asset://winhandler/new.tzst";
        input.checksum = "149a9ca40b1119d01de5599faead07a9748cba511b0039fafaa48b4e0e10078c";
        input.runtimeIdentifier = "runtime";
        input.target = "/prefix/system32";
        JSONArray files = new JSONArray();
        for (String p : new String[]{"prefix/xinput1_3.dll", "prefix/xinput1_4.dll", "wine/xinput1_3.dll", "wine/xinput1_4.dll"}) {
            files.put(new JSONObject().put("path", p).put("exists", true).put("size", 5).put("sha256", "new"));
        }
        JSONObject state = new JSONObject().put("userData", "keep").put("installedLayers",
                new JSONArray().put(new JSONObject().put("type", "input-bridge-stack").put("source", "old")));
        BoxInstaller.replaceReleaseLayerRecord(state, input, files);
        BoxInstaller.recordReleaseInputIdentity(state, input);
        JSONObject row = state.getJSONArray("installedLayers").getJSONObject(0);
        assertEquals(input.source, row.getString("source"));
        assertEquals(input.checksum, row.getString("checksum"));
        assertEquals(4, row.getJSONArray("criticalFiles").length());
        assertEquals(input.source, state.getString("wowInputBridgeMigrationSource"));
        assertEquals(input.checksum, state.getString("wowInputBridgeMigrationChecksum"));
        assertEquals(1, state.getInt("wowInputBridgeMigrationVersion"));
        assertEquals("keep", state.getString("userData"));
    }
}
