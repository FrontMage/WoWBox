package com.winlator.xserver.errors;

import com.winlator.xserver.extensions.RenderExtension;

public final class BadPicture extends XRequestError {
    public BadPicture(int id) {
        super(Byte.toUnsignedInt(RenderExtension.FIRST_ERROR) + 1, id);
    }
}
