package com.winlator.xserver.errors;

import com.winlator.xserver.extensions.RenderExtension;

public final class BadPictFormat extends XRequestError {
    public BadPictFormat(int id) {
        super(Byte.toUnsignedInt(RenderExtension.FIRST_ERROR) + 0, id);
    }
}
