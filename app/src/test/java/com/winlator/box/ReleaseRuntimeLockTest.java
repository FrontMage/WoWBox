package com.winlator.box;
import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ReleaseRuntimeLockTest {
    @Test public void initializesBeforeTakingRuntimeWhenSingletonConstructorAlsoNeedsIt() throws Exception {
        Object runtime = new Object(), singleton = new Object();
        CountDownLatch holdingSingleton = new CountDownLatch(1), attemptingInit = new CountDownLatch(1),
                constructorFinished = new CountDownLatch(1), workFinished = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread initializer = new Thread(() -> {
            synchronized (singleton) {
                holdingSingleton.countDown();
                try { assertTrue(attemptingInit.await(2, TimeUnit.SECONDS)); }
                catch (Throwable t) { error.set(t); return; }
                synchronized (runtime) { constructorFinished.countDown(); }
            }
        });
        Thread migration = new Thread(() -> {
            try {
                ReleaseRuntimeLock.run(runtime, () -> {
                    attemptingInit.countDown(); synchronized (singleton) { /* initialized */ }
                }, () -> workFinished.countDown());
            } catch (Throwable t) { error.set(t); }
        });
        initializer.setDaemon(true); migration.setDaemon(true);
        initializer.start(); assertTrue(holdingSingleton.await(2, TimeUnit.SECONDS)); migration.start();
        assertTrue(constructorFinished.await(2, TimeUnit.SECONDS));
        assertTrue(workFinished.await(2, TimeUnit.SECONDS));
        assertNull(error.get());
    }
    @Test public void commitIsSerializedWithRuntimeWriters() throws Exception {
        Object runtime = new Object();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1),
                writerEntered = new CountDownLatch(1);
        Thread commit = new Thread(() -> {
            try { ReleaseRuntimeLock.run(runtime, () -> {}, () -> {
                entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS));
            }); } catch (Exception e) { throw new AssertionError(e); }
        });
        Thread writer = new Thread(() -> { synchronized (runtime) { writerEntered.countDown(); } });
        commit.setDaemon(true); writer.setDaemon(true); commit.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS)); writer.start();
        assertFalse(writerEntered.await(100, TimeUnit.MILLISECONDS)); release.countDown();
        assertTrue(writerEntered.await(2, TimeUnit.SECONDS));
    }
}
