package com.winlator.xserver;

import com.winlator.xconnector.XOutputStream;

import java.nio.ByteBuffer;

public final class PictFormat {
    public static final int TYPE_DIRECT = 1;

    public final int id;
    public final int depth;
    public final int redShift;
    public final int redMask;
    public final int greenShift;
    public final int greenMask;
    public final int blueShift;
    public final int blueMask;
    public final int alphaShift;
    public final int alphaMask;

    public PictFormat(
            int id,
            int depth,
            int redShift,
            int redMask,
            int greenShift,
            int greenMask,
            int blueShift,
            int blueMask,
            int alphaShift,
            int alphaMask) {
        this.id = id;
        this.depth = depth;
        this.redShift = redShift;
        this.redMask = redMask;
        this.greenShift = greenShift;
        this.greenMask = greenMask;
        this.blueShift = blueShift;
        this.blueMask = blueMask;
        this.alphaShift = alphaShift;
        this.alphaMask = alphaMask;
    }

    public void writeWire(XOutputStream outputStream) {
        outputStream.writeInt(id);
        outputStream.writeByte((byte)TYPE_DIRECT);
        outputStream.writeByte((byte)depth);
        outputStream.writeShort((short)0);
        outputStream.writeShort((short)redShift);
        outputStream.writeShort((short)redMask);
        outputStream.writeShort((short)greenShift);
        outputStream.writeShort((short)greenMask);
        outputStream.writeShort((short)blueShift);
        outputStream.writeShort((short)blueMask);
        outputStream.writeShort((short)alphaShift);
        outputStream.writeShort((short)alphaMask);
        outputStream.writeInt(0);
    }

    public void writeWire(ByteBuffer output) {
        output.putInt(id);
        output.put((byte)TYPE_DIRECT);
        output.put((byte)depth);
        output.putShort((short)0);
        output.putShort((short)redShift);
        output.putShort((short)redMask);
        output.putShort((short)greenShift);
        output.putShort((short)greenMask);
        output.putShort((short)blueShift);
        output.putShort((short)blueMask);
        output.putShort((short)alphaShift);
        output.putShort((short)alphaMask);
        output.putInt(0);
    }
}
