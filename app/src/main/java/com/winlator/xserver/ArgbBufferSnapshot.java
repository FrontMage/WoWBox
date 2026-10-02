package com.winlator.xserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public final class ArgbBufferSnapshot {
    private ArgbBufferSnapshot() {}

    public static int[] copy(ByteBuffer data, int width, int height, int stride) {
        if (data == null || width <= 0 || height <= 0 || stride < width) {
            throw new IllegalArgumentException("Invalid ARGB buffer geometry");
        }

        int[] pixels = new int[Math.multiplyExact(width, height)];
        IntBuffer source = data.duplicate()
                .order(ByteOrder.LITTLE_ENDIAN)
                .asIntBuffer();
        for (int y = 0; y < height; y++) {
            int sourceOffset = Math.multiplyExact(y, stride);
            if (sourceOffset + width > source.capacity()) {
                throw new IllegalArgumentException("ARGB buffer is smaller than its geometry");
            }
            source.position(sourceOffset);
            source.get(pixels, y * width, width);
        }
        return pixels;
    }
}
