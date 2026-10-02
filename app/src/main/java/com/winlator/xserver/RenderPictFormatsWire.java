package com.winlator.xserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class RenderPictFormatsWire {
    private RenderPictFormatsWire() {}

    public static ByteBuffer encode(
            RenderPictFormats formats,
            int rootVisualId,
            ByteOrder byteOrder) {
        ByteBuffer payload = ByteBuffer.allocate(128).order(byteOrder);
        formats.argb32.writeWire(payload);
        formats.rgb24.writeWire(payload);
        formats.a1.writeWire(payload);

        payload.putInt(3);
        payload.putInt(formats.rgb24.id);

        writeDepth(payload, 1, 0);
        writeDepth(payload, 24, 0);
        writeDepth(payload, 32, 1);
        payload.putInt(rootVisualId);
        payload.putInt(formats.argb32.id);

        payload.putInt(0);
        if (payload.position() != 128) {
            throw new IllegalStateException(
                    "Unexpected QueryPictFormats payload size " + payload.position());
        }
        payload.flip();
        return payload;
    }

    private static void writeDepth(ByteBuffer payload, int depth, int visualCount) {
        payload.put((byte)depth);
        payload.put((byte)0);
        payload.putShort((short)visualCount);
        payload.putInt(0);
    }
}
