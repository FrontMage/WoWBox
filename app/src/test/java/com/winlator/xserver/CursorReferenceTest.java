package com.winlator.xserver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CursorReferenceTest {
    @Test
    public void disposalWaitsForAllIndependentReferences() {
        FakeCursor cursor = new FakeCursor(99);
        cursor.retain();
        cursor.retain();
        assertEquals(3, cursor.getReferenceCountForTest());

        cursor.release();
        cursor.release();
        assertEquals(0, cursor.disposeCount);
        cursor.release();
        assertEquals(1, cursor.disposeCount);
    }

    private static final class FakeCursor extends Cursor {
        private int disposeCount;

        private FakeCursor(int id) {
            super(id);
        }

        @Override
        public boolean isAnimated() {
            return false;
        }

        @Override
        public long getPixelBytes() {
            return 0;
        }

        @Override
        public ResolvedFrame resolveFrame(long elapsedMs) {
            return null;
        }

        @Override
        protected void onDispose() {
            disposeCount++;
        }
    }
}
