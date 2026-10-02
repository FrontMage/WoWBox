package com.winlator.xserver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

public class RenderPixelCodecTest {
    private static final PictFormat ARGB32 = new PictFormat(
            1, 32, 16, 0xff, 8, 0xff, 0, 0xff, 24, 0xff);
    private static final PictFormat RGB24 = new PictFormat(
            2, 24, 16, 0xff, 8, 0xff, 0, 0xff, 0, 0);
    private static final PictFormat A1 = new PictFormat(
            3, 1, 0, 0, 0, 0, 0, 0, 0, 1);

    @Test
    public void preservesPremultipliedArgbGoldPixelsInSnapshot() {
        int[] input = {0x00000000, 0x80604020, 0xffd0a040};
        int[] output = RenderPixelCodec.toPremultipliedArgb(ARGB32, input);
        assertNotSame(input, output);
        assertArrayEquals(input, output);
    }

    @Test
    public void normalizesRgb24AndA1ToPremultipliedArgb() {
        assertArrayEquals(
                new int[]{0xff112233, 0xffabcdef},
                RenderPixelCodec.toPremultipliedArgb(
                        RGB24, new int[]{0x00112233, 0x55abcdef}));
        assertArrayEquals(
                new int[]{0x00000000, 0xffffffff},
                RenderPixelCodec.toPremultipliedArgb(
                        A1, new int[]{0xff000000, 0xffffffff}));
    }
}
