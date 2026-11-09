package com.winlator.box;

/** A launch may be cancelled until its first session-affecting operation. */
public final class BoxLaunchRequest {
    private boolean cancelled;
    private boolean committed;

    /** Does not interrupt an already committed launch or its existing session. */
    public synchronized void cancel() {
        if (!committed) cancelled = true;
    }

    /** Called once after expensive verification, before changing session state. */
    public synchronized boolean tryCommit() {
        if (cancelled || committed) return false;
        committed = true;
        return true;
    }

    public synchronized boolean isCommitted() { return committed; }
}
