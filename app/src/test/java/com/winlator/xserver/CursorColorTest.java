package com.winlator.xserver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CursorColorTest {
    @Test
    public void usesHighEightBitsOfCard16Components() {
        assertEquals(0x00, CursorColor.rgb16To8(0x00ff));
        assertEquals(0x12, CursorColor.rgb16To8(0x1234));
        assertEquals(0xff, CursorColor.rgb16To8(0xffff));
        assertEquals(0xff12569a, CursorColor.packOpaqueArgb(
                0x1234, 0x5678, 0x9abc));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonCard16Components() {
        CursorColor.rgb16To8(0x10000);
    }
}
