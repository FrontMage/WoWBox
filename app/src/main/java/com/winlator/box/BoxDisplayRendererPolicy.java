package com.winlator.box;

/** Host composition is independent of the guest's Vulkan driver. */
public final class BoxDisplayRendererPolicy {
    private BoxDisplayRendererPolicy() {}

    public static String selectOrdinaryBox(boolean nativeAvailable, boolean boxSession,
                                           boolean targetDevice) {
        return nativeAvailable && boxSession && targetDevice ? "vulkan" : "gl";
    }
}
