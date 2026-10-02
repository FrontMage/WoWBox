package com.winlator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WineSessionProcessControllerTest {
    private static final int SIGKILL = 9;
    private static final int SIGCONT = 18;
    private static final int SIGSTOP = 19;

    @Test
    public void processFilterAcceptsWineAndExeButRejectsPulseAudio() {
        assertTrue(WineSessionProcessController.isWineGuestProcess(
                "/opt/wine/bin/wineserver", "wineserver"));
        assertTrue(WineSessionProcessController.isWineGuestProcess(
                "C:\\Games\\WOW.EXE", "wow.exe"));
        assertFalse(WineSessionProcessController.isWineGuestProcess(
                "/system/bin/app_process com.winlator.llm", "com.winlator.llm"));
        assertFalse(WineSessionProcessController.isWineGuestProcess(
                "/system/bin/app_process", "wine64"));
        assertFalse(WineSessionProcessController.isWineGuestProcess(
                "/usr/bin/pulseaudio --daemonize=no wine-helper", "pulseaudio"));
    }

    @Test
    public void wineMapEvidenceUsesOnlyNarrowRuntimeAndPrefixMarkers() {
        assertTrue(WineSessionProcessController.isWineMappingLine(
                "7000-8000 r-xp /usr/lib/wine/i386-windows/ntdll.dll"));
        assertTrue(WineSessionProcessController.isWineMappingLine(
                "7000-8000 r-xp /data/prefix/.wine/drive_c/Games/Wow.exe"));
        assertTrue(WineSessionProcessController.isWineMappingLine(
                "7000-8000 r-xp /opt/proton-11/lib/wine/x86_64-unix/ntdll.so"));
        assertTrue(WineSessionProcessController.isWineMappingLine(
                "7000-8000 r-xp /tmp/libarm64ecfex.dll"));
        assertTrue(WineSessionProcessController.isWineMappingLine(
                "7000-8000 r-xp /tmp/WOW64FEX.dll"));
        assertTrue(WineSessionProcessController.isWineMappingLine(
                "7000-8000 r-xp /usr/bin/wowbox64"));
        assertFalse(WineSessionProcessController.isWineMappingLine(
                "7000-8000 r-xp /system/lib64/libandroid_runtime.so"));
    }

    @Test
    public void pauseFiltersUidHostPulseAndDuplicates() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(10, 1000, "com.winlator.llm", "com.winlator.llm", 'S');
        proc.add(20, 1000, "/opt/wine/bin/wineserver", "wineserver", 'S');
        proc.addDuplicate(20);
        proc.add(30, 2000, "/opt/wine/bin/wine64 game.exe", "wine64", 'S');
        proc.add(40, 1000, "/usr/bin/pulseaudio helper.exe", "pulseaudio", 'S');
        proc.add(50, 1000, "Z:\\Games\\Wow.exe", "Wow.exe", 'S');
        proc.add(60, 1000, "/system/bin/surfaceflinger", "surfaceflinger", 'S');
        proc.addUnreadable(70);

        RecordingSignalSender signals = new RecordingSignalSender();
        FakeScheduler scheduler = new FakeScheduler();
        WineSessionProcessController controller = controller(proc, signals, scheduler);

        WineSessionProcessController.PauseResult result =
                controller.pauseAllWineProcesses();

        assertTrue(controller.isPaused());
        assertEquals(2, result.getInitialProcessCount());
        assertEquals(2, result.getStopSignalCount());
        assertFalse(result.wasAlreadyPaused());
        assertEquals(Arrays.asList("20:19", "50:19"), signals.events);
    }

    @Test
    public void activeWineProcessGateUsesTheSameUidFilteredProcessSet() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "C:\\Games\\WowClassic.exe", "WowClassic.exe", 'S');
        proc.add(30, 2000, "C:\\Games\\Other.exe", "Other.exe", 'S');
        WineSessionProcessController controller =
                controller(proc, new RecordingSignalSender(), new FakeScheduler());

        assertTrue(controller.hasActiveWineProcesses());
        proc.remove(20);
        assertFalse(controller.hasActiveWineProcesses());
    }

    @Test
    public void activeGateFindsZombieExeLeaderWithLiveSibling() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "", "WoWClassic.exe", 'Z');
        proc.addThread(20, 21, 1000, "", "worker", 'S');
        WineSessionProcessController controller =
                controller(proc, new RecordingSignalSender(), new FakeScheduler());

        assertTrue(controller.hasActiveWineProcesses());
        proc.setState(21, 'Z');
        assertFalse(controller.hasActiveWineProcesses());
    }

    @Test
    public void activeGateRequiresWineMapForNonExeZombieLeader() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "", "CrGpuMain", 'Z');
        proc.addThread(20, 21, 1000, "", "Chrome_IOThread", 'S');
        WineSessionProcessController controller =
                controller(proc, new RecordingSignalSender(), new FakeScheduler());

        assertFalse(controller.hasActiveWineProcesses());
        proc.setWineMappingEvidence(21, true);
        assertTrue(controller.hasActiveWineProcesses());
    }

    @Test
    public void sameUidNonWineZombieGroupWithLiveSiblingIsNotCandidate() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "", "RenderThread", 'Z');
        proc.addThread(20, 21, 1000, "", "worker", 'S');
        WineSessionProcessController controller =
                controller(proc, new RecordingSignalSender(), new FakeScheduler());

        assertFalse(controller.hasActiveWineProcesses());
    }

    @Test
    public void emptyExeLeaderWithoutZombieOrLiveSiblingIsNotCandidate() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "", "unrelated.exe", 'S');
        proc.addThread(20, 21, 1000, "", "worker", 'S');
        proc.add(30, 1000, "", "finished.exe", 'Z');
        WineSessionProcessController controller =
                controller(proc, new RecordingSignalSender(), new FakeScheduler());

        assertFalse(controller.hasActiveWineProcesses());
    }

    @Test
    public void ordinaryWineZombieWithoutLiveThreadsIsNotActive() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "/opt/wine/bin/wineserver", "wineserver", 'Z');
        WineSessionProcessController controller =
                controller(proc, new RecordingSignalSender(), new FakeScheduler());

        assertFalse(controller.hasActiveWineProcesses());
    }

    @Test
    public void forceKillSignalsEveryLiveThreadButNotZombiePulseHostOrOtherUid() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(10, 1000, "wine host.exe", "host.exe", 'S');
        proc.addThread(10, 11, 1000, "wine host.exe", "host-worker", 'S');
        proc.add(20, 1000, "C:\\Games\\Wow.exe", "Wow.exe", 'S');
        proc.addThread(20, 21, 1000, "C:\\Games\\Wow.exe", "render", 'T');
        proc.addThread(20, 22, 1000, "C:\\Games\\Wow.exe", "finished", 'Z');
        proc.add(30, 1000, "", "plugplay.exe", 'Z');
        proc.addThread(30, 31, 1000, "", "service", 'S');
        proc.add(35, 1000, "", "CrGpuMain", 'Z');
        proc.addThread(35, 36, 1000, "", "Chrome_IOThread", 'S');
        proc.setWineMappingEvidence(36, true);
        proc.add(40, 1000, "/usr/bin/pulseaudio helper.exe", "pulseaudio", 'S');
        proc.addThread(40, 41, 1000, "/usr/bin/pulseaudio helper.exe", "audio", 'S');
        proc.add(50, 2000, "C:\\Games\\Other.exe", "Other.exe", 'S');
        proc.addThread(50, 51, 2000, "C:\\Games\\Other.exe", "other", 'S');

        RecordingSignalSender signals = new RecordingSignalSender();
        WineSessionProcessController controller =
                controller(proc, signals, new FakeScheduler());

        WineSessionProcessController.ForceKillResult result =
                controller.forceKillWineProcesses();

        assertEquals(3, result.getCandidateProcessCount());
        assertEquals(4, result.getLiveThreadCount());
        assertEquals(4, result.getKillSignalCount());
        assertEquals(Arrays.asList(
                "20:" + SIGKILL,
                "21:" + SIGKILL,
                "31:" + SIGKILL,
                "36:" + SIGKILL
        ), signals.events);
    }

    @Test
    public void forceKillInvalidatesPauseAndPendingVerification() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "C:\\Games\\Wow.exe", "Wow.exe", 'S');
        RecordingSignalSender signals = new RecordingSignalSender();
        FakeScheduler scheduler = new FakeScheduler();
        WineSessionProcessController controller = controller(proc, signals, scheduler);

        controller.pauseAllWineProcesses();
        WineSessionProcessController.ForceKillResult result =
                controller.forceKillWineProcesses();
        scheduler.forceRunLatest();

        assertFalse(controller.isPaused());
        assertTrue(scheduler.latest.cancelled);
        assertEquals(1, result.getLiveThreadCount());
        assertEquals(Arrays.asList("20:" + SIGSTOP, "20:" + SIGKILL), signals.events);
    }

    @Test
    public void forceKillRevalidatesUidTgidStartTimesAndLiveState() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        addZombieGroup(proc, 20, 21);
        addZombieGroup(proc, 30, 31);
        addZombieGroup(proc, 40, 41);
        addZombieGroup(proc, 50, 51);
        addZombieGroup(proc, 60, 61);
        addZombieGroup(proc, 70, 71);

        RecordingSignalSender signals = new RecordingSignalSender();
        signals.onFirstSignal = () -> {
            proc.setUid(31, 2000);
            proc.setThreadGroupId(41, 4000);
            proc.bumpStartTime(51);
            proc.setState(61, 'Z');
            proc.bumpStartTime(70);
        };
        WineSessionProcessController controller =
                controller(proc, signals, new FakeScheduler());

        WineSessionProcessController.ForceKillResult result =
                controller.forceKillWineProcesses();

        assertEquals(6, result.getCandidateProcessCount());
        assertEquals(6, result.getLiveThreadCount());
        assertEquals(1, result.getKillSignalCount());
        assertEquals(Arrays.asList("21:" + SIGKILL), signals.events);
    }

    @Test
    public void verifierConfirmsStoppedRetriesAndSweepsLateProcesses() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "/opt/wine/bin/wineserver", "wineserver", 'T');
        proc.add(21, 1000, "C:\\Games\\Agent.exe", "Agent.exe", 'R');

        RecordingSignalSender signals = new RecordingSignalSender();
        FakeScheduler scheduler = new FakeScheduler();
        WineSessionProcessController controller = controller(proc, signals, scheduler);
        List<WineSessionProcessController.VerificationResult> reports = new ArrayList<>();
        controller.setEventListener(reports::add);

        controller.pauseAllWineProcesses();
        proc.add(30, 1000, "C:\\Games\\Wow.exe", "Wow.exe", 'R');
        scheduler.runLatest();

        assertEquals(Arrays.asList(
                "20:19", "21:19",
                "21:19", "30:19"
        ), signals.events);
        assertEquals(1, reports.size());
        WineSessionProcessController.VerificationResult report = reports.get(0);
        assertEquals(2, report.getInitialProcessCount());
        assertEquals(2, report.getInitialStopSignalCount());
        assertEquals(1, report.getVerifiedStoppedCount());
        assertEquals(1, report.getRetrySignalCount());
        assertEquals(1, report.getLateProcessCount());
        assertEquals(1, report.getLateStopSignalCount());
        assertEquals(3, report.getTrackedProcessCount());
    }

    @Test
    public void quickResumeCancelsStalePauseVerification() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "C:\\Games\\Wow.exe", "Wow.exe", 'R');
        RecordingSignalSender signals = new RecordingSignalSender();
        FakeScheduler scheduler = new FakeScheduler();
        WineSessionProcessController controller = controller(proc, signals, scheduler);
        List<WineSessionProcessController.VerificationResult> reports = new ArrayList<>();
        controller.setEventListener(reports::add);

        controller.pauseAllWineProcesses();
        int resumed = controller.resumeAllWineProcesses();
        scheduler.forceRunLatest();

        assertFalse(controller.isPaused());
        assertEquals(1, resumed);
        assertEquals(Arrays.asList("20:19", "20:18"), signals.events);
        assertTrue(scheduler.latest.cancelled);
        assertTrue(reports.isEmpty());
    }

    @Test
    public void repeatedPauseIsIdempotentAndDoesNotScheduleAnotherVerifier() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "/opt/wine/bin/wine64 game.exe", "wine64", 'T');
        RecordingSignalSender signals = new RecordingSignalSender();
        FakeScheduler scheduler = new FakeScheduler();
        WineSessionProcessController controller = controller(proc, signals, scheduler);

        controller.pauseAllWineProcesses();
        WineSessionProcessController.PauseResult repeated =
                controller.pauseAllWineProcesses();

        assertTrue(repeated.wasAlreadyPaused());
        assertEquals(1, repeated.getInitialProcessCount());
        assertEquals(0, repeated.getStopSignalCount());
        assertEquals(1, scheduler.tasks.size());
        assertEquals(Arrays.asList("20:19"), signals.events);
    }

    @Test
    public void repeatedPauseStopsProcessesSpawnedAfterInitialVerification() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "/opt/wine/bin/wineserver", "wineserver", 'T');
        RecordingSignalSender signals = new RecordingSignalSender();
        FakeScheduler scheduler = new FakeScheduler();
        WineSessionProcessController controller = controller(proc, signals, scheduler);

        controller.pauseAllWineProcesses();
        scheduler.runLatest();
        proc.add(30, 1000, "C:\\Games\\Battle.net.exe", "Battle.net.exe", 'S');

        WineSessionProcessController.PauseResult refreshed =
                controller.pauseAllWineProcesses();

        assertTrue(refreshed.wasAlreadyPaused());
        assertEquals(2, refreshed.getInitialProcessCount());
        assertEquals(1, refreshed.getStopSignalCount());
        assertEquals(2, scheduler.tasks.size());
        assertEquals(Arrays.asList("20:19", "30:19"), signals.events);
    }

    @Test
    public void failedSignalIsCountedAsSkippedWithoutAbortingOtherProcesses() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "/opt/wine/bin/wineserver", "wineserver", 'S');
        proc.add(30, 1000, "C:\\Games\\Wow.exe", "Wow.exe", 'S');
        RecordingSignalSender signals = new RecordingSignalSender();
        signals.failedPid = 20;
        FakeScheduler scheduler = new FakeScheduler();
        WineSessionProcessController controller = controller(proc, signals, scheduler);

        WineSessionProcessController.PauseResult result =
                controller.pauseAllWineProcesses();

        assertEquals(2, result.getInitialProcessCount());
        assertEquals(1, result.getStopSignalCount());
        assertEquals(Arrays.asList("20:19", "30:19"), signals.events);
    }

    @Test
    public void resumeHappensBeforeOrderlyShutdownAndIsSafeAfterExit() {
        FakeProcReader proc = new FakeProcReader(1000, 10);
        proc.add(20, 1000, "/opt/wine/bin/wineserver", "wineserver", 'T');
        RecordingSignalSender signals = new RecordingSignalSender();
        FakeScheduler scheduler = new FakeScheduler();
        WineSessionProcessController controller = controller(proc, signals, scheduler);

        controller.pauseAllWineProcesses();
        int resumed = controller.resumeAllWineProcesses();
        signals.events.add("shutdown");
        proc.remove(20);
        int repeatedResume = controller.resumeAllWineProcesses();

        assertFalse(controller.isPaused());
        assertEquals(1, resumed);
        assertEquals(0, repeatedResume);
        assertEquals(Arrays.asList("20:19", "20:18", "shutdown"), signals.events);
    }

    private static WineSessionProcessController controller(
            FakeProcReader proc,
            RecordingSignalSender signals,
            FakeScheduler scheduler) {
        return new WineSessionProcessController(
                proc,
                signals,
                scheduler,
                (message, error) -> {}
        );
    }

    private static void addZombieGroup(FakeProcReader proc, int pid, int tid) {
        proc.add(pid, 1000, "", "orphan.exe", 'Z');
        proc.addThread(pid, tid, 1000, "", "worker", 'S');
    }

    private static final class Entry {
        int uid;
        int threadGroupId;
        long startTime;
        final String commandLine;
        final String comm;
        char state;
        boolean wineMappingEvidence;

        Entry(int uid,
              int threadGroupId,
              long startTime,
              String commandLine,
              String comm,
              char state) {
            this.uid = uid;
            this.threadGroupId = threadGroupId;
            this.startTime = startTime;
            this.commandLine = commandLine;
            this.comm = comm;
            this.state = state;
        }
    }

    private static final class FakeProcReader
            implements WineSessionProcessController.ProcReader {
        private final int currentUid;
        private final int currentPid;
        private final List<Integer> pids = new ArrayList<>();
        private final Map<Integer, Entry> entries = new HashMap<>();
        private final Map<Integer, List<Integer>> tasks = new HashMap<>();

        FakeProcReader(int currentUid, int currentPid) {
            this.currentUid = currentUid;
            this.currentPid = currentPid;
        }

        void add(int pid, int uid, String commandLine, String comm, char state) {
            pids.add(pid);
            entries.put(pid, new Entry(uid, pid, startTimeFor(pid),
                    commandLine, comm, state));
            tasks.put(pid, new ArrayList<>(Arrays.asList(pid)));
        }

        void addThread(int pid,
                       int tid,
                       int uid,
                       String commandLine,
                       String comm,
                       char state) {
            entries.put(tid, new Entry(uid, pid, startTimeFor(tid),
                    commandLine, comm, state));
            List<Integer> taskIds = tasks.get(pid);
            if (taskIds == null) throw new IllegalArgumentException("Unknown process " + pid);
            taskIds.add(tid);
        }

        void addDuplicate(int pid) {
            pids.add(pid);
        }

        void addUnreadable(int pid) {
            pids.add(pid);
        }

        void remove(int pid) {
            pids.remove((Integer) pid);
            entries.remove(pid);
            List<Integer> taskIds = tasks.remove(pid);
            if (taskIds != null) {
                for (int tid : taskIds) entries.remove(tid);
            }
        }

        void setState(int pid, char state) {
            Entry entry = entries.get(pid);
            if (entry == null) throw new IllegalArgumentException("Unknown pid " + pid);
            entry.state = state;
        }

        void setUid(int pid, int uid) {
            entryUnchecked(pid).uid = uid;
        }

        void setThreadGroupId(int pid, int threadGroupId) {
            entryUnchecked(pid).threadGroupId = threadGroupId;
        }

        void bumpStartTime(int pid) {
            entryUnchecked(pid).startTime++;
        }

        void setWineMappingEvidence(int pid, boolean value) {
            entryUnchecked(pid).wineMappingEvidence = value;
        }

        @Override
        public int getCurrentUid() {
            return currentUid;
        }

        @Override
        public int getCurrentPid() {
            return currentPid;
        }

        @Override
        public List<Integer> listProcessIds() {
            return new ArrayList<>(pids);
        }

        @Override
        public List<Integer> listTaskIds(int pid) throws IOException {
            List<Integer> taskIds = tasks.get(pid);
            if (taskIds == null) throw new IOException("process exited");
            return new ArrayList<>(taskIds);
        }

        @Override
        public int readUid(int pid) throws IOException {
            return entry(pid).uid;
        }

        @Override
        public int readThreadGroupId(int pid) throws IOException {
            return entry(pid).threadGroupId;
        }

        @Override
        public long readStartTime(int pid) throws IOException {
            return entry(pid).startTime;
        }

        @Override
        public String readCommandLine(int pid) throws IOException {
            return entry(pid).commandLine;
        }

        @Override
        public String readComm(int pid) throws IOException {
            return entry(pid).comm;
        }

        @Override
        public char readState(int pid) throws IOException {
            return entry(pid).state;
        }

        @Override
        public boolean hasWineMappingEvidence(int pid) throws IOException {
            return entry(pid).wineMappingEvidence;
        }

        private Entry entry(int pid) throws IOException {
            Entry entry = entries.get(pid);
            if (entry == null) throw new IOException("process exited");
            return entry;
        }

        private Entry entryUnchecked(int pid) {
            Entry entry = entries.get(pid);
            if (entry == null) throw new IllegalArgumentException("Unknown pid " + pid);
            return entry;
        }

        private static long startTimeFor(int pid) {
            return 100000L + pid;
        }
    }

    private static final class RecordingSignalSender
            implements WineSessionProcessController.SignalSender {
        final List<String> events = new ArrayList<>();
        int failedPid = -1;
        Runnable onFirstSignal;

        @Override
        public boolean send(int pid, int signal) {
            events.add(pid + ":" + signal);
            if (events.size() == 1 && onFirstSignal != null) onFirstSignal.run();
            return pid != failedPid;
        }
    }

    private static final class FakeScheduler
            implements WineSessionProcessController.TaskScheduler {
        final List<Task> tasks = new ArrayList<>();
        Task latest;

        @Override
        public WineSessionProcessController.Cancelable schedule(Runnable task, long delayMs) {
            latest = new Task(task, delayMs);
            tasks.add(latest);
            return latest;
        }

        void runLatest() {
            if (!latest.cancelled) latest.runnable.run();
        }

        void forceRunLatest() {
            latest.runnable.run();
        }
    }

    private static final class Task implements WineSessionProcessController.Cancelable {
        final Runnable runnable;
        final long delayMs;
        boolean cancelled;

        Task(Runnable runnable, long delayMs) {
            this.runnable = runnable;
            this.delayMs = delayMs;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
