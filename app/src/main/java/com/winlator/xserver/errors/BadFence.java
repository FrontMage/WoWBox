package com.winlator.xserver.errors;

import com.winlator.xserver.extensions.SyncExtension;

public class BadFence extends XRequestError {
    public BadFence(int id) {
        super(Byte.toUnsignedInt(SyncExtension.FIRST_ERROR) + 2, id);
    }
}
