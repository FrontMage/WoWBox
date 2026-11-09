package com.winlator.box;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/** Cold-process maintenance barrier and one-use Activity admission tokens. */
public final class BoxLaunchLease {
    private static final BoxLaunchLease PROCESS = new BoxLaunchLease();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Set<String> pending = new HashSet<>();
    private boolean launchEverCommitted;
    private Thread maintenanceOwner;
    private int maintenanceDepth;

    public static BoxLaunchLease process() { return PROCESS; }
    public static boolean enabled() { return "com.winlator.llm".equals(com.winlator.BuildConfig.APPLICATION_ID); }

    /** Called before submitting an Activity; a failed submission remains dirty. */
    public synchronized String reserve() {
        if (maintenanceOwner != null) throw new IllegalStateException("release_maintenance_active");
        byte[] random = new byte[24]; RANDOM.nextBytes(random);
        StringBuilder token = new StringBuilder(48);
        for (byte b : random) token.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        String value = token.toString();
        if (!pending.add(value)) throw new IllegalStateException("duplicate_launch_capability");
        launchEverCommitted = true;
        return value;
    }
    public synchronized void cancel(String token) { if (token != null) pending.remove(token); }
    public synchronized boolean claim(String token, Object owner) {
        return owner != null && token != null && maintenanceOwner == null && pending.remove(token);
    }
    /** Call before any direct Wine-start preparation or filesystem mutation. */
    public synchronized void markWineUsed() {
        if (maintenanceOwner != null) throw new IllegalStateException("release_maintenance_active");
        launchEverCommitted = true;
    }
    public synchronized Lease beginMaintenance() {
        Thread current = Thread.currentThread();
        if (maintenanceOwner == current) { maintenanceDepth++; return new Lease(this, current); }
        if (maintenanceOwner != null) throw new IllegalStateException("release_maintenance_active");
        if (launchEverCommitted || !pending.isEmpty()) throw new IllegalStateException("release_restart_required");
        maintenanceOwner = current; maintenanceDepth = 1;
        return new Lease(this, current);
    }
    private synchronized void end(Thread owner) {
        if (owner != maintenanceOwner || Thread.currentThread() != owner || maintenanceDepth <= 0)
            throw new IllegalStateException("invalid_maintenance_owner");
        if (--maintenanceDepth == 0) maintenanceOwner = null;
    }
    public static final class Lease implements AutoCloseable {
        private BoxLaunchLease parent; private final Thread owner;
        private Lease(BoxLaunchLease parent, Thread owner) { this.parent = parent; this.owner = owner; }
        @Override public void close() { BoxLaunchLease p = parent; if (p != null) { p.end(owner); parent = null; } }
    }
}
