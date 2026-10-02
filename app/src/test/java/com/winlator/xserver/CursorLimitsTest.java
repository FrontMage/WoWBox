package com.winlator.xserver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CursorLimitsTest {
    @Test
    public void exposesFixedResourceLimits() {
        assertEquals(512, CursorManager.MAX_CURSOR_DIMENSION);
        assertEquals(256, CursorManager.MAX_ANIMATION_FRAMES);
        assertEquals(64L * 1024L * 1024L, CursorManager.MAX_CURSOR_PIXEL_BYTES);
        assertEquals(512L * 512L * 4L, CursorManager.checkedPixelBytes(512, 512));
        CursorManager.validateStaticGeometry(512, 512);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedStaticCursor() {
        CursorManager.validateStaticGeometry(513, 1);
    }
}
