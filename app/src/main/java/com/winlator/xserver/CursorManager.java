package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.Texture;

import java.nio.ByteOrder;
import java.nio.IntBuffer;

public class CursorManager extends XResourceManager {
    public static final int MAX_CURSOR_DIMENSION = 512;
    public static final int MAX_ANIMATION_FRAMES = 256;
    public static final long MAX_CURSOR_PIXEL_BYTES = 64L * 1024L * 1024L;

    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final XServer xServer;

    public CursorManager(XServer xServer) {
        this.xServer = xServer;
    }

    public Cursor getCursor(int id) {
        return cursors.get(id);
    }

    public StaticCursor getStaticCursor(int id) {
        Cursor cursor = getCursor(id);
        return cursor instanceof StaticCursor ? (StaticCursor)cursor : null;
    }

    public StaticCursor createCoreCursor(
            int id,
            int hotSpotX,
            int hotSpotY,
            Pixmap sourcePixmap,
            Pixmap maskPixmap,
            int foreRed,
            int foreGreen,
            int foreBlue,
            int backRed,
            int backGreen,
            int backBlue) {
        if (cursors.indexOfKey(id) >= 0) return null;

        Drawable source = sourcePixmap.drawable;
        Drawable mask = maskPixmap != null ? maskPixmap.drawable : null;
        int width = Short.toUnsignedInt(source.width);
        int height = Short.toUnsignedInt(source.height);
        validateStaticGeometry(width, height);
        Drawable image = new Drawable(
                0,
                width,
                height,
                source.visual);
        image.drawAlphaMaskedBitmap(
                foreRed,
                foreGreen,
                foreBlue,
                backRed,
                backGreen,
                backBlue,
                source,
                mask);

        boolean visible = mask == null || !isEmptyMaskImage(mask);
        StaticCursor cursor = createStaticCursorInternal(
                id, hotSpotX, hotSpotY, image, visible);
        registerCursor(cursor);
        return cursor;
    }

    public StaticCursor createRenderCursor(
            int id,
            int hotSpotX,
            int hotSpotY,
            int width,
            int height,
            int[] argbPremultiplied) {
        if (cursors.indexOfKey(id) >= 0) return null;
        validateStaticGeometry(width, height);
        long pixelBytes = checkedPixelBytes(width, height);
        if (pixelBytes > MAX_CURSOR_PIXEL_BYTES) {
            throw new IllegalArgumentException("Cursor exceeds pixel budget");
        }

        Drawable image = Drawable.fromArgbPremultiplied(
                width, height, argbPremultiplied.clone());
        StaticCursor cursor = createStaticCursorInternal(
                id, hotSpotX, hotSpotY, image, true);
        registerCursor(cursor);
        return cursor;
    }

    public AnimatedCursor createAnimatedCursor(
            int id,
            StaticCursor[] frames,
            long[] delaysMs) {
        if (cursors.indexOfKey(id) >= 0) return null;
        if (frames.length == 0 || frames.length > MAX_ANIMATION_FRAMES) {
            throw new IllegalArgumentException("Invalid animation frame count");
        }

        long totalBytes = 0;
        for (StaticCursor frame : frames) {
            if (frame == null) throw new IllegalArgumentException("Animation frame is null");
            totalBytes = Math.addExact(totalBytes, frame.getPixelBytes());
            if (totalBytes > MAX_CURSOR_PIXEL_BYTES) {
                throw new IllegalArgumentException("Animated cursor exceeds pixel budget");
            }
        }

        AnimatedCursor cursor = new AnimatedCursor(id, frames, delaysMs);
        registerCursor(cursor);
        return cursor;
    }

    public Cursor freeCursor(int id) {
        Cursor cursor = cursors.get(id);
        if (cursor == null) return null;
        triggerOnFreeResourceListener(cursor);
        cursors.remove(id);
        cursor.release();
        return cursor;
    }

    public static void validateStaticGeometry(int width, int height) {
        if (width <= 0 || height <= 0 ||
                width > MAX_CURSOR_DIMENSION || height > MAX_CURSOR_DIMENSION) {
            throw new IllegalArgumentException("Invalid cursor dimensions");
        }
    }

    public static long checkedPixelBytes(int width, int height) {
        return Math.multiplyExact(Math.multiplyExact((long)width, (long)height), 4L);
    }

    private void registerCursor(Cursor cursor) {
        cursors.put(cursor.id, cursor);
        triggerOnCreateResourceListener(cursor);
    }

    private StaticCursor createStaticCursorInternal(
            int id,
            int hotSpotX,
            int hotSpotY,
            Drawable image,
            boolean visible) {
        return new StaticCursor(
                id,
                hotSpotX,
                hotSpotY,
                image,
                visible,
                () -> destroyCursorTexture(image));
    }

    private void destroyCursorTexture(Drawable image) {
        Texture texture = image.getTexture();
        if (texture == null || !texture.isAllocated()) return;
        GLRenderer renderer = xServer.getRenderer();
        if (renderer != null) renderer.xServerView.queueEvent(texture::destroy);
    }

    private static boolean isEmptyMaskImage(Drawable maskImage) {
        IntBuffer maskData = maskImage.getData().duplicate()
                .order(ByteOrder.LITTLE_ENDIAN)
                .asIntBuffer();
        while (maskData.hasRemaining()) {
            if ((maskData.get() & 0x00ffffff) != 0) return false;
        }
        return true;
    }
}
