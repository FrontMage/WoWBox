package com.winlator.xconnector;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClientCloseState {
    private final AtomicBoolean closeStarted = new AtomicBoolean(false);
    private final CountDownLatch closeFinished = new CountDownLatch(1);

    boolean tryBeginClose() {
        return closeStarted.compareAndSet(false, true);
    }

    void finishClose() {
        closeFinished.countDown();
    }

    void awaitClosed() {
        boolean interrupted = false;
        while (true) {
            try {
                closeFinished.await();
                break;
            }
            catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
