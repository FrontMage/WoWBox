package com.winlator.box;

/** Initialize lock-taking collaborators before entering the runtime monitor. */
final class ReleaseRuntimeLock {
    interface Work { void run() throws Exception; }
    private ReleaseRuntimeLock() {}
    static void run(Object runtime, Runnable initialize, Work work) throws Exception {
        initialize.run();
        synchronized (runtime) { work.run(); }
    }
}
