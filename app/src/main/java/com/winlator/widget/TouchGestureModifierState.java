package com.winlator.widget;

import java.util.HashSet;
import java.util.Set;

final class TouchGestureModifierState {
    private final Set<Integer> leftShoulderDeviceIds = new HashSet<>();
    private boolean leftShoulderAtTouchDown;

    void setLeftShoulderPressed(int deviceId, boolean pressed) {
        if (pressed) {
            leftShoulderDeviceIds.add(deviceId);
        }
        else {
            leftShoulderDeviceIds.remove(deviceId);
        }
    }

    void beginTouch() {
        leftShoulderAtTouchDown = !leftShoulderDeviceIds.isEmpty();
    }

    boolean shouldRightClickTap() {
        return leftShoulderAtTouchDown;
    }

    void cancelTouch() {
        leftShoulderAtTouchDown = false;
    }

    void clear() {
        leftShoulderDeviceIds.clear();
        cancelTouch();
    }

    boolean isLeftShoulderPressed() {
        return !leftShoulderDeviceIds.isEmpty();
    }
}
