package com.winlator.box;
import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BoxLaunchLeaseTest {
    @Test public void coldProcessCanMaintainAndRestartCreatesNewGeneration() {
        BoxLaunchLease old = new BoxLaunchLease();
        try (BoxLaunchLease.Lease ignored = old.beginMaintenance()) {
            try (BoxLaunchLease.Lease nested = old.beginMaintenance()) {}
        }
        String token = old.reserve(); assertTrue(old.claim(token, new Object()));
        assertFalse(old.claim(token, new Object()));
        try { old.beginMaintenance(); fail(); } catch (IllegalStateException expected) {
            assertEquals("release_restart_required", expected.getMessage());
        }
        BoxLaunchLease restarted = new BoxLaunchLease();
        assertFalse(restarted.claim(token, new Object()));
        try (BoxLaunchLease.Lease ignored = restarted.beginMaintenance()) {}
    }
    @Test public void cancelledOrFailedSubmissionStillRequiresProcessRestart() {
        BoxLaunchLease fence = new BoxLaunchLease(); String token = fence.reserve(); fence.cancel(token);
        assertFalse(fence.claim(token, new Object()));
        try { fence.beginMaintenance(); fail(); } catch (IllegalStateException expected) {
            assertEquals("release_restart_required", expected.getMessage());
        }
    }
    @Test public void sameProcessRecreationGetsOneFreshTokenButProcessDeathInvalidatesIt() {
        BoxLaunchLease process = new BoxLaunchLease();
        String original = process.reserve(); assertTrue(process.claim(original, new Object()));
        String recreation = process.reserve();
        assertTrue(process.claim(recreation, new Object())); assertFalse(process.claim(recreation, new Object()));
        String savedBeforeDeath = process.reserve();
        assertFalse(new BoxLaunchLease().claim(savedBeforeDeath, new Object()));
    }
    @Test public void repeatedSaveResumeAndFinishRevokePriorBundles() {
        BoxLaunchLease process = new BoxLaunchLease();
        assertTrue(process.claim(process.reserve(), new Object()));
        String firstSave = process.reserve();
        process.cancel(firstSave); // a newer save replaces it
        String secondSave = process.reserve();
        assertFalse(process.claim(firstSave, new Object()));
        process.cancel(secondSave); // the original Activity resumes or finishes
        assertFalse(process.claim(secondSave, new Object()));
        String realRecreation = process.reserve();
        assertTrue(process.claim(realRecreation, new Object()));
        assertFalse(process.claim(realRecreation, new Object()));
    }
    @Test public void directWineStartMarksProcessDirtyAndIsRefusedDuringMaintenance() {
        BoxLaunchLease fence = new BoxLaunchLease();
        try (BoxLaunchLease.Lease ignored = fence.beginMaintenance()) {
            try { fence.markWineUsed(); fail(); } catch (IllegalStateException expected) {}
            try { fence.reserve(); fail(); } catch (IllegalStateException expected) {}
            assertFalse(fence.claim("old", new Object()));
        }
        fence.markWineUsed();
        try { fence.beginMaintenance(); fail(); } catch (IllegalStateException expected) {}
    }
    @Test public void heldMaintenanceRejectsLaunchAndDirectWineUntilReleased() throws Exception {
        BoxLaunchLease fence = new BoxLaunchLease();
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        Thread maintainer = new Thread(() -> {
            try (BoxLaunchLease.Lease ignored = fence.beginMaintenance()) {
                held.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) { throw new AssertionError(e); }
            finally { done.countDown(); }
        });
        maintainer.setDaemon(true); maintainer.start(); assertTrue(held.await(2, TimeUnit.SECONDS));
        try { fence.reserve(); fail(); } catch (IllegalStateException expected) {}
        try { fence.markWineUsed(); fail(); } catch (IllegalStateException expected) {}
        assertFalse(fence.claim("old-intent", new Object()));
        release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNotNull(fence.reserve());
        try { fence.beginMaintenance(); fail(); } catch (IllegalStateException expected) {
            assertEquals("release_restart_required", expected.getMessage());
        }
    }
    @Test public void concurrentLaunchAndMaintenanceCannotBothWin() throws Exception {
        for (int i = 0; i < 200; i++) {
            BoxLaunchLease fence = new BoxLaunchLease(); CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(2);
            AtomicBoolean launch = new AtomicBoolean(), maintenance = new AtomicBoolean();
            Thread a = new Thread(() -> { try { start.await(); fence.reserve(); launch.set(true); }
                catch (IllegalStateException expected) {} catch (Exception e) { throw new AssertionError(e); } finally { done.countDown(); } });
            Thread b = new Thread(() -> { try { start.await(); try (BoxLaunchLease.Lease ignored = fence.beginMaintenance()) { maintenance.set(true); Thread.yield(); } }
                catch (IllegalStateException expected) {} catch (Exception e) { throw new AssertionError(e); } finally { done.countDown(); } });
            a.start(); b.start(); start.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS));
            // If maintenance finished first, a later launch is valid. If launch committed first, maintenance cannot start.
            if (launch.get() && maintenance.get()) {
                try { fence.beginMaintenance(); fail(); } catch (IllegalStateException expected) {}
            } else assertTrue(launch.get() || maintenance.get());
        }
    }
}
