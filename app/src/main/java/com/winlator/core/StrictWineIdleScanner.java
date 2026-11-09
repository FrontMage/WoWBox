package com.winlator.core;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Migration-only fail-closed inspection of processes visible to this Android UID.
 * Call while launch and recovery are excluded by the owning transaction lock.
 * hidepid can hide processes: this scan cannot prove that the kernel's list is
 * complete, nor prevent an independently-created process after the snapshot.
 */
public final class StrictWineIdleScanner {
    private StrictWineIdleScanner() {}

    enum Result { IDLE, ACTIVE, UNKNOWN }
    static final class ProcStat {
        final long start;
        final char state;
        ProcStat(long start, char state) { this.start = start; this.state = state; }
    }
    interface Reader {
        int uid();
        int selfPid();
        List<Integer> pids() throws IOException;
        int owner(int pid) throws IOException;
        ProcStat stat(int pid) throws IOException;
        String cmdline(int pid) throws IOException;
        String comm(int pid) throws IOException;
        List<Integer> tasks(int pid) throws IOException;
        boolean mapsWine(int pid) throws IOException;
    }

    /** Throws when Wine is observed or available evidence is incomplete. */
    public static void requireIdle() {
        Result result = scan(new AndroidProcReader());
        if (result == Result.ACTIVE) throw new IllegalStateException("active_wine_processes");
        if (result != Result.IDLE) throw new IllegalStateException("strict_wine_idle_unknown");
    }

    static Result scan(Reader reader) {
        final List<Integer> pids;
        try { pids = reader.pids(); }
        catch (IOException | RuntimeException error) { return Result.UNKNOWN; }
        if (pids == null) return Result.UNKNOWN;
        Set<Integer> seen = new HashSet<>();
        boolean sawSelf = false;
        for (Integer item : pids) {
            if (item == null || item <= 0) return Result.UNKNOWN;
            int pid = item;
            if (pid == reader.selfPid()) { sawSelf = true; continue; }
            if (!seen.add(pid)) continue;
            ProcStat initial = null;
            try {
                // Directory UID lets inaccessible foreign processes be ignored
                // without opening their status/maps under Android hidepid.
                if (reader.owner(pid) != reader.uid()) continue;
                initial = reader.stat(pid);
                if (initial == null || initial.start <= 0) return Result.UNKNOWN;
                List<Integer> tids = reader.tasks(pid);
                if (tids == null || tids.isEmpty()) return Result.UNKNOWN;
                boolean live = isLive(initial.state);
                if (!live) {
                    // A zombie thread-group leader can retain live siblings.
                    // We cannot safely classify that process as idle.
                    for (Integer tid : tids) {
                        if (tid == null || tid <= 0) return Result.UNKNOWN;
                        if (tid != pid && isLive(reader.stat(tid).state)) return Result.UNKNOWN;
                    }
                } else {
                    String command = reader.cmdline(pid);
                    String comm = reader.comm(pid);
                    if (command == null || comm == null) return Result.UNKNOWN;
                    if (looksLikeWine(command, comm)) return Result.ACTIVE;
                    // A launcher can have an innocuous name while mapping Wine.
                    if (reader.mapsWine(pid)) return Result.ACTIVE;
                }
                ProcStat last = reader.stat(pid);
                if (last == null || last.start != initial.start) return Result.UNKNOWN;
            } catch (IOException | RuntimeException error) {
                // Normal exit is benign only when the PID is actually gone.
                // Permission denial, unreadable members and PID reuse are unknown.
                try {
                    ProcStat now = reader.stat(pid);
                    if (initial != null && now.start != initial.start) return Result.UNKNOWN;
                    return Result.UNKNOWN;
                } catch (NoSuchFileException gone) {
                    continue;
                } catch (IOException | RuntimeException ambiguous) {
                    return Result.UNKNOWN;
                }
            }
        }
        return sawSelf ? Result.IDLE : Result.UNKNOWN;
    }

    private static boolean isLive(char state) { return state != 'Z' && state != 'X' && state != 'x'; }
    private static boolean looksLikeWine(String command, String comm) {
        String text = (command + " " + comm).toLowerCase(Locale.ROOT);
        return text.contains("wine") || text.contains(".exe") || text.contains("wowbox64") ||
                text.contains("box64") || text.contains("fexloader");
    }

    private static final class AndroidProcReader implements Reader {
        private static final File PROC = new File("/proc");
        private static final int MAX_MAP_LINES = 200000;
        @Override public int uid() { return Process.myUid(); }
        @Override public int selfPid() { return Process.myPid(); }
        @Override public List<Integer> pids() throws IOException { return numericChildren(PROC); }
        @Override public int owner(int pid) throws IOException {
            try { return Os.stat(new File(PROC, Integer.toString(pid)).getPath()).st_uid; }
            catch (ErrnoException error) { throw convert(error, Integer.toString(pid)); }
        }
        @Override public ProcStat stat(int pid) throws IOException {
            String text = firstLine(file(pid, "stat"));
            int end = text.lastIndexOf(')');
            if (end < 0 || end + 1 >= text.length()) throw new IOException("invalid proc stat");
            String[] fields = text.substring(end + 1).trim().split("\\s+");
            if (fields.length <= 19 || fields[0].length() != 1) throw new IOException("short proc stat");
            try { return new ProcStat(Long.parseLong(fields[19]), fields[0].charAt(0)); }
            catch (NumberFormatException error) { throw new IOException("invalid start time", error); }
        }
        @Override public String cmdline(int pid) throws IOException {
            File path = file(pid, "cmdline");
            try (FileInputStream in = new FileInputStream(path)) {
                byte[] data = new byte[65536];
                int count = 0;
                while (count < data.length) {
                    int n = in.read(data, count, data.length - count);
                    if (n < 0) break;
                    if (n == 0) throw new IOException("cmdline read stalled");
                    count += n;
                }
                if (count == data.length && in.read() != -1) throw new IOException("cmdline exceeds limit");
                return new String(data, 0, count, StandardCharsets.UTF_8).replace('\0', ' ');
            } catch (IOException error) { throw checkGone(pid, error); }
        }
        @Override public String comm(int pid) throws IOException { return firstLine(file(pid, "comm")); }
        @Override public List<Integer> tasks(int pid) throws IOException { return numericChildren(file(pid, "task")); }
        @Override public boolean mapsWine(int pid) throws IOException {
            try (BufferedReader in = new BufferedReader(new FileReader(file(pid, "maps")))) {
                String line; int count = 0;
                while ((line = in.readLine()) != null) {
                    if (++count > MAX_MAP_LINES) throw new IOException("maps exceeds limit");
                    if (WineSessionProcessController.isWineMappingLine(line)) return true;
                }
                return false;
            } catch (IOException error) { throw checkGone(pid, error); }
        }
        private static File file(int pid, String name) { return new File(new File(PROC, Integer.toString(pid)), name); }
        private static String firstLine(File path) throws IOException {
            try (BufferedReader in = new BufferedReader(new FileReader(path))) {
                String line = in.readLine();
                if (line == null) throw new IOException("empty " + path.getName());
                return line;
            } catch (IOException error) {
                try { Os.stat(path.getParent()); }
                catch (ErrnoException stat) { throw convert(stat, path.toString()); }
                throw error;
            }
        }
        private static List<Integer> numericChildren(File dir) throws IOException {
            File[] entries = dir.listFiles();
            if (entries == null) {
                try { Os.stat(dir.getPath()); }
                catch (ErrnoException error) { throw convert(error, dir.toString()); }
                throw new IOException("cannot enumerate " + dir);
            }
            List<Integer> result = new ArrayList<>();
            for (File entry : entries) {
                String name = entry.getName();
                if (!name.matches("[0-9]+")) continue;
                try { result.add(Integer.parseInt(name)); }
                catch (NumberFormatException error) { throw new IOException("invalid process ID", error); }
            }
            return result;
        }
        private static IOException checkGone(int pid, IOException original) {
            try { Os.stat(new File(PROC, Integer.toString(pid)).getPath()); }
            catch (ErrnoException error) { return convert(error, Integer.toString(pid)); }
            return original;
        }
        private static IOException convert(ErrnoException error, String path) {
            if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH)
                return new NoSuchFileException(path);
            return new IOException("proc access failed: " + path, error);
        }
    }
}
