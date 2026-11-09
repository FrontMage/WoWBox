package com.winlator.core;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class StrictWineIdleScannerTest {
    @Test public void soleCurrentHostProcessIsIdle() {
        Fake r = new Fake();
        assertEquals(StrictWineIdleScanner.Result.IDLE, StrictWineIdleScanner.scan(r));
    }
    @Test public void idleOnlyForCompleteVisibleScan() {
        Fake r = new Fake(); r.add(12, 1000, "android.host", 'S');
        assertEquals(StrictWineIdleScanner.Result.IDLE, StrictWineIdleScanner.scan(r));
    }
    @Test public void missingSelfMeansIncompleteEnumeration() {
        Fake r = new Fake(); r.omitSelf = true;
        assertEquals(StrictWineIdleScanner.Result.UNKNOWN, StrictWineIdleScanner.scan(r));
    }
    @Test public void enumerationFailureIsUnknown() {
        Fake r = new Fake(); r.fail = "pids";
        assertEquals(StrictWineIdleScanner.Result.UNKNOWN, StrictWineIdleScanner.scan(r));
    }
    @Test public void unreadableOwnPidIsUnknown() {
        Fake r = new Fake(); r.add(12, 1000, "android.host", 'S'); r.fail = "cmdline";
        assertEquals(StrictWineIdleScanner.Result.UNKNOWN, StrictWineIdleScanner.scan(r));
    }
    @Test public void inaccessibleForeignProcessesAreNotOpened() {
        Fake r = new Fake(); r.add(12, 2000, "other", 'S'); r.fail = "cmdline";
        assertEquals(StrictWineIdleScanner.Result.IDLE, StrictWineIdleScanner.scan(r));
    }
    @Test public void ownerReadFailureDoesNotBecomeIdle() {
        Fake r = new Fake(); r.add(12, 1000, "other", 'S'); r.fail = "owner";
        assertEquals(StrictWineIdleScanner.Result.UNKNOWN, StrictWineIdleScanner.scan(r));
    }
    @Test public void detectedWineAndMappedWineBothBlock() {
        Fake r = new Fake(); r.add(12, 1000, "C:\\Games\\WoW.exe", 'S');
        assertEquals(StrictWineIdleScanner.Result.ACTIVE, StrictWineIdleScanner.scan(r));
        r.rows.get(12).command = "native-helper"; r.rows.get(12).mapped = true;
        assertEquals(StrictWineIdleScanner.Result.ACTIVE, StrictWineIdleScanner.scan(r));
    }
    @Test public void mapsReadFailureIsUnknown() {
        Fake r = new Fake(); r.add(12, 1000, "native-helper", 'S'); r.fail = "maps";
        assertEquals(StrictWineIdleScanner.Result.UNKNOWN, StrictWineIdleScanner.scan(r));
    }
    @Test public void exitedProcessMayDisappear() {
        Fake r = new Fake(); r.add(12, 1000, "native-helper", 'S'); r.disappear = true;
        assertEquals(StrictWineIdleScanner.Result.IDLE, StrictWineIdleScanner.scan(r));
    }
    @Test public void reusedPidIsUnknown() {
        Fake r = new Fake(); r.add(12, 1000, "native-helper", 'S'); r.reuse = true;
        assertEquals(StrictWineIdleScanner.Result.UNKNOWN, StrictWineIdleScanner.scan(r));
    }
    @Test public void zombieLeaderWithLiveSiblingIsUnknown() {
        Fake r = new Fake(); r.add(12, 1000, "", 'Z'); r.add(13, 1000, "", 'S');
        r.rows.get(12).tasks = Arrays.asList(12, 13);
        assertEquals(StrictWineIdleScanner.Result.UNKNOWN, StrictWineIdleScanner.scan(r));
    }
    @Test public void taskEnumerationFailureIsUnknown() {
        Fake r = new Fake(); r.add(12, 1000, "native-helper", 'S'); r.fail = "tasks";
        assertEquals(StrictWineIdleScanner.Result.UNKNOWN, StrictWineIdleScanner.scan(r));
    }
    @Test public void deadZombieWithoutSiblingsIsIdle() {
        Fake r = new Fake(); r.add(12, 1000, "", 'Z');
        assertEquals(StrictWineIdleScanner.Result.IDLE, StrictWineIdleScanner.scan(r));
    }
    @Test public void secondStatFailureIsUnknownWhenPidStillExists() {
        Fake r = new Fake(); r.add(12, 1000, "native-helper", 'S'); r.fail = "finalstat";
        assertEquals(StrictWineIdleScanner.Result.UNKNOWN, StrictWineIdleScanner.scan(r));
    }

    private static final class Row {
        int uid; String command; char state; long start = 10; boolean mapped;
        List<Integer> tasks;
    }
    private static final class Fake implements StrictWineIdleScanner.Reader {
        final Map<Integer, Row> rows = new HashMap<>();
        String fail = ""; boolean disappear, reuse, omitSelf; int statCalls;
        void add(int pid, int uid, String command, char state) {
            Row row = new Row(); row.uid = uid; row.command = command; row.state = state;
            row.tasks = Arrays.asList(pid); rows.put(pid, row);
        }
        void maybeFail(String op) throws IOException { if (fail.equals(op)) throw new IOException("injected " + op); }
        @Override public int uid() { return 1000; }
        @Override public int selfPid() { return 10; }
        @Override public List<Integer> pids() throws IOException {
            maybeFail("pids"); List<Integer> ids = new ArrayList<>(rows.keySet());
            if (!omitSelf) ids.add(10); return ids;
        }
        @Override public int owner(int pid) throws IOException { maybeFail("owner"); return rows.get(pid).uid; }
        @Override public StrictWineIdleScanner.ProcStat stat(int pid) throws IOException {
            if (disappear && statCalls++ > 0) throw new NoSuchFileException("/proc/" + pid);
            if (fail.equals("finalstat") && statCalls++ > 0) throw new IOException("injected stat");
            Row r = rows.get(pid);
            if (r == null) throw new NoSuchFileException("/proc/" + pid);
            long start = r.start;
            if (reuse && statCalls++ > 0) start++;
            return new StrictWineIdleScanner.ProcStat(start, r.state);
        }
        @Override public String cmdline(int pid) throws IOException { maybeFail("cmdline"); return rows.get(pid).command; }
        @Override public String comm(int pid) throws IOException { maybeFail("comm"); return rows.get(pid).command; }
        @Override public List<Integer> tasks(int pid) throws IOException { maybeFail("tasks"); return rows.get(pid).tasks; }
        @Override public boolean mapsWine(int pid) throws IOException { maybeFail("maps"); return rows.get(pid).mapped; }
    }
}
