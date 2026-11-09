package com.winlator.box;

/** Own exactly one GUI launch while its background verification runs. */
public final class BoxLaunchController {
    private BoxLaunchRequest pending;

    public synchronized BoxLaunchRequest begin() {
        if (pending != null) return null;
        pending = new BoxLaunchRequest();
        return pending;
    }

    public synchronized boolean isPending() { return pending != null; }

    public synchronized void cancelPending() {
        if (pending != null) pending.cancel();
    }

    public synchronized void releaseCommitted() {
        if (pending != null && pending.isCommitted()) pending = null;
    }

    /** An old worker must never clear a newer launch. */
    public synchronized boolean finish(BoxLaunchRequest request) {
        if (request == null || pending != request) return false;
        pending = null;
        return true;
    }
}
