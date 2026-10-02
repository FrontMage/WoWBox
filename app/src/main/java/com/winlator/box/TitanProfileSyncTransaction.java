package com.winlator.box;

import com.winlator.core.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public final class TitanProfileSyncTransaction {
    interface FailureInjector {
        void before(String step) throws IOException;
    }

    public static final class TreeStats {
        public final long wtfFiles;
        public final long addonFiles;
        public final long totalBytes;

        TreeStats(long wtfFiles, long addonFiles, long totalBytes) {
            this.wtfFiles = wtfFiles;
            this.addonFiles = addonFiles;
            this.totalBytes = totalBytes;
        }
    }

    private static final String JOURNAL = "transaction.properties";
    private static final String STAGE = "stage";
    private static final String BACKUP = "backup";

    private final File targetRoot;
    private final File workDir;
    private final FailureInjector failureInjector;

    public TitanProfileSyncTransaction(File targetRoot, File workDir) {
        this(targetRoot, workDir, step -> {});
    }

    TitanProfileSyncTransaction(
            File targetRoot, File workDir, FailureInjector failureInjector) {
        this.targetRoot = targetRoot;
        this.workDir = workDir;
        this.failureInjector = failureInjector;
    }

    public File prepareStaging() throws IOException {
        recover();
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new IOException("Unable to create sync workspace");
        }
        File stage = getStageDir();
        if (stage.exists() && !FileUtils.delete(stage)) {
            throw new IOException("Unable to clear sync staging");
        }
        if (!stage.mkdirs()) throw new IOException("Unable to create sync staging");
        return stage;
    }

    public synchronized void activate(
            long expectedWtfFiles,
            long expectedAddonFiles,
            long expectedBytes) throws IOException {
        File stagedWtf = new File(getStageDir(), "WTF");
        File stagedAddons = new File(getStageDir(), "Interface/AddOns");
        if (!stagedWtf.isDirectory() || !stagedAddons.isDirectory()) {
            throw new IOException("Staging is incomplete");
        }
        TreeStats stagedStats = scanTrees(stagedWtf, stagedAddons);
        requireStats(stagedStats, expectedWtfFiles, expectedAddonFiles, expectedBytes);

        File targetWtf = new File(targetRoot, "WTF");
        File targetAddons = new File(targetRoot, "Interface/AddOns");
        File targetInterface = targetAddons.getParentFile();
        if (!targetRoot.isDirectory()) throw new IOException("Titan target is missing");
        if (!targetInterface.isDirectory() && !targetInterface.mkdirs()) {
            throw new IOException("Unable to create target Interface directory");
        }

        File backup = getBackupDir();
        if (backup.exists() && !FileUtils.delete(backup)) {
            throw new IOException("Unable to clear old transaction backup");
        }
        if (!backup.mkdirs()) throw new IOException("Unable to create transaction backup");
        File backupWtf = new File(backup, "WTF");
        File backupAddons = new File(backup, "AddOns");

        Properties journal = new Properties();
        journal.setProperty("phase", "PREPARED");
        journal.setProperty("originalWtf", Boolean.toString(targetWtf.exists()));
        journal.setProperty("originalAddons", Boolean.toString(targetAddons.exists()));
        writeJournal(journal);

        try {
            failureInjector.before("move-old-wtf");
            if (targetWtf.exists() && !targetWtf.renameTo(backupWtf)) {
                throw new IOException("Unable to back up current WTF");
            }
            setPhase(journal, "OLD_WTF_MOVED");

            failureInjector.before("activate-new-wtf");
            if (!stagedWtf.renameTo(targetWtf)) {
                throw new IOException("Unable to activate new WTF");
            }
            setPhase(journal, "NEW_WTF_ACTIVE");

            failureInjector.before("move-old-addons");
            if (targetAddons.exists() && !targetAddons.renameTo(backupAddons)) {
                throw new IOException("Unable to back up current AddOns");
            }
            setPhase(journal, "OLD_ADDONS_MOVED");

            failureInjector.before("activate-new-addons");
            if (!stagedAddons.renameTo(targetAddons)) {
                throw new IOException("Unable to activate new AddOns");
            }
            setPhase(journal, "NEW_ADDONS_ACTIVE");

            failureInjector.before("verify-active");
            TreeStats activeStats = scanTrees(targetWtf, targetAddons);
            requireStats(activeStats, expectedWtfFiles, expectedAddonFiles, expectedBytes);
            setPhase(journal, "COMMITTED");
        }
        catch (IOException error) {
            IOException rollbackError = rollback(journal);
            if (rollbackError != null) error.addSuppressed(rollbackError);
            throw error;
        }
        cleanupCommitted();
    }

    public synchronized void recover() throws IOException {
        File journalFile = getJournalFile();
        if (!journalFile.isFile()) {
            File backup = getBackupDir();
            if (backup.exists()) {
                throw new IOException("Orphaned sync backup requires manual inspection");
            }
            return;
        }
        Properties journal = readJournal();
        if ("COMMITTED".equals(journal.getProperty("phase"))) {
            cleanupCommitted();
            return;
        }
        IOException error = rollback(journal);
        if (error != null) throw error;
    }

    public synchronized void discardStaging() {
        File stage = getStageDir();
        if (stage.exists()) FileUtils.delete(stage);
    }

    private IOException rollback(Properties journal) {
        try {
            boolean originalWtf =
                    Boolean.parseBoolean(journal.getProperty("originalWtf", "false"));
            boolean originalAddons =
                    Boolean.parseBoolean(journal.getProperty("originalAddons", "false"));
            restoreTree(
                    new File(targetRoot, "WTF"),
                    new File(getStageDir(), "WTF"),
                    new File(getBackupDir(), "WTF"),
                    originalWtf);
            restoreTree(
                    new File(targetRoot, "Interface/AddOns"),
                    new File(getStageDir(), "Interface/AddOns"),
                    new File(getBackupDir(), "AddOns"),
                    originalAddons);
            FileUtils.delete(getStageDir());
            FileUtils.delete(getBackupDir());
            getJournalFile().delete();
            return null;
        }
        catch (IOException error) {
            return new IOException("Unable to restore the previous Titan profile", error);
        }
    }

    private static void restoreTree(
            File target, File staged, File backup, boolean originalExisted) throws IOException {
        if (backup.exists()) {
            if (target.exists() && !FileUtils.delete(target)) {
                throw new IOException("Unable to remove partially activated " + target);
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Unable to create restore parent " + parent);
            }
            if (!backup.renameTo(target)) {
                throw new IOException("Unable to restore " + target);
            }
            return;
        }
        if (!originalExisted && !staged.exists() && target.exists() && !FileUtils.delete(target)) {
            throw new IOException("Unable to remove newly activated " + target);
        }
    }

    private void cleanupCommitted() throws IOException {
        if (getBackupDir().exists() && !FileUtils.delete(getBackupDir())) {
            throw new IOException("Unable to delete transient sync backup");
        }
        if (getStageDir().exists() && !FileUtils.delete(getStageDir())) {
            throw new IOException("Unable to delete sync staging");
        }
        if (getJournalFile().exists() && !getJournalFile().delete()) {
            throw new IOException("Unable to delete sync transaction journal");
        }
    }

    private void setPhase(Properties journal, String phase) throws IOException {
        journal.setProperty("phase", phase);
        writeJournal(journal);
    }

    private Properties readJournal() throws IOException {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(getJournalFile())) {
            properties.load(input);
        }
        return properties;
    }

    private void writeJournal(Properties properties) throws IOException {
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new IOException("Unable to create sync workspace");
        }
        File pending = new File(workDir, JOURNAL + ".pending");
        try (FileOutputStream output = new FileOutputStream(pending)) {
            properties.store(output, "Titan profile sync transaction");
            output.flush();
            output.getFD().sync();
        }
        File journal = getJournalFile();
        File previous = new File(workDir, JOURNAL + ".previous");
        if (previous.exists() && !previous.delete()) {
            throw new IOException("Unable to clear old transaction journal");
        }
        if (journal.exists() && !journal.renameTo(previous)) {
            throw new IOException("Unable to rotate transaction journal");
        }
        if (!pending.renameTo(journal)) {
            if (previous.exists()) previous.renameTo(journal);
            throw new IOException("Unable to activate transaction journal");
        }
        previous.delete();
    }

    static TreeStats scanTrees(File wtf, File addons) throws IOException {
        long[] wtfStats = scan(wtf);
        long[] addonStats = scan(addons);
        return new TreeStats(wtfStats[0], addonStats[0], wtfStats[1] + addonStats[1]);
    }

    private static long[] scan(File root) throws IOException {
        if (!root.isDirectory()) throw new IOException("Missing tree " + root);
        long files = 0;
        long bytes = 0;
        File[] children = root.listFiles();
        if (children == null) throw new IOException("Unable to list " + root);
        for (File child : children) {
            if (FileUtils.isSymlink(child)) throw new IOException("Unexpected symlink " + child);
            if (child.isDirectory()) {
                long[] nested = scan(child);
                files += nested[0];
                bytes += nested[1];
            }
            else if (child.isFile()) {
                files++;
                bytes += child.length();
            }
            else {
                throw new IOException("Unexpected filesystem entry " + child);
            }
        }
        return new long[]{files, bytes};
    }

    private static void requireStats(
            TreeStats stats, long wtfFiles, long addonFiles, long bytes) throws IOException {
        if (stats.wtfFiles != wtfFiles
                || stats.addonFiles != addonFiles
                || stats.totalBytes != bytes) {
            throw new IOException("Titan profile tree verification failed");
        }
    }

    private File getStageDir() {
        return new File(workDir, STAGE);
    }

    private File getBackupDir() {
        return new File(workDir, BACKUP);
    }

    private File getJournalFile() {
        return new File(workDir, JOURNAL);
    }
}
