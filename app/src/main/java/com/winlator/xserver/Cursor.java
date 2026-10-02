package com.winlator.xserver;

public abstract class Cursor extends XResource {
    public static final class ResolvedFrame {
        public final StaticCursor cursor;
        public final long millisUntilNextFrame;

        public ResolvedFrame(StaticCursor cursor, long millisUntilNextFrame) {
            this.cursor = cursor;
            this.millisUntilNextFrame = millisUntilNextFrame;
        }
    }

    private int referenceCount = 1;
    private boolean disposed;

    protected Cursor(int id) {
        super(id);
    }

    public final synchronized void retain() {
        if (disposed) throw new IllegalStateException("Cannot retain a disposed cursor");
        referenceCount++;
    }

    public final void release() {
        boolean disposeNow = false;
        synchronized (this) {
            if (referenceCount <= 0) throw new IllegalStateException("Cursor reference underflow");
            referenceCount--;
            if (referenceCount == 0) {
                disposed = true;
                disposeNow = true;
            }
        }
        if (disposeNow) onDispose();
    }

    synchronized int getReferenceCountForTest() {
        return referenceCount;
    }

    public abstract boolean isAnimated();

    public abstract long getPixelBytes();

    public abstract ResolvedFrame resolveFrame(long elapsedMs);

    protected abstract void onDispose();
}
