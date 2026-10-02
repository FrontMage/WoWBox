package com.winlator.box;

/**
 * Tracks the host-side manual resume gate independently from Android views.
 *
 * <p>The Wine process controller remains the source of truth for whether the
 * guest is paused. This class only guards the UI transition from a resumed
 * Android surface to an explicit user-approved guest resume.</p>
 */
public final class BoxSessionResumeGate {
    public static final long NO_GENERATION = -1L;

    private long generation;
    private boolean hostResumed;
    private boolean waitingForManualResume;
    private boolean surfaceReady;
    private boolean resumeInProgress;
    private boolean shutdown;

    public synchronized long onHostResumed(boolean wineSessionPaused) {
        generation++;
        hostResumed = true;
        waitingForManualResume = wineSessionPaused && !shutdown;
        surfaceReady = false;
        resumeInProgress = false;
        return waitingForManualResume ? generation : NO_GENERATION;
    }

    public synchronized void onHostPaused(boolean wineSessionPaused) {
        generation++;
        hostResumed = false;
        waitingForManualResume = wineSessionPaused && !shutdown;
        surfaceReady = false;
        resumeInProgress = false;
    }

    public synchronized boolean onSurfaceFrame(long expectedGeneration) {
        if (shutdown
                || !hostResumed
                || !waitingForManualResume
                || resumeInProgress
                || generation != expectedGeneration) {
            return false;
        }
        surfaceReady = true;
        return true;
    }

    public synchronized boolean beginManualResume() {
        if (shutdown
                || !hostResumed
                || !waitingForManualResume
                || !surfaceReady
                || resumeInProgress) {
            return false;
        }
        resumeInProgress = true;
        return true;
    }

    public synchronized void completeManualResume() {
        if (!resumeInProgress) return;
        generation++;
        waitingForManualResume = false;
        surfaceReady = false;
        resumeInProgress = false;
    }

    public synchronized void cancelManualResume() {
        if (!resumeInProgress) return;
        resumeInProgress = false;
    }

    public synchronized void shutdown() {
        generation++;
        shutdown = true;
        hostResumed = false;
        waitingForManualResume = false;
        surfaceReady = false;
        resumeInProgress = false;
    }

    public synchronized long getGeneration() {
        return generation;
    }

    public synchronized boolean isWaitingForManualResume() {
        return waitingForManualResume;
    }

    public synchronized boolean isSurfaceReady() {
        return surfaceReady;
    }
}
