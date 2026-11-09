package com.winlator.box;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class BoxLaunchRequestTest {
    @Test public void cancellingWhileVerificationIsBlockedPreventsSessionCommit() throws Exception {
        BoxLaunchRequest request = new BoxLaunchRequest();
        CountDownLatch checking = new CountDownLatch(1), finishChecking = new CountDownLatch(1);
        AtomicBoolean sessionCreated = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            checking.countDown();
            try { finishChecking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            if (request.tryCommit()) sessionCreated.set(true);
        });
        worker.start();
        assertTrue(checking.await(2, TimeUnit.SECONDS));
        request.cancel();
        finishChecking.countDown();
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertFalse(sessionCreated.get());
        assertFalse(request.tryCommit());
    }

    @Test public void cancelAfterCommitDoesNotUndoOrDuplicateLaunch() {
        BoxLaunchRequest request = new BoxLaunchRequest();
        assertTrue(request.tryCommit());
        request.cancel();
        assertFalse(request.tryCommit());
    }

    @Test public void repeatedCancelRemainsCancelled() {
        BoxLaunchRequest request = new BoxLaunchRequest();
        request.cancel(); request.cancel();
        assertFalse(request.tryCommit());
    }

    @Test public void repeatedClicksAndStaleWorkerCannotStartOrClearAnotherRequest() {
        BoxLaunchController controller = new BoxLaunchController();
        BoxLaunchRequest first = controller.begin();
        assertNotNull(first);
        assertNull(controller.begin());
        controller.cancelPending();
        assertFalse(first.tryCommit());
        assertTrue(controller.isPending());
        assertTrue(controller.finish(first));
        BoxLaunchRequest next = controller.begin();
        assertNotNull(next);
        assertFalse(controller.finish(first));
        assertTrue(controller.isPending());
        assertTrue(next.tryCommit());
        controller.cancelPending(); // committed request is not stopped by lifecycle changes
        controller.releaseCommitted();
        assertFalse(controller.isPending());
    }

    @Test public void lifecycleDoesNotReleaseUncommittedRequestUntilWorkerFinishes() {
        BoxLaunchController controller = new BoxLaunchController();
        BoxLaunchRequest request = controller.begin();
        controller.cancelPending();
        controller.releaseCommitted();
        assertTrue(controller.isPending());
        assertFalse(request.tryCommit());
        assertTrue(controller.finish(request));
    }
}
