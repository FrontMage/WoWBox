package com.winlator.xserver;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class PictureSnapshotTest {
    @Test
    public void ownsAnImmutablePixelSnapshot() {
        PictFormat format = new PictFormat(
                1, 32, 16, 0xff, 8, 0xff, 0, 0xff, 24, 0xff);
        int[] source = {0x80604020, 0xffd0a040};
        Picture picture = new Picture(2, 2, 1, format, source);

        source[0] = 0;
        int[] firstCopy = picture.copyArgbPremultiplied();
        firstCopy[1] = 0;
        assertArrayEquals(
                new int[]{0x80604020, 0xffd0a040},
                picture.copyArgbPremultiplied());
    }
}
