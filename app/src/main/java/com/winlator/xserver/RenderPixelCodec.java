package com.winlator.xserver;

public final class RenderPixelCodec {
    private RenderPixelCodec() {}

    public static int[] toPremultipliedArgb(PictFormat format, int[] pixels) {
        if (format == null || pixels == null) {
            throw new IllegalArgumentException("Picture format and pixels are required");
        }

        int[] normalized = pixels.clone();
        if (format.depth == 32 && format.alphaMask == 0xff) {
            return normalized;
        }
        if (format.depth == 24 && format.alphaMask == 0) {
            for (int i = 0; i < normalized.length; i++) {
                normalized[i] = 0xff000000 | (normalized[i] & 0x00ffffff);
            }
            return normalized;
        }
        if (format.depth == 1 && format.alphaMask == 1) {
            for (int i = 0; i < normalized.length; i++) {
                normalized[i] = (normalized[i] & 0x00ffffff) != 0
                        ? 0xffffffff
                        : 0x00000000;
            }
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported Picture format");
    }
}
