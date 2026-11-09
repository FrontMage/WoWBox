package com.winlator.box;

/** The runtime-update prompt takes priority; otherwise expose a verified fallback. */
public final class GpuFallbackNoticePolicy {
    private GpuFallbackNoticePolicy() {}
    public static boolean showFallback(boolean fallback, boolean updateRequired, boolean upgrading) {
        return fallback && !updateRequired && !upgrading;
    }
    public static boolean showBlocked(boolean blocked, boolean updateRequired, boolean upgrading) {
        return blocked && !updateRequired && !upgrading;
    }
}
