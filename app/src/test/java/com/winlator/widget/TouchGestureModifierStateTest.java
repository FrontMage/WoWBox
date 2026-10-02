package com.winlator.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TouchGestureModifierStateTest {
    @Test
    public void leftShoulderHeldBeforeTouchLatchesRightClick() {
        TouchGestureModifierState state = new TouchGestureModifierState();

        state.setLeftShoulderPressed(7, true);
        state.beginTouch();
        state.setLeftShoulderPressed(7, false);

        assertTrue(state.shouldRightClickTap());
        assertFalse(state.isLeftShoulderPressed());
    }

    @Test
    public void leftShoulderPressedAfterTouchDoesNotChangeTap() {
        TouchGestureModifierState state = new TouchGestureModifierState();

        state.beginTouch();
        state.setLeftShoulderPressed(7, true);

        assertFalse(state.shouldRightClickTap());
        assertTrue(state.isLeftShoulderPressed());
    }

    @Test
    public void tracksMultipleControllersIndependently() {
        TouchGestureModifierState state = new TouchGestureModifierState();

        state.setLeftShoulderPressed(7, true);
        state.setLeftShoulderPressed(11, true);
        state.setLeftShoulderPressed(7, false);
        state.beginTouch();

        assertTrue(state.isLeftShoulderPressed());
        assertTrue(state.shouldRightClickTap());

        state.setLeftShoulderPressed(11, false);
        assertFalse(state.isLeftShoulderPressed());
    }

    @Test
    public void duplicateKeyEventsAreIdempotent() {
        TouchGestureModifierState state = new TouchGestureModifierState();

        state.setLeftShoulderPressed(7, true);
        state.setLeftShoulderPressed(7, true);
        state.beginTouch();

        assertTrue(state.isLeftShoulderPressed());
        assertTrue(state.shouldRightClickTap());

        state.setLeftShoulderPressed(7, false);
        state.setLeftShoulderPressed(7, false);
        assertFalse(state.isLeftShoulderPressed());
    }

    @Test
    public void cancelTouchPreservesPhysicalButtonState() {
        TouchGestureModifierState state = new TouchGestureModifierState();

        state.setLeftShoulderPressed(7, true);
        state.beginTouch();
        state.cancelTouch();

        assertFalse(state.shouldRightClickTap());
        assertTrue(state.isLeftShoulderPressed());

        state.beginTouch();
        assertTrue(state.shouldRightClickTap());
    }

    @Test
    public void clearDropsPhysicalAndLatchedState() {
        TouchGestureModifierState state = new TouchGestureModifierState();

        state.setLeftShoulderPressed(7, true);
        state.beginTouch();
        state.clear();

        assertFalse(state.isLeftShoulderPressed());
        assertFalse(state.shouldRightClickTap());
    }
}
