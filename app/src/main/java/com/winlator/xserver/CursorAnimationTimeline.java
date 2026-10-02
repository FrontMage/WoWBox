package com.winlator.xserver;

public final class CursorAnimationTimeline {
    public static final class Selection {
        public final int frameIndex;
        public final long millisUntilNextFrame;

        private Selection(int frameIndex, long millisUntilNextFrame) {
            this.frameIndex = frameIndex;
            this.millisUntilNextFrame = millisUntilNextFrame;
        }
    }

    private CursorAnimationTimeline() {}

    public static long effectiveDelay(long unsignedDelayMs) {
        if (unsignedDelayMs < 0 || unsignedDelayMs > 0xffffffffL) {
            throw new IllegalArgumentException("Animation delay is not CARD32");
        }
        return Math.max(1L, unsignedDelayMs);
    }

    public static long totalDuration(long[] delaysMs) {
        if (delaysMs == null || delaysMs.length == 0) {
            throw new IllegalArgumentException("Animation must contain at least one frame");
        }

        long total = 0;
        for (long delay : delaysMs) total = Math.addExact(total, effectiveDelay(delay));
        return total;
    }

    public static Selection select(long[] delaysMs, long elapsedMs) {
        long total = totalDuration(delaysMs);
        long position = Math.floorMod(Math.max(0, elapsedMs), total);
        for (int i = 0; i < delaysMs.length; i++) {
            long delay = effectiveDelay(delaysMs[i]);
            if (position < delay) return new Selection(i, delay - position);
            position -= delay;
        }
        throw new IllegalStateException("Animation timeline selection failed");
    }
}
