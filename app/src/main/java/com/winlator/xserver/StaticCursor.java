package com.winlator.xserver;

public final class StaticCursor extends Cursor {
    public final int hotSpotX;
    public final int hotSpotY;
    public final Drawable cursorImage;
    private final boolean visible;
    private final long pixelBytes;
    private final Runnable disposeCallback;

    public StaticCursor(
            int id,
            int hotSpotX,
            int hotSpotY,
            Drawable cursorImage,
            boolean visible,
            Runnable disposeCallback) {
        super(id);
        if (cursorImage == null) throw new IllegalArgumentException("cursorImage cannot be null");
        this.hotSpotX = hotSpotX;
        this.hotSpotY = hotSpotY;
        this.cursorImage = cursorImage;
        this.visible = visible;
        this.pixelBytes = Math.multiplyExact(
                Math.multiplyExact(
                        (long)Short.toUnsignedInt(cursorImage.width),
                        (long)Short.toUnsignedInt(cursorImage.height)),
                4L);
        this.disposeCallback = disposeCallback;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    public boolean isAnimated() {
        return false;
    }

    @Override
    public long getPixelBytes() {
        return pixelBytes;
    }

    @Override
    public ResolvedFrame resolveFrame(long elapsedMs) {
        return new ResolvedFrame(this, -1);
    }

    @Override
    protected void onDispose() {
        if (disposeCallback != null) disposeCallback.run();
    }
}
