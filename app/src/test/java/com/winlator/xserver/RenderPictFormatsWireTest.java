package com.winlator.xserver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RenderPictFormatsWireTest {
    @Test
    public void encodesThreeFormatsAndOneScreenInClientByteOrder() {
        RenderPictFormats formats = new RenderPictFormats();
        int rootVisual = 0x10203040;
        ByteBuffer wire = RenderPictFormatsWire.encode(
                formats, rootVisual, ByteOrder.LITTLE_ENDIAN);

        assertEquals(128, wire.remaining());
        assertFormat(wire, formats.argb32, 32, 24, 0xff);
        assertFormat(wire, formats.rgb24, 24, 0, 0);
        assertFormat(wire, formats.a1, 1, 0, 1);

        assertEquals(3, wire.getInt());
        assertEquals(formats.rgb24.id, wire.getInt());
        assertDepth(wire, 1, 0);
        assertDepth(wire, 24, 0);
        assertDepth(wire, 32, 1);
        assertEquals(rootVisual, wire.getInt());
        assertEquals(formats.argb32.id, wire.getInt());
        assertEquals(0, wire.getInt());
        assertEquals(0, wire.remaining());
    }

    private static void assertFormat(
            ByteBuffer wire,
            PictFormat expected,
            int depth,
            int alphaShift,
            int alphaMask) {
        assertEquals(expected.id, wire.getInt());
        assertEquals(PictFormat.TYPE_DIRECT, Byte.toUnsignedInt(wire.get()));
        assertEquals(depth, Byte.toUnsignedInt(wire.get()));
        assertEquals(0, Short.toUnsignedInt(wire.getShort()));
        assertEquals(expected.redShift, Short.toUnsignedInt(wire.getShort()));
        assertEquals(expected.redMask, Short.toUnsignedInt(wire.getShort()));
        assertEquals(expected.greenShift, Short.toUnsignedInt(wire.getShort()));
        assertEquals(expected.greenMask, Short.toUnsignedInt(wire.getShort()));
        assertEquals(expected.blueShift, Short.toUnsignedInt(wire.getShort()));
        assertEquals(expected.blueMask, Short.toUnsignedInt(wire.getShort()));
        assertEquals(alphaShift, Short.toUnsignedInt(wire.getShort()));
        assertEquals(alphaMask, Short.toUnsignedInt(wire.getShort()));
        assertEquals(0, wire.getInt());
    }

    private static void assertDepth(ByteBuffer wire, int depth, int visuals) {
        assertEquals(depth, Byte.toUnsignedInt(wire.get()));
        assertEquals(0, Byte.toUnsignedInt(wire.get()));
        assertEquals(visuals, Short.toUnsignedInt(wire.getShort()));
        assertEquals(0, wire.getInt());
    }
}
