package com.winlator.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class SessionLogWriter implements Callback<String> {
    private static final int MAX_CONSECUTIVE_DUPLICATES = 3;
    private static final int SUMMARY_INTERVAL = 1024;
    private static final long MAX_LOG_BYTES = 256L * 1024L * 1024L;

    private final File logFile;
    private final Object lock = new Object();
    private BufferedWriter writer;
    private int pendingLines = 0;
    private long lastFlushMs = 0;
    private String lastLine;
    private int duplicateCount = 0;
    private long totalSuppressedDuplicates = 0;
    private boolean sizeLimitReached = false;

    public SessionLogWriter(File logFile) {
        this.logFile = logFile;
    }

    @Override
    public void call(String line) {
        appendLine(line);
    }

    public void appendLine(String line) {
        synchronized (lock) {
            if (sizeLimitReached) return;
            ensureWriter();
            if (writer == null) return;
            try {
                appendLineLocked(line);
            }
            catch (IOException e) {
                // Ignore write failures to avoid crashing the app.
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (writer != null) {
                try {
                    flushDuplicateSummaryLocked();
                    writer.flush();
                    writer.close();
                }
                catch (IOException e) {
                    // Ignore close failures.
                }
                writer = null;
            }
        }
    }

    private void appendLineLocked(String line) throws IOException {
        if (line != null && line.equals(lastLine)) {
            duplicateCount++;
            if (duplicateCount > MAX_CONSECUTIVE_DUPLICATES) {
                totalSuppressedDuplicates++;
                if (totalSuppressedDuplicates % SUMMARY_INTERVAL == 0) {
                    writeRawLineLocked("[session-log] suppressed " + totalSuppressedDuplicates + " repeated lines matching previous line");
                }
                return;
            }
        }
        else {
            flushDuplicateSummaryLocked();
            lastLine = line;
            duplicateCount = 1;
        }

        writeRawLineLocked(line);
    }

    private void flushDuplicateSummaryLocked() throws IOException {
        if (duplicateCount > MAX_CONSECUTIVE_DUPLICATES && lastLine != null) {
            int suppressed = duplicateCount - MAX_CONSECUTIVE_DUPLICATES;
            writeRawLineLocked("[session-log] suppressed " + suppressed + " repeated lines matching previous line");
        }
        duplicateCount = 0;
    }

    private void writeRawLineLocked(String line) throws IOException {
        if (line == null) line = "";
        writer.write(line);
        writer.newLine();
        pendingLines++;

        long now = System.currentTimeMillis();
        boolean important =
                line.startsWith("err:") ||
                line.contains("Unhandled exception") ||
                line.contains("FATAL") ||
                line.contains("EXCEPTION") ||
                line.startsWith("[session-log]");
        if (important || pendingLines >= 64 || (now - lastFlushMs) >= 250) {
            writer.flush();
            pendingLines = 0;
            lastFlushMs = now;
            enforceSizeLimitLocked();
        }
    }

    private void enforceSizeLimitLocked() throws IOException {
        if (logFile.length() < MAX_LOG_BYTES) return;
        writer.write("[session-log] log size limit reached; further guest log lines are suppressed");
        writer.newLine();
        writer.flush();
        sizeLimitReached = true;
    }

    private void ensureWriter() {
        if (writer != null) return;
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try {
            writer = new BufferedWriter(new FileWriter(logFile, true));
            pendingLines = 0;
            lastFlushMs = System.currentTimeMillis();
        }
        catch (IOException e) {
            writer = null;
        }
    }
}
