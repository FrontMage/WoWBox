package com.winlator.xserver;

public final class RenderPictFormats {
    public final PictFormat argb32 = new PictFormat(
            IDGenerator.generate(),
            32,
            16, 0xff,
            8, 0xff,
            0, 0xff,
            24, 0xff);
    public final PictFormat rgb24 = new PictFormat(
            IDGenerator.generate(),
            24,
            16, 0xff,
            8, 0xff,
            0, 0xff,
            0, 0);
    public final PictFormat a1 = new PictFormat(
            IDGenerator.generate(),
            1,
            0, 0,
            0, 0,
            0, 0,
            0, 1);

    public final PictFormat[] all = {argb32, rgb24, a1};

    public PictFormat get(int id) {
        for (PictFormat format : all) {
            if (format.id == id) return format;
        }
        return null;
    }
}
