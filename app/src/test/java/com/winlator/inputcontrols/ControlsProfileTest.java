package com.winlator.inputcontrols;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ControlsProfileTest {
    @Test
    public void classificationRecognizesTheExistingGamepadElementContract() {
        assertTrue(ControlsProfile.isVirtualGamepadElement(new Binding[]{Binding.GAMEPAD_BUTTON_A}));
        assertTrue(ControlsProfile.isVirtualGamepadElement(new Binding[]{
                Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_RIGHT,
                Binding.GAMEPAD_LEFT_THUMB_DOWN, Binding.GAMEPAD_LEFT_THUMB_LEFT}));
        assertFalse(ControlsProfile.isVirtualGamepadElement(new Binding[]{Binding.KEY_W, Binding.KEY_A}));
        assertFalse(ControlsProfile.isVirtualGamepadElement(new Binding[]{Binding.GAMEPAD_BUTTON_A, Binding.NONE}));
    }

    @Test
    public void profileStateExistsBeforeAnyElementDrawingOrNetworkRequest() {
        ControlsProfile profile = new ControlsProfile(null, 3);
        GamepadState state = profile.getGamepadState();
        state.setPressed(0, true);

        assertSame(state, profile.getGamepadState());
        assertTrue(profile.getGamepadState().isPressed(0));
    }
}
