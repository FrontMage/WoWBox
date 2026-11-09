package com.winlator.box;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BoxDisplayRendererPolicyTest {
    @Test public void requiresPackagedCompositorBoxSessionAndTargetDevice() {
        assertEquals("vulkan", BoxDisplayRendererPolicy.selectOrdinaryBox(true, true, true));
        assertEquals("gl", BoxDisplayRendererPolicy.selectOrdinaryBox(false, true, true));
        assertEquals("gl", BoxDisplayRendererPolicy.selectOrdinaryBox(true, false, true));
        assertEquals("gl", BoxDisplayRendererPolicy.selectOrdinaryBox(true, true, false));
    }
}
