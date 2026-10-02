package com.winlator.xserver;

public final class AnimatedCursor extends Cursor {
    private final StaticCursor[] frames;
    private final long[] delaysMs;
    private final long pixelBytes;

    public AnimatedCursor(int id, StaticCursor[] frames, long[] delaysMs) {
        super(id);
        if (frames == null || delaysMs == null || frames.length == 0 ||
                frames.length != delaysMs.length) {
            throw new IllegalArgumentException("Invalid animated cursor frames");
        }

        this.frames = frames.clone();
        this.delaysMs = delaysMs.clone();
        CursorAnimationTimeline.totalDuration(this.delaysMs);

        long bytes = 0;
        int retained = 0;
        try {
            for (StaticCursor frame : this.frames) {
                if (frame == null) throw new IllegalArgumentException("Animation frame is null");
                frame.retain();
                retained++;
                bytes = Math.addExact(bytes, frame.getPixelBytes());
            }
        }
        catch (RuntimeException | Error error) {
            for (int i = retained - 1; i >= 0; i--) this.frames[i].release();
            throw error;
        }
        pixelBytes = bytes;
    }

    @Override
    public boolean isAnimated() {
        return true;
    }

    @Override
    public long getPixelBytes() {
        return pixelBytes;
    }

    @Override
    public ResolvedFrame resolveFrame(long elapsedMs) {
        CursorAnimationTimeline.Selection selection =
                CursorAnimationTimeline.select(delaysMs, elapsedMs);
        return new ResolvedFrame(
                frames[selection.frameIndex],
                selection.millisUntilNextFrame);
    }

    @Override
    protected void onDispose() {
        for (int i = frames.length - 1; i >= 0; i--) frames[i].release();
    }
}
