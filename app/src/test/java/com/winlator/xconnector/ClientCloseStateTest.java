package com.winlator.xconnector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientCloseStateTest {
    @Test
    public void onlyOneConcurrentCallerOwnsClose() throws Exception {
        ClientCloseState state = new ClientCloseState();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger owners = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 32; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (state.tryBeginClose()) owners.incrementAndGet();
            });
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread thread : threads) thread.join();

        assertEquals(1, owners.get());
        assertFalse(state.tryBeginClose());
    }

    @Test
    public void duplicateCallerWaitsForCloseCompletion() throws Exception {
        ClientCloseState state = new ClientCloseState();
        assertTrue(state.tryBeginClose());

        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterFinished = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            waiterStarted.countDown();
            state.awaitClosed();
            waiterFinished.countDown();
        });
        waiter.start();

        assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));
        assertFalse(waiterFinished.await(50, TimeUnit.MILLISECONDS));
        state.finishClose();
        assertTrue(waiterFinished.await(1, TimeUnit.SECONDS));
        waiter.join();
    }

    @Test
    public void completionIsIdempotent() {
        ClientCloseState state = new ClientCloseState();
        assertTrue(state.tryBeginClose());
        state.finishClose();
        state.finishClose();
        state.awaitClosed();
    }
}
