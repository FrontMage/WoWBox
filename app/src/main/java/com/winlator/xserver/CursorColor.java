package com.winlator.xserver;

public final class CursorColor {
    private CursorColor() {}

    public static int rgb16To8(int component) {
        if ((component & ~0xffff) != 0) {
            throw new IllegalArgumentException("X11 color component is not CARD16");
        }
        return (component >>> 8) & 0xff;
    }

    public static int packOpaqueArgb(int red, int green, int blue) {
        return 0xff000000 |
                (rgb16To8(red) << 16) |
                (rgb16To8(green) << 8) |
                rgb16To8(blue);
    }
}
