package com.winlator.xserver;

import java.util.Arrays;

public final class Picture extends XResource {
    public final int width;
    public final int height;
    public final PictFormat format;
    private final int[] argbPremultiplied;

    public Picture(
            int id,
            int width,
            int height,
            PictFormat format,
            int[] argbPremultiplied) {
        super(id);
        if (format == null || argbPremultiplied == null ||
                argbPremultiplied.length != width * height) {
            throw new IllegalArgumentException("Invalid Picture snapshot");
        }
        this.width = width;
        this.height = height;
        this.format = format;
        this.argbPremultiplied = Arrays.copyOf(
                argbPremultiplied, argbPremultiplied.length);
    }

    public int[] copyArgbPremultiplied() {
        return Arrays.copyOf(argbPremultiplied, argbPremultiplied.length);
    }
}
