package com.winlator.box;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class BoxInitialSpecRefreshPolicyTest {
    private static final String STANDARD = "com.winlator.llm";
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static BoxSpec bundled() {
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
        BoxSpec.Target bnet = new BoxSpec.Target();
        bnet.id = "bnet";
        bnet.guestPath = "C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe";
        spec.launch.targets.add(bnet);
        return spec;
    }

    private static JSONObject persisted(BoxSpec spec) throws Exception {
        return new JSONObject(spec.toJson().toString(2));
    }

    @Test public void automationFreshSelectsSameDriverAsGuiFresh() throws Exception {
        BoxSpec gui = bundled();
        assertTrue(BoxRedMagicGpuPolicy.applyFreshInstall(gui, true, STANDARD));
        // Normal first launch already persisted the GUI-selected spec.
        JSONObject alreadySelected = new JSONObject(gui.toJson().toString());
        BoxInitialSpecRefreshPolicy.validate(alreadySelected, bundled(), true, STANDARD);
        assertEquals("stevenmxz-gen8-v39", BoxSpec.fromJson(alreadySelected).graphics.turnipVersion);
        // A pre-existing canonical default is accepted without rewriting it.
        JSONObject defaultSpec = persisted(bundled());
        String before = defaultSpec.toString();
        BoxInitialSpecRefreshPolicy.validate(defaultSpec, bundled(), true, STANDARD);
        assertEquals(before, defaultSpec.toString());
        assertEquals("26.1.5-frontmage-ir3-nonstrict", BoxSpec.fromJson(defaultSpec).graphics.turnipVersion);
    }

    @Test public void nonTargetAndOtherPackageKeepOriginalGpu() throws Exception {
        JSONObject a = persisted(bundled()), b = persisted(bundled());
        BoxInitialSpecRefreshPolicy.validate(a, bundled(), false, STANDARD);
        BoxInitialSpecRefreshPolicy.validate(b, bundled(), true, "other.package");
        assertEquals("26.1.5-frontmage-ir3-nonstrict", BoxSpec.fromJson(a).graphics.turnipVersion);
        assertEquals("26.1.5-frontmage-ir3-nonstrict", BoxSpec.fromJson(b).graphics.turnipVersion);
    }

    @Test public void customEnvironmentTargetGpuAndUnknownFieldsFailClosed() throws Exception {
        for (String mutation : new String[]{"env", "target", "gpu", "unknown"}) {
            JSONObject persisted = persisted(bundled());
            switch (mutation) {
                case "env": persisted.getJSONObject("env").getJSONObject("overrides").put("MY_SETTING", "keep"); break;
                case "target": persisted.getJSONObject("launch").getJSONArray("targets").getJSONObject(0)
                        .getJSONArray("args").put("--custom"); break;
                case "gpu": persisted.getJSONObject("graphics").put("turnipVersion", "my-driver"); break;
                case "unknown": persisted.getJSONObject("env").put("myFutureField", "keep"); break;
            }
            String before = persisted.toString();
            try {
                BoxInitialSpecRefreshPolicy.validate(persisted, bundled(), true, STANDARD);
                fail("expected custom " + mutation + " to be preserved");
            } catch (IllegalStateException expected) {
                assertEquals("initial_spec_has_custom_fields", expected.getMessage());
            }
            assertEquals(before, persisted.toString());
        }
    }

    @Test public void ownedPartialInstallRetryKeepsExactSpecAndPrefix() throws Exception {
        File root = temp.newFolder("retry"), imagefs = new File(root, "imagefs");
        File specFile = new File(root, "box-spec.json"), marker = new File(root, "owner");
        BoxSpec selected = bundled();
        assertTrue(BoxRedMagicGpuPolicy.applyFreshInstall(selected, true, STANDARD));
        String original = selected.toJson().toString();
        Files.write(specFile.toPath(), original.getBytes(StandardCharsets.UTF_8));
        BoxInitialInstallGuard.beginOrResume(imagefs, marker, specFile, false);
        assertTrue(new File(imagefs, "home/xuser-box/.wine").mkdirs());
        assertTrue(BoxInitialInstallGuard.mayResume(marker, specFile, false));
        assertEquals(original, new String(Files.readAllBytes(specFile.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void installedPrefixCannotBeginFreshInstall() throws Exception {
        File root = temp.newFolder("installed"), spec = new File(root, "spec.json");
        Files.write(spec.toPath(), bundled().toJson().toString().getBytes(StandardCharsets.UTF_8));
        try {
            BoxInitialInstallGuard.beginOrResume(new File(root, "imagefs"), new File(root, "owner"), spec, true);
            fail("installed prefix must not be replaced");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("existing_install"));
        }
    }
}
