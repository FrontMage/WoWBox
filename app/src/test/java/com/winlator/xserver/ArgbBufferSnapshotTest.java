package com.winlator.xserver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ArgbBufferSnapshotTest {
    @Test
    public void copiesFragmentedRowsAndDropsStridePadding() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0 * 4, 0x80102030);
        buffer.putInt(1 * 4, 0xff405060);
        buffer.putInt(2 * 4, 0x00708090);
        buffer.putInt(3 * 4, 0xdeadbeef);

        buffer.putInt(4 * 4, 0xffa0b0c0);
        buffer.putInt(5 * 4, 0x40201008);
        buffer.putInt(6 * 4, 0x00000000);
        buffer.putInt(7 * 4, 0xcafebabe);

        assertArrayEquals(new int[]{
                0x80102030, 0xff405060, 0x00708090,
                0xffa0b0c0, 0x40201008, 0x00000000
        }, ArgbBufferSnapshot.copy(buffer, 3, 2, 4));
    }

    @Test
    public void littleEndianArgbIntIsBgraInMemory() {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x80604020);
        assertEquals(0x20, Byte.toUnsignedInt(buffer.get(0)));
        assertEquals(0x40, Byte.toUnsignedInt(buffer.get(1)));
        assertEquals(0x60, Byte.toUnsignedInt(buffer.get(2)));
        assertEquals(0x80, Byte.toUnsignedInt(buffer.get(3)));
    }
}
