package com.winlator.xserver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CursorAnimationTimelineTest {
    @Test
    public void selectsFramesUsingUptimeElapsedPhase() {
        long[] delays = {10, 20, 30};
        assertSelection(delays, 0, 0, 10);
        assertSelection(delays, 9, 0, 1);
        assertSelection(delays, 10, 1, 20);
        assertSelection(delays, 29, 1, 1);
        assertSelection(delays, 30, 2, 30);
        assertSelection(delays, 60, 0, 10);
        assertSelection(delays, 75, 1, 15);
    }

    @Test
    public void clampsZeroDelayToOneMillisecond() {
        assertEquals(3, CursorAnimationTimeline.totalDuration(
                new long[]{0, 1, 1}));
        assertSelection(new long[]{0, 5}, 0, 0, 1);
        assertSelection(new long[]{0, 5}, 1, 1, 5);
    }

    private static void assertSelection(
            long[] delays,
            long elapsed,
            int frame,
            long remaining) {
        CursorAnimationTimeline.Selection selection =
                CursorAnimationTimeline.select(delays, elapsed);
        assertEquals(frame, selection.frameIndex);
        assertEquals(remaining, selection.millisUntilNextFrame);
    }
}
