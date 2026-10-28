package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.winlator.core.EnvVars;

import org.junit.Test;

public class RenderDocEnvironmentTest {
    private static final String APP_FILES = "/data/user/0/com.blizzard.diablo.immortal/files";
    private static final String ROOT = APP_FILES + "/diagnostics";

    @Test
    public void disabledPreferenceOverridesAllHistoricalCaptureActivation() {
        EnvVars env = new EnvVars();
        env.put("ENABLE_VULKAN_RENDERDOC_CAPTURE", "1");
        env.put("VK_INSTANCE_LAYERS", RenderDocEnvironment.LAYER);
        env.put("RENDERDOC_CAPFILE", "/historical/output");
        env.put("RENDERDOC_TEMP", "/historical/temp");
        env.put("RENDERDOC_CAPOPTS", "historical-options");
        RenderDocEnvironment.apply(env, false, "", "", APP_FILES);
        assertEquals("0", env.get("ENABLE_VULKAN_RENDERDOC_CAPTURE"));
        assertEquals("1", env.get("DISABLE_VULKAN_RENDERDOC_CAPTURE"));
        assertEquals("1", env.get("DISABLE_VULKAN_RENDERDOC_CAPTURE_1_44"));
        assertEquals("", env.get("VK_INSTANCE_LAYERS"));
        assertTrue(env.has("RENDERDOC_TEMP"));
        assertEquals("", env.get("RENDERDOC_TEMP"));
        assertEquals("", env.get("RENDERDOC_CAPFILE"));
        assertEquals("", env.get("RENDERDOC_CAPOPTS"));
    }

    @Test
    public void preservesOtherLayersAndLayerPathsWhenDisabled() {
        EnvVars env = new EnvVars();
        env.put("VK_INSTANCE_LAYERS", "VK_LAYER_KHRONOS_validation:"
                + RenderDocEnvironment.LAYER + ":VK_LAYER_LS_frame_generation");
        env.put("VK_LAYER_PATH", "/usr/share/vulkan/implicit_layer.d:" + ROOT
                + "/renderdoc-standard-layer-v1:/user/custom/layers");
        RenderDocEnvironment.apply(env, false, "", "", APP_FILES);
        assertEquals("VK_LAYER_KHRONOS_validation:VK_LAYER_LS_frame_generation",
                env.get("VK_INSTANCE_LAYERS"));
        assertEquals("/usr/share/vulkan/implicit_layer.d:/user/custom/layers",
                env.get("VK_LAYER_PATH"));
    }

    @Test
    public void enabledPreferenceOverridesStaleOffAndUsesExclusiveManagedOutput() {
        EnvVars env = new EnvVars();
        env.put("ENABLE_VULKAN_RENDERDOC_CAPTURE", "0");
        env.put("DISABLE_VULKAN_RENDERDOC_CAPTURE", "1");
        env.put("VK_INSTANCE_LAYERS", "VK_LAYER_LS_frame_generation");
        env.put("VK_LAYER_PATH", "/custom/layers");
        String capture = ROOT + "/rdoc-standard-unique-session";
        String layer = ROOT + "/renderdoc/1.44-uu-v1/layers";
        RenderDocEnvironment.apply(env, true, layer, capture, APP_FILES);
        assertEquals("VK_LAYER_LS_frame_generation:" + RenderDocEnvironment.LAYER,
                env.get("VK_INSTANCE_LAYERS"));
        assertEquals("/custom/layers:" + layer, env.get("VK_LAYER_PATH"));
        assertEquals("1", env.get("ENABLE_VULKAN_RENDERDOC_CAPTURE"));
        assertFalse(env.has("DISABLE_VULKAN_RENDERDOC_CAPTURE"));
        assertEquals("", env.get("RENDERDOC_CAPFILE"));
        assertEquals(capture, env.get("RENDERDOC_TEMP"));
        assertEquals("1", env.get("RENDERDOC_DISABLE_ANDROID_KEEPALIVE"));
    }

    @Test
    public void repeatedApplicationKeepsExactlyOneCaptureLayer() {
        EnvVars env = new EnvVars();
        String layer = ROOT + "/renderdoc/1.44-uu-v1/layers";
        RenderDocEnvironment.apply(env, true, layer, ROOT + "/rdoc-standard-one", APP_FILES);
        RenderDocEnvironment.apply(env, true, layer, ROOT + "/rdoc-standard-two", APP_FILES);
        assertEquals(RenderDocEnvironment.LAYER, env.get("VK_INSTANCE_LAYERS"));
        assertEquals(layer, env.get("VK_LAYER_PATH"));
        assertEquals(ROOT + "/rdoc-standard-two", env.get("RENDERDOC_TEMP"));
    }

    @Test
    public void finalPolicyPreservesDriverAndSynchronizationChoices() {
        EnvVars env = new EnvVars();
        env.put("MESA_VK_WSI_DEBUG", "forcesync");
        env.put("TU_DEBUG", "nolrz");
        env.put("LD_PRELOAD", "/existing/lib.so");
        env.put("ADRENOTOOLS_DRIVER_PATH", "/existing/driver");
        RenderDocEnvironment.apply(env, false, "", "", APP_FILES);
        assertEquals("forcesync", env.get("MESA_VK_WSI_DEBUG"));
        assertEquals("nolrz", env.get("TU_DEBUG"));
        assertEquals("/existing/lib.so", env.get("LD_PRELOAD"));
        assertEquals("/existing/driver", env.get("ADRENOTOOLS_DRIVER_PATH"));
    }

    @Test
    public void verifiedRuntimeRequiresAllThreeRouteGatesAndBothIdentities() {
        String archive = RenderDocEnvironment.VALIDATED_WINE_ARCHIVE;
        String ntdll = RenderDocEnvironment.VALIDATED_UNIX_NTDLL;
        assertTrue(RenderDocEnvironment.isValidatedRuntime(true, true, true, archive, ntdll));
        assertFalse(RenderDocEnvironment.isValidatedRuntime(false, true, true, archive, ntdll));
        assertFalse(RenderDocEnvironment.isValidatedRuntime(true, false, true, archive, ntdll));
        assertFalse(RenderDocEnvironment.isValidatedRuntime(true, true, false, archive, ntdll));
        assertFalse(RenderDocEnvironment.isValidatedRuntime(true, true, true, "other", ntdll));
        assertFalse(RenderDocEnvironment.isValidatedRuntime(true, true, true, archive, "other"));
    }

    @Test
    public void keepsOtherApplicationsAndUsersDiagnosticDirectories() {
        String otherApp = "/data/user/0/com.other.app/files/diagnostics/renderdoc-standard-layer-v1";
        String otherUser = "/data/user/10/com.blizzard.diablo.immortal/files/diagnostics/renderdoc/version/layers";
        EnvVars env = new EnvVars();
        env.put("VK_LAYER_PATH", otherApp + ":" + ROOT + "/renderdoc-standard-layer-v1:" + otherUser);
        RenderDocEnvironment.apply(env, false, "", "", APP_FILES);
        assertEquals(otherApp + ":" + otherUser, env.get("VK_LAYER_PATH"));
    }

    @Test
    public void removesTheUserZeroAliasOfItsOwnHistoricalLayerDirectory() {
        EnvVars env = new EnvVars();
        env.put("VK_LAYER_PATH", "/data/data/com.blizzard.diablo.immortal/files/diagnostics/renderdoc-standard-layer-v1");
        RenderDocEnvironment.apply(env, false, "", "", APP_FILES);
        assertEquals("", env.get("VK_LAYER_PATH"));
    }
}
