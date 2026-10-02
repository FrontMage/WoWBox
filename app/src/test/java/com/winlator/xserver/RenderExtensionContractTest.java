package com.winlator.xserver;

import static org.junit.Assert.assertEquals;

import com.winlator.xserver.errors.BadFence;
import com.winlator.xserver.errors.BadPictFormat;
import com.winlator.xserver.errors.BadPicture;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.RenderExtension;
import com.winlator.xserver.extensions.SyncExtension;

import org.junit.Test;

public class RenderExtensionContractTest {
    @Test
    public void usesNonOverlappingExtensionErrorRanges() {
        assertEquals(128, Byte.toUnsignedInt(new MITSHMExtension().getFirstErrorId()));
        assertEquals(129, Byte.toUnsignedInt(SyncExtension.FIRST_ERROR));
        assertEquals(131, Byte.toUnsignedInt(new BadFence(1).getCode()));
        assertEquals(132, Byte.toUnsignedInt(RenderExtension.FIRST_ERROR));
        assertEquals(132, Byte.toUnsignedInt(new BadPictFormat(1).getCode()));
        assertEquals(133, Byte.toUnsignedInt(new BadPicture(1).getCode()));
        assertEquals(151, Byte.toUnsignedInt(RenderExtension.MAJOR_OPCODE));
    }

    @Test
    public void advertisesRenderProtocolVersionZeroPointEight() {
        assertEquals(0, RenderExtension.SERVER_MAJOR_VERSION);
        assertEquals(8, RenderExtension.SERVER_MINOR_VERSION);
    }
}
