package com.winlator.xserver;

import android.util.SparseArray;

public final class PictureManager extends XResourceManager {
    private final SparseArray<Picture> pictures = new SparseArray<>();

    public Picture getPicture(int id) {
        return pictures.get(id);
    }

    public Picture createPicture(
            int id,
            Drawable drawable,
            PictFormat format) {
        if (pictures.indexOfKey(id) >= 0) return null;
        int width = Short.toUnsignedInt(drawable.width);
        int height = Short.toUnsignedInt(drawable.height);
        Picture picture = new Picture(
                id,
                width,
                height,
                format,
                RenderPixelCodec.toPremultipliedArgb(
                        format,
                        drawable.snapshotArgbPremultiplied()));
        pictures.put(id, picture);
        triggerOnCreateResourceListener(picture);
        return picture;
    }

    public Picture freePicture(int id) {
        Picture picture = pictures.get(id);
        if (picture == null) return null;
        triggerOnFreeResourceListener(picture);
        pictures.remove(id);
        return picture;
    }
}
