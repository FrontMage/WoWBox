package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.winlator.core.EnvVars;

import org.junit.Test;

public class FrameGenerationManagerTest {
    @Test
    public void buildsStartupOnlyFifoConfiguration() {
        String result = FrameGenerationManager.buildConfig(
                "/data/user/0/com.winlator.llm/files/box/frame-generation/Lossless.dll",
                2,
                0.80f,
                true);

        assertTrue(result.contains("exe = \"wow-box-lsfg\""));
        assertTrue(result.contains("multiplier = 2"));
        assertTrue(result.contains("flow_scale = 0.80"));
        assertTrue(result.contains("performance_mode = true"));
        assertTrue(result.contains("hdr_mode = false"));
        assertTrue(result.contains("experimental_present_mode = \"fifo\""));
        assertEquals("fifo", FrameGenerationManager.PRESENT_MODE);
    }

    @Test
    public void clampsMultiplierAndFlowScale() {
        assertEquals(2, FrameGenerationManager.normalizeMultiplier(1));
        assertEquals(4, FrameGenerationManager.normalizeMultiplier(9));
        assertEquals(0.25f, FrameGenerationManager.normalizeFlowScale(-1), 0.0001f);
        assertEquals(1.0f, FrameGenerationManager.normalizeFlowScale(5), 0.0001f);
        assertEquals(
                FrameGenerationManager.DEFAULT_FLOW_SCALE,
                FrameGenerationManager.normalizeFlowScale(Float.NaN),
                0.0001f);
    }

    @Test
    public void capsGeneratedOutputAtSixtyFps() {
        assertEquals(60, FrameGenerationManager.OUTPUT_FPS_CAP);
        assertEquals(30, FrameGenerationManager.sourceFpsCapForMultiplier(2));
        assertEquals(20, FrameGenerationManager.sourceFpsCapForMultiplier(3));
        assertEquals(15, FrameGenerationManager.sourceFpsCapForMultiplier(4));
        assertEquals(30, FrameGenerationManager.sourceFpsCapForMultiplier(1));
        assertEquals(15, FrameGenerationManager.sourceFpsCapForMultiplier(9));
    }

    @Test
    public void disablesPresentWaitForActiveFrameGeneration() {
        EnvVars envVars = new EnvVars();

        FrameGenerationManager.applyVulkanCompatibilityEnv(envVars);

        assertEquals(
                "1",
                envVars.get(FrameGenerationManager.PRESENT_WAIT_COMPAT_ENV));
    }
}
