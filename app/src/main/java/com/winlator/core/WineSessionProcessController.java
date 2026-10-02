package com.winlator.core;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Freezes and resumes the Wine side of a Winlator session without suspending the
 * Android host or its audio server.
 *
 * <p>The implementation discovers the complete same-UID Wine process set from
 * procfs instead of relying on the launcher's PID. Wine can detach from that
 * launcher and create additional Windows processes after the initial launch.</p>
 */
public final class WineSessionProcessController {
    private static final String TAG = "WineSessionPause";
    private static final int SIGKILL = 9;
    private static final int SIGCONT = 18;
    private static final int SIGSTOP = 19;
    private static final long VERIFICATION_DELAY_MS = 50L;
    private static final String[] WINE_MAP_MARKERS = {
            "/lib/wine/",
            "/.wine/drive_c/",
            "/opt/proton-",
            "libarm64ecfex",
            "wow64fex",
            "wowbox64"
    };

    public interface EventListener {
        void onPauseVerificationComplete(VerificationResult result);
    }

    interface ProcReader {
        int getCurrentUid();
        int getCurrentPid();
        List<Integer> listProcessIds();
        List<Integer> listTaskIds(int pid) throws IOException;
        int readUid(int pid) throws IOException;
        int readThreadGroupId(int pid) throws IOException;
        long readStartTime(int pid) throws IOException;
        String readCommandLine(int pid) throws IOException;
        String readComm(int pid) throws IOException;
        char readState(int pid) throws IOException;
        boolean hasWineMappingEvidence(int pid) throws IOException;
    }

    interface SignalSender {
        boolean send(int pid, int signal);
    }

    interface Cancelable {
        void cancel();
    }

    interface TaskScheduler {
        Cancelable schedule(Runnable task, long delayMs);
    }

    interface DiagnosticLogger {
        void warning(String message, Throwable error);
    }

    public static final class PauseResult {
        private final int initialProcessCount;
        private final int stopSignalCount;
        private final boolean alreadyPaused;

        PauseResult(int initialProcessCount, int stopSignalCount, boolean alreadyPaused) {
            this.initialProcessCount = initialProcessCount;
            this.stopSignalCount = stopSignalCount;
            this.alreadyPaused = alreadyPaused;
        }

        public int getInitialProcessCount() {
            return initialProcessCount;
        }

        public int getStopSignalCount() {
            return stopSignalCount;
        }

        public boolean wasAlreadyPaused() {
            return alreadyPaused;
        }
    }

    /**
     * Describes one bounded force-kill sweep. The counts describe the procfs
     * snapshot taken before signals were sent; a caller can use
     * {@link #hasActiveWineProcesses()} to perform its own bounded wait and
     * verification without blocking this controller.
     */
    public static final class ForceKillResult {
        private final int candidateProcessCount;
        private final int liveThreadCount;
        private final int killSignalCount;

        ForceKillResult(int candidateProcessCount,
                        int liveThreadCount,
                        int killSignalCount) {
            this.candidateProcessCount = candidateProcessCount;
            this.liveThreadCount = liveThreadCount;
            this.killSignalCount = killSignalCount;
        }

        public int getCandidateProcessCount() {
            return candidateProcessCount;
        }

        public int getLiveThreadCount() {
            return liveThreadCount;
        }

        public int getKillSignalCount() {
            return killSignalCount;
        }
    }

    private static final class CandidateProcess {
        final int processId;
        final long startTime;
        final List<CandidateThread> liveThreads;

        CandidateProcess(int processId,
                         long startTime,
                         List<CandidateThread> liveThreads) {
            this.processId = processId;
            this.startTime = startTime;
            this.liveThreads = liveThreads;
        }
    }

    private static final class CandidateThread {
        final int threadId;
        final long startTime;

        CandidateThread(int threadId, long startTime) {
            this.threadId = threadId;
            this.startTime = startTime;
        }
    }

    public static final class VerificationResult {
        private final int initialProcessCount;
        private final int initialStopSignalCount;
        private final int verifiedStoppedCount;
        private final int retrySignalCount;
        private final int lateProcessCount;
        private final int lateStopSignalCount;
        private final int trackedProcessCount;

        VerificationResult(int initialProcessCount,
                           int initialStopSignalCount,
                           int verifiedStoppedCount,
                           int retrySignalCount,
                           int lateProcessCount,
                           int lateStopSignalCount,
                           int trackedProcessCount) {
            this.initialProcessCount = initialProcessCount;
            this.initialStopSignalCount = initialStopSignalCount;
            this.verifiedStoppedCount = verifiedStoppedCount;
            this.retrySignalCount = retrySignalCount;
            this.lateProcessCount = lateProcessCount;
            this.lateStopSignalCount = lateStopSignalCount;
            this.trackedProcessCount = trackedProcessCount;
        }

        public int getInitialProcessCount() {
            return initialProcessCount;
        }

        public int getInitialStopSignalCount() {
            return initialStopSignalCount;
        }

        public int getVerifiedStoppedCount() {
            return verifiedStoppedCount;
        }

        public int getRetrySignalCount() {
            return retrySignalCount;
        }

        public int getLateProcessCount() {
            return lateProcessCount;
        }

        public int getLateStopSignalCount() {
            return lateStopSignalCount;
        }

        public int getTrackedProcessCount() {
            return trackedProcessCount;
        }
    }

    private static final class SingletonHolder {
        private static final WineSessionProcessController INSTANCE =
                new WineSessionProcessController(
                        new LinuxProcReader(),
                        (pid, signal) -> {
                            try {
                                Process.sendSignal(pid, signal);
                                return true;
                            }
                            catch (Throwable error) {
                                Log.w(TAG, "Unable to signal pid=" + pid + " signal=" + signal, error);
                                return false;
                            }
                        },
                        new ExecutorTaskScheduler(),
                        (message, error) -> Log.w(TAG, message, error)
                );
    }

    public static WineSessionProcessController getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private final Object lock = new Object();
    private final ProcReader procReader;
    private final SignalSender signalSender;
    private final TaskScheduler scheduler;
    private final DiagnosticLogger logger;
    private final Set<Integer> stoppedPids = new LinkedHashSet<>();

    private boolean pauseActive;
    private long pauseGeneration;
    private Cancelable pendingVerification;
    private EventListener eventListener;

    WineSessionProcessController(ProcReader procReader,
                                 SignalSender signalSender,
                                 TaskScheduler scheduler,
                                 DiagnosticLogger logger) {
        this.procReader = procReader;
        this.signalSender = signalSender;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public void setEventListener(EventListener eventListener) {
        synchronized (lock) {
            this.eventListener = eventListener;
        }
    }

    public PauseResult pauseAllWineProcesses() {
        synchronized (lock) {
            boolean alreadyPaused = pauseActive;
            if (!alreadyPaused) {
                pauseActive = true;
                stoppedPids.clear();
            }

            List<Integer> initialPids = scanCandidatePids();
            int stopSignals = 0;
            for (int pid : initialPids) {
                if (alreadyPaused && stoppedPids.contains(pid) && isProcessStopped(pid)) {
                    continue;
                }
                if (sendSignal(pid, SIGSTOP)) {
                    stoppedPids.add(pid);
                    stopSignals++;
                }
            }

            if (!alreadyPaused || stopSignals > 0) {
                long generation = ++pauseGeneration;
                cancelPendingVerificationLocked();
                final List<Integer> initialSnapshot =
                        Collections.unmodifiableList(new ArrayList<>(initialPids));
                final int initialStopSignals = stopSignals;
                pendingVerification = scheduler.schedule(
                        () -> verifyPause(generation, initialSnapshot, initialStopSignals),
                        VERIFICATION_DELAY_MS
                );
            }
            return new PauseResult(initialPids.size(), stopSignals, alreadyPaused);
        }
    }

    public int resumeAllWineProcesses() {
        synchronized (lock) {
            boolean hadActivePause = pauseActive || !stoppedPids.isEmpty();
            pauseActive = false;
            pauseGeneration++;
            cancelPendingVerificationLocked();
            if (!hadActivePause) return 0;

            Set<Integer> liveCandidates = new LinkedHashSet<>(scanCandidatePids());
            int continueSignals = 0;
            for (int pid : liveCandidates) {
                if (sendSignal(pid, SIGCONT)) continueSignals++;
            }
            stoppedPids.clear();
            return continueSignals;
        }
    }

    public boolean isPaused() {
        synchronized (lock) {
            return pauseActive || !stoppedPids.isEmpty();
        }
    }

    public boolean hasActiveWineProcesses() {
        return !scanCandidateProcesses().isEmpty();
    }

    /**
     * Sends {@code SIGKILL} to every live thread in the current same-UID Wine
     * candidate set. This is intentionally a single bounded procfs sweep: the
     * orderly-shutdown caller owns its timeout and polling policy.
     *
     * <p>Thread-by-thread signalling is required for the Android failure mode
     * where a Wine thread-group leader has already become a zombie while live
     * sibling threads remain behind it. Signalling only the zombie TGID leader
     * cannot terminate those siblings.</p>
     */
    public ForceKillResult forceKillWineProcesses() {
        synchronized (lock) {
            pauseActive = false;
            pauseGeneration++;
            cancelPendingVerificationLocked();
            stoppedPids.clear();

            List<CandidateProcess> candidates = scanCandidateProcesses();
            int currentUid = procReader.getCurrentUid();
            int liveThreadCount = 0;
            int killSignals = 0;
            for (CandidateProcess candidate : candidates) {
                liveThreadCount += candidate.liveThreads.size();
                for (CandidateThread thread : candidate.liveThreads) {
                    if (isStillSameLiveThread(currentUid, candidate, thread)
                            && sendSignal(thread.threadId, SIGKILL)) {
                        killSignals++;
                    }
                }
            }
            return new ForceKillResult(candidates.size(), liveThreadCount, killSignals);
        }
    }

    private boolean isProcessStopped(int pid) {
        try {
            char state = procReader.readState(pid);
            return state == 'T' || state == 't';
        }
        catch (IOException | RuntimeException error) {
            logFailure("Unable to read stopped state for pid=" + pid, error);
            return false;
        }
    }

    private void verifyPause(long generation,
                             List<Integer> initialPids,
                             int initialStopSignals) {
        VerificationResult result;
        EventListener listener;
        synchronized (lock) {
            if (!pauseActive || generation != pauseGeneration) return;
            pendingVerification = null;

            List<Integer> currentPids = scanCandidatePids();
            Set<Integer> currentPidSet = new HashSet<>(currentPids);
            Set<Integer> initialPidSet = new HashSet<>(initialPids);
            int verifiedStopped = 0;
            int retrySignals = 0;

            for (int pid : initialPids) {
                if (!currentPidSet.contains(pid)) continue;
                char state;
                try {
                    state = procReader.readState(pid);
                }
                catch (IOException | RuntimeException error) {
                    logFailure("Unable to verify stopped state for pid=" + pid, error);
                    state = '\0';
                }
                if (state == 'T' || state == 't') {
                    verifiedStopped++;
                }
                else if (sendSignal(pid, SIGSTOP)) {
                    stoppedPids.add(pid);
                    retrySignals++;
                }
            }

            int lateProcessCount = 0;
            int lateStopSignals = 0;
            for (int pid : currentPids) {
                if (initialPidSet.contains(pid)) continue;
                lateProcessCount++;
                if (sendSignal(pid, SIGSTOP)) {
                    stoppedPids.add(pid);
                    lateStopSignals++;
                }
            }

            result = new VerificationResult(
                    initialPids.size(),
                    initialStopSignals,
                    verifiedStopped,
                    retrySignals,
                    lateProcessCount,
                    lateStopSignals,
                    stoppedPids.size()
            );
            listener = eventListener;
        }

        if (listener != null) {
            try {
                listener.onPauseVerificationComplete(result);
            }
            catch (RuntimeException error) {
                logFailure("Pause verification listener failed", error);
            }
        }
    }

    private void cancelPendingVerificationLocked() {
        if (pendingVerification == null) return;
        pendingVerification.cancel();
        pendingVerification = null;
    }

    private List<Integer> scanCandidatePids() {
        List<CandidateProcess> candidates = scanCandidateProcesses();
        ArrayList<Integer> result = new ArrayList<>(candidates.size());
        for (CandidateProcess candidate : candidates) {
            result.add(candidate.processId);
        }
        return result;
    }

    private List<CandidateProcess> scanCandidateProcesses() {
        Set<Integer> seenProcessIds = new HashSet<>();
        ArrayList<CandidateProcess> result = new ArrayList<>();
        int currentUid = procReader.getCurrentUid();
        int currentPid = procReader.getCurrentPid();
        List<Integer> pids;
        try {
            pids = procReader.listProcessIds();
        }
        catch (RuntimeException error) {
            logFailure("Unable to enumerate procfs", error);
            return new ArrayList<>();
        }

        for (Integer value : pids) {
            if (value == null) continue;
            int pid = value;
            if (pid <= 0 || pid == currentPid || !seenProcessIds.add(pid)) continue;
            try {
                long processStartTime = procReader.readStartTime(pid);
                if (procReader.readUid(pid) != currentUid) continue;
                String commandLine = "";
                String comm = "";
                boolean commandLineReadable = true;
                try {
                    commandLine = procReader.readCommandLine(pid);
                }
                catch (IOException | RuntimeException error) {
                    commandLineReadable = false;
                    logFailure("Unable to read command line for pid=" + pid, error);
                }
                try {
                    comm = procReader.readComm(pid);
                }
                catch (IOException | RuntimeException error) {
                    logFailure("Unable to read comm for pid=" + pid, error);
                }
                if (isExcludedHostProcess(commandLine, comm)) continue;

                List<CandidateThread> liveThreads = readLiveThreads(pid, currentUid);
                boolean ordinaryWineProcess = isWineGuestProcess(commandLine, comm);
                boolean orphanedWineProcess = false;
                if (!ordinaryWineProcess
                        && commandLineReadable
                        && (commandLine == null || commandLine.trim().isEmpty())
                        && hasLiveSibling(pid, liveThreads)) {
                    try {
                        if (procReader.readState(pid) == 'Z') {
                            orphanedWineProcess = hasExeComm(comm)
                                    || hasWineMappedLiveSibling(
                                            pid, currentUid, liveThreads);
                        }
                    }
                    catch (IOException | RuntimeException error) {
                        logFailure("Unable to read zombie leader state for pid=" + pid, error);
                    }
                }

                if (!liveThreads.isEmpty()
                        && (ordinaryWineProcess || orphanedWineProcess)
                        && procReader.readStartTime(pid) == processStartTime) {
                    result.add(new CandidateProcess(pid, processStartTime, liveThreads));
                }
            }
            catch (IOException | RuntimeException error) {
                logFailure("Skipping unreadable or exited procfs entry pid=" + pid, error);
            }
        }

        Collections.sort(result, (left, right) ->
                Integer.compare(left.processId, right.processId));
        return result;
    }

    private List<CandidateThread> readLiveThreads(int pid, int currentUid) {
        List<Integer> taskIds;
        try {
            taskIds = procReader.listTaskIds(pid);
        }
        catch (IOException | RuntimeException error) {
            logFailure("Unable to enumerate tasks for pid=" + pid, error);
            taskIds = Collections.singletonList(pid);
        }

        Set<Integer> uniqueTaskIds = new HashSet<>();
        ArrayList<CandidateThread> liveThreads = new ArrayList<>();
        for (Integer value : taskIds) {
            if (value == null) continue;
            int tid = value;
            if (tid <= 0 || !uniqueTaskIds.add(tid)) continue;
            try {
                long startTime = procReader.readStartTime(tid);
                if (procReader.readUid(tid) == currentUid
                        && procReader.readThreadGroupId(tid) == pid
                        && isLiveState(procReader.readState(tid))
                        && procReader.readStartTime(tid) == startTime) {
                    liveThreads.add(new CandidateThread(tid, startTime));
                }
            }
            catch (IOException | RuntimeException error) {
                logFailure("Unable to read thread state for tid=" + tid
                        + " tgid=" + pid, error);
            }
        }
        Collections.sort(liveThreads, (left, right) ->
                Integer.compare(left.threadId, right.threadId));
        return liveThreads;
    }

    private boolean hasWineMappedLiveSibling(int pid,
                                             int currentUid,
                                             List<CandidateThread> liveThreads) {
        // Threads in one group share an mm, so one stable live sibling is a
        // representative maps probe and keeps frequent idle polling bounded.
        CandidateThread sibling = null;
        for (CandidateThread thread : liveThreads) {
            if (thread.threadId != pid) {
                sibling = thread;
                break;
            }
        }
        if (sibling == null) return false;

        try {
            return procReader.hasWineMappingEvidence(sibling.threadId)
                    && procReader.readUid(sibling.threadId) == currentUid
                    && procReader.readThreadGroupId(sibling.threadId) == pid
                    && procReader.readStartTime(sibling.threadId) == sibling.startTime
                    && isLiveState(procReader.readState(sibling.threadId));
        }
        catch (IOException | RuntimeException error) {
            logFailure("Unable to read maps for tid=" + sibling.threadId
                    + " tgid=" + pid, error);
            return false;
        }
    }

    private boolean isStillSameLiveThread(int currentUid,
                                          CandidateProcess process,
                                          CandidateThread thread) {
        try {
            return procReader.readUid(thread.threadId) == currentUid
                    && procReader.readThreadGroupId(thread.threadId) == process.processId
                    && procReader.readStartTime(process.processId) == process.startTime
                    && procReader.readStartTime(thread.threadId) == thread.startTime
                    && isLiveState(procReader.readState(thread.threadId));
        }
        catch (IOException | RuntimeException error) {
            logFailure("Unable to revalidate tid=" + thread.threadId
                    + " tgid=" + process.processId + " before SIGKILL", error);
            return false;
        }
    }

    private static boolean hasLiveSibling(int pid,
                                          List<CandidateThread> liveThreads) {
        for (CandidateThread thread : liveThreads) {
            if (thread.threadId != pid) return true;
        }
        return false;
    }

    private static boolean isLiveState(char state) {
        return state != 'Z' && state != 'X' && state != 'x';
    }

    private static boolean hasExeComm(String comm) {
        return comm != null && comm.trim().toLowerCase(Locale.ROOT).endsWith(".exe");
    }

    private static boolean isExcludedHostProcess(String commandLine, String comm) {
        String descriptor = ((commandLine != null ? commandLine : "") + " "
                + (comm != null ? comm : "")).toLowerCase(Locale.ROOT);
        return descriptor.contains("pulseaudio")
                || descriptor.contains("libpulseaudio.so");
    }

    static boolean isWineGuestProcess(String commandLine, String comm) {
        String normalizedCommandLine =
                (commandLine != null ? commandLine : "").toLowerCase(Locale.ROOT);
        if (isExcludedHostProcess(commandLine, comm)) return false;
        return normalizedCommandLine.contains("wine")
                || normalizedCommandLine.contains(".exe");
    }

    static boolean isWineMappingLine(String line) {
        String normalized = (line != null ? line : "").toLowerCase(Locale.ROOT);
        for (String marker : WINE_MAP_MARKERS) {
            if (normalized.contains(marker)) return true;
        }
        return false;
    }

    private boolean sendSignal(int pid, int signal) {
        try {
            return signalSender.send(pid, signal);
        }
        catch (RuntimeException error) {
            logFailure("Unable to signal pid=" + pid + " signal=" + signal, error);
            return false;
        }
    }

    private void logFailure(String message, Throwable error) {
        if (logger == null) return;
        try {
            logger.warning(message, error);
        }
        catch (RuntimeException ignored) {}
    }

    private static final class ExecutorTaskScheduler implements TaskScheduler {
        private final ScheduledExecutorService executor;

        private ExecutorTaskScheduler() {
            ThreadFactory threadFactory = runnable -> {
                Thread thread = new Thread(runnable, "wine-session-pause-check");
                thread.setDaemon(true);
                return thread;
            };
            executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
        }

        @Override
        public Cancelable schedule(Runnable task, long delayMs) {
            ScheduledFuture<?> future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
    }

    private static final class LinuxProcReader implements ProcReader {
        private static final File PROC_DIR = new File("/proc");
        private static final int MAX_COMMAND_LINE_BYTES = 32768;
        private static final int MAX_MAP_LINES = 65536;

        @Override
        public int getCurrentUid() {
            return Process.myUid();
        }

        @Override
        public int getCurrentPid() {
            return Process.myPid();
        }

        @Override
        public List<Integer> listProcessIds() {
            ArrayList<Integer> result = new ArrayList<>();
            File[] entries = PROC_DIR.listFiles();
            if (entries == null) return result;
            for (File entry : entries) {
                String name = entry.getName();
                if (!isNumeric(name)) continue;
                try {
                    result.add(Integer.parseInt(name));
                }
                catch (NumberFormatException ignored) {}
            }
            return result;
        }

        @Override
        public List<Integer> listTaskIds(int pid) throws IOException {
            File taskDir = new File(PROC_DIR, pid + "/task");
            File[] entries = taskDir.listFiles();
            if (entries == null) throw new IOException("Unable to list task directory");

            ArrayList<Integer> result = new ArrayList<>();
            for (File entry : entries) {
                String name = entry.getName();
                if (!isNumeric(name)) continue;
                try {
                    result.add(Integer.parseInt(name));
                }
                catch (NumberFormatException ignored) {}
            }
            return result;
        }

        @Override
        public int readUid(int pid) throws IOException {
            String uidLine = readStatusValue(pid, "Uid:");
            if (uidLine == null) throw new IOException("Uid missing");
            String[] fields = uidLine.trim().split("\\s+");
            if (fields.length == 0) throw new IOException("Uid value missing");
            try {
                return Integer.parseInt(fields[0]);
            }
            catch (NumberFormatException error) {
                throw new IOException("Invalid Uid", error);
            }
        }

        @Override
        public int readThreadGroupId(int pid) throws IOException {
            String tgidLine = readStatusValue(pid, "Tgid:");
            if (tgidLine == null) throw new IOException("Tgid missing");
            try {
                return Integer.parseInt(tgidLine.trim());
            }
            catch (NumberFormatException error) {
                throw new IOException("Invalid Tgid", error);
            }
        }

        @Override
        public long readStartTime(int pid) throws IOException {
            File file = new File(PROC_DIR, pid + "/stat");
            String stat;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                stat = reader.readLine();
            }
            if (stat == null) throw new IOException("stat missing");

            int commEnd = stat.lastIndexOf(')');
            if (commEnd < 0 || commEnd + 1 >= stat.length()) {
                throw new IOException("Invalid stat comm");
            }
            String[] fields = stat.substring(commEnd + 1).trim().split("\\s+");
            if (fields.length <= 19) throw new IOException("stat starttime missing");
            try {
                return Long.parseLong(fields[19]);
            }
            catch (NumberFormatException error) {
                throw new IOException("Invalid stat starttime", error);
            }
        }

        @Override
        public String readCommandLine(int pid) throws IOException {
            File file = new File(PROC_DIR, pid + "/cmdline");
            byte[] buffer = new byte[MAX_COMMAND_LINE_BYTES];
            int length;
            try (FileInputStream input = new FileInputStream(file)) {
                length = input.read(buffer);
            }
            if (length <= 0) return "";
            String value = new String(buffer, 0, length, StandardCharsets.UTF_8);
            return value.replace('\0', ' ').trim();
        }

        @Override
        public String readComm(int pid) throws IOException {
            File file = new File(PROC_DIR, pid + "/comm");
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line = reader.readLine();
                return line != null ? line.trim() : "";
            }
        }

        @Override
        public char readState(int pid) throws IOException {
            String stateLine = readStatusValue(pid, "State:");
            if (stateLine == null) throw new IOException("State missing");
            String value = stateLine.trim();
            if (value.isEmpty()) throw new IOException("State value missing");
            return value.charAt(0);
        }

        @Override
        public boolean hasWineMappingEvidence(int pid) throws IOException {
            File file = new File(PROC_DIR, pid + "/maps");
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                int lines = 0;
                while (lines++ < MAX_MAP_LINES && (line = reader.readLine()) != null) {
                    if (isWineMappingLine(line)) return true;
                }
            }
            return false;
        }

        private static String readStatusValue(int pid, String key) throws IOException {
            File file = new File(PROC_DIR, pid + "/status");
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(key)) return line.substring(key.length());
                }
            }
            return null;
        }

        private static boolean isNumeric(String value) {
            if (value == null || value.isEmpty()) return false;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch < '0' || ch > '9') return false;
            }
            return true;
        }
    }
}
