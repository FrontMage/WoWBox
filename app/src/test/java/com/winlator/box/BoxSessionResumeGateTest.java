package com.winlator.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BoxSessionResumeGateTest {
    @Test
    public void firstResumeWithoutPausedGuestDoesNotShowGate() {
        BoxSessionResumeGate gate = new BoxSessionResumeGate();

        assertEquals(BoxSessionResumeGate.NO_GENERATION, gate.onHostResumed(false));
        assertFalse(gate.isWaitingForManualResume());
        assertFalse(gate.isSurfaceReady());
        assertFalse(gate.beginManualResume());
    }

    @Test
    public void pausedGuestWaitsForSurfaceAndExplicitResume() {
        BoxSessionResumeGate gate = new BoxSessionResumeGate();

        gate.onHostPaused(true);
        long generation = gate.onHostResumed(true);

        assertTrue(gate.isWaitingForManualResume());
        assertFalse(gate.beginManualResume());
        assertTrue(gate.onSurfaceFrame(generation));
        assertTrue(gate.isSurfaceReady());
        assertTrue(gate.beginManualResume());
        assertFalse(gate.beginManualResume());

        gate.completeManualResume();
        assertFalse(gate.isWaitingForManualResume());
        assertFalse(gate.isSurfaceReady());
    }

    @Test
    public void rapidPauseResumeRejectsStaleSurfaceCallbacks() {
        BoxSessionResumeGate gate = new BoxSessionResumeGate();

        gate.onHostPaused(true);
        long firstGeneration = gate.onHostResumed(true);
        gate.onHostPaused(true);
        long secondGeneration = gate.onHostResumed(true);
        gate.onHostPaused(true);
        long thirdGeneration = gate.onHostResumed(true);

        assertFalse(gate.onSurfaceFrame(firstGeneration));
        assertFalse(gate.onSurfaceFrame(secondGeneration));
        assertFalse(gate.isSurfaceReady());
        assertTrue(gate.onSurfaceFrame(thirdGeneration));
        assertTrue(gate.beginManualResume());
    }

    @Test
    public void activityRecreationRestoresGateFromControllerPauseState() {
        BoxSessionResumeGate recreatedGate = new BoxSessionResumeGate();

        long generation = recreatedGate.onHostResumed(true);

        assertTrue(generation > 0);
        assertTrue(recreatedGate.isWaitingForManualResume());
    }

    @Test
    public void anotherPauseCancelsAnInProgressResume() {
        BoxSessionResumeGate gate = new BoxSessionResumeGate();

        gate.onHostPaused(true);
        long generation = gate.onHostResumed(true);
        assertTrue(gate.onSurfaceFrame(generation));
        assertTrue(gate.beginManualResume());

        gate.onHostPaused(true);

        assertTrue(gate.isWaitingForManualResume());
        assertFalse(gate.isSurfaceReady());
        assertFalse(gate.beginManualResume());
    }

    @Test
    public void shutdownInvalidatesCallbacksAndResumeRequests() {
        BoxSessionResumeGate gate = new BoxSessionResumeGate();

        gate.onHostPaused(true);
        long generation = gate.onHostResumed(true);
        gate.shutdown();

        assertFalse(gate.onSurfaceFrame(generation));
        assertFalse(gate.beginManualResume());
        assertFalse(gate.isWaitingForManualResume());
    }
}
