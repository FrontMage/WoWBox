package com.winlator.box;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies a manifest-selected set of Wine-owned PE changes to an existing
 * prefix.  Every entry is fully preflighted before the first destination is
 * touched, and a destination is replaceable only when it is the declared
 * baseline.  DX and XInput files remain owned by their dedicated managed
 * layers and are never accepted here.
 */
final class WinePrefixDeltaReconciler {
    private static final String[] PREFIX_ROOTS = {
            "system32/",
            "sysarm32/",
            "syswow64/"
    };
    private static final String[] RUNTIME_ROOTS = {
            "lib/wine/aarch64-windows/",
            "lib/wine/x86_64-windows/",
            "lib/wine/i386-windows/"
    };

    static final class Entry {
        final String prefixPath;
        final String runtimePath;
        final String baselineSha256;
        final String candidateSha256;

        Entry(
                String prefixPath,
                String runtimePath,
                String baselineSha256,
                String candidateSha256) {
            this.prefixPath = prefixPath;
            this.runtimePath = runtimePath;
            this.baselineSha256 = normalizeSha256(baselineSha256, "baselineSha256");
            this.candidateSha256 = normalizeSha256(candidateSha256, "candidateSha256");
        }
    }

    static final class Result {
        final int replaced;
        final int alreadyCurrent;

        Result(int replaced, int alreadyCurrent) {
            this.replaced = replaced;
            this.alreadyCurrent = alreadyCurrent;
        }
    }

    private static final class PlannedEntry {
        final Entry entry;
        final File source;
        final File destination;
        boolean replace;
        File prepared;
        File backup;
        boolean activated;

        PlannedEntry(Entry entry, File source, File destination, boolean replace) {
            this.entry = entry;
            this.source = source;
            this.destination = destination;
            this.replace = replace;
        }
    }

    private WinePrefixDeltaReconciler() {}

    static Result reconcile(File runtimeRoot, File prefixWindowsRoot, List<Entry> entries)
            throws IOException {
        if (runtimeRoot == null || !runtimeRoot.isDirectory() || Files.isSymbolicLink(runtimeRoot.toPath())) {
            throw new IllegalStateException("Wine runtime root is missing or unsafe: " + runtimeRoot);
        }
        if (prefixWindowsRoot == null ||
                !prefixWindowsRoot.isDirectory() ||
                Files.isSymbolicLink(prefixWindowsRoot.toPath())) {
            throw new IllegalStateException("Wine prefix Windows root is missing or unsafe: " +
                    prefixWindowsRoot);
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Wine prefix delta has no entries");
        }

        File canonicalRuntimeRoot = runtimeRoot.getCanonicalFile();
        File canonicalPrefixRoot = prefixWindowsRoot.getCanonicalFile();
        ArrayList<PlannedEntry> plan = new ArrayList<>();
        Set<String> seenDestinations = new HashSet<>();
        int alreadyCurrent = 0;

        for (Entry entry : entries) {
            validateEntry(entry);
            String destinationKey = entry.prefixPath.toLowerCase(Locale.US);
            if (!seenDestinations.add(destinationKey)) {
                throw new IllegalArgumentException("Duplicate Wine prefix delta path: " +
                        entry.prefixPath);
            }

            File source = resolveContained(canonicalRuntimeRoot, entry.runtimePath);
            File destination = resolveContained(canonicalPrefixRoot, entry.prefixPath);
            if (!source.getName().equalsIgnoreCase(destination.getName())) {
                throw new IllegalArgumentException("Wine prefix delta filenames differ: " +
                        entry.runtimePath + " -> " + entry.prefixPath);
            }
            plan.add(new PlannedEntry(entry, source, destination, false));
        }

        recoverInterruptedTransaction(plan);

        for (PlannedEntry item : plan) {
            if (!item.source.isFile() || Files.isSymbolicLink(item.source.toPath())) {
                throw new IllegalStateException("Wine prefix delta source is missing or unsafe: " +
                        item.source);
            }
            if (!item.destination.isFile() ||
                    Files.isSymbolicLink(item.destination.toPath())) {
                throw new IllegalStateException(
                        "Wine prefix delta destination is missing or unsafe: " +
                                item.destination);
            }

            String sourceSha256 = sha256(item.source);
            if (!item.entry.candidateSha256.equals(sourceSha256)) {
                throw new IllegalStateException("Wine prefix delta source hash mismatch: " +
                        item.entry.runtimePath);
            }

            String currentSha256 = sha256(item.destination);
            if (item.entry.candidateSha256.equals(currentSha256)) {
                alreadyCurrent++;
            }
            else if (item.entry.baselineSha256.equals(currentSha256)) {
                item.replace = true;
            }
            else {
                throw new IllegalStateException(
                        "Wine prefix delta rejected unknown destination hash: " +
                                item.entry.prefixPath + " sha256=" + currentSha256);
            }
        }

        ArrayList<PlannedEntry> replacements = new ArrayList<>();
        for (PlannedEntry item : plan) {
            if (item.replace) replacements.add(item);
        }
        if (replacements.isEmpty()) return new Result(0, alreadyCurrent);

        try {
            for (PlannedEntry item : replacements) {
                item.prepared = new File(item.destination.getAbsolutePath() + ".wine-delta-new");
                item.backup = new File(item.destination.getAbsolutePath() + ".wine-delta-backup");
                if (item.prepared.exists() || item.backup.exists() ||
                        Files.isSymbolicLink(item.prepared.toPath()) ||
                        Files.isSymbolicLink(item.backup.toPath())) {
                    throw new IllegalStateException(
                            "Wine prefix delta has stale transaction files for " +
                                    item.entry.prefixPath);
                }
                copyFile(item.source, item.prepared);
                if (!item.entry.candidateSha256.equals(sha256(item.prepared))) {
                    throw new IllegalStateException(
                            "Wine prefix delta prepared hash mismatch: " +
                                    item.entry.prefixPath);
                }
            }

            for (PlannedEntry item : replacements) {
                if (!item.destination.renameTo(item.backup)) {
                    throw new IllegalStateException(
                            "Unable to back up Wine prefix delta destination: " +
                                    item.entry.prefixPath);
                }
                if (!item.prepared.renameTo(item.destination)) {
                    if (!item.backup.renameTo(item.destination)) {
                        throw new IllegalStateException(
                                "Unable to activate or restore Wine prefix delta destination: " +
                                        item.entry.prefixPath);
                    }
                    throw new IllegalStateException(
                            "Unable to activate Wine prefix delta destination: " +
                                    item.entry.prefixPath);
                }
                item.activated = true;
                if (!item.entry.candidateSha256.equals(sha256(item.destination))) {
                    throw new IllegalStateException(
                            "Activated Wine prefix delta hash mismatch: " +
                                    item.entry.prefixPath);
                }
            }

            for (PlannedEntry item : replacements) {
                // All destinations are committed and verified at this point.
                // A failed cleanup must not roll back an already-committed
                // multi-file transaction after earlier backups were removed.
                // Leaving the backup makes the next run fail closed.
                item.backup.delete();
            }
            return new Result(replacements.size(), alreadyCurrent);
        }
        catch (RuntimeException | IOException failure) {
            IOException rollbackFailure = rollback(replacements);
            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
            throw failure;
        }
        finally {
            for (PlannedEntry item : replacements) {
                if (item.prepared != null && item.prepared.exists()) item.prepared.delete();
            }
        }
    }

    static boolean candidateStateMatches(File prefixWindowsRoot, List<Entry> entries)
            throws IOException {
        if (prefixWindowsRoot == null ||
                !prefixWindowsRoot.isDirectory() ||
                Files.isSymbolicLink(prefixWindowsRoot.toPath()) ||
                entries == null ||
                entries.isEmpty()) {
            return false;
        }

        File canonicalPrefixRoot = prefixWindowsRoot.getCanonicalFile();
        Set<String> seenDestinations = new HashSet<>();
        for (Entry entry : entries) {
            validateEntry(entry);
            if (!seenDestinations.add(entry.prefixPath.toLowerCase(Locale.US))) return false;
            File destination = resolveContained(canonicalPrefixRoot, entry.prefixPath);
            if (!destination.isFile() || Files.isSymbolicLink(destination.toPath()) ||
                    !entry.candidateSha256.equals(sha256(destination))) {
                return false;
            }
        }
        return true;
    }

    static boolean isAllowedPrefixPath(String path) {
        if (!isSafeRelativePath(path)) return false;
        boolean allowedRoot = false;
        for (String root : PREFIX_ROOTS) {
            if (path.startsWith(root) && path.length() > root.length()) {
                allowedRoot = true;
                break;
            }
        }
        if (!allowedRoot) return false;

        String basename = new File(path).getName().toLowerCase(Locale.US);
        return !(basename.startsWith("d3d") && basename.endsWith(".dll")) &&
                !"dxgi.dll".equals(basename) &&
                !(basename.startsWith("xinput") && basename.endsWith(".dll"));
    }

    static boolean isAllowedRuntimePath(String path) {
        if (!isSafeRelativePath(path)) return false;
        for (String root : RUNTIME_ROOTS) {
            if (path.startsWith(root) && path.length() > root.length()) return true;
        }
        return false;
    }

    static String sha256(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void validateEntry(Entry entry) {
        if (entry == null) throw new IllegalArgumentException("Wine prefix delta entry is null");
        if (!isAllowedPrefixPath(entry.prefixPath)) {
            throw new IllegalArgumentException(
                    "Unsafe or managed-layer-owned Wine prefix delta path: " +
                            entry.prefixPath);
        }
        if (!isAllowedRuntimePath(entry.runtimePath)) {
            throw new IllegalArgumentException(
                    "Unsafe Wine prefix delta runtime path: " + entry.runtimePath);
        }
        if (entry.baselineSha256.equals(entry.candidateSha256)) {
            throw new IllegalArgumentException(
                    "Wine prefix delta entry does not change content: " + entry.prefixPath);
        }
    }

    private static String normalizeSha256(String value, String field) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.US);
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Invalid Wine prefix delta " + field);
        }
        return normalized;
    }

    private static boolean isSafeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
            return false;
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) ||
                    part.indexOf(':') >= 0 || containsControlCharacter(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static File resolveContained(File root, String relativePath) throws IOException {
        File lexical = new File(root, relativePath);
        File cursor = lexical;
        while (cursor != null && !cursor.equals(root)) {
            if (Files.isSymbolicLink(cursor.toPath())) {
                throw new IllegalArgumentException(
                        "Wine prefix delta path contains a symlink: " + relativePath);
            }
            cursor = cursor.getParentFile();
        }
        File result = lexical.getCanonicalFile();
        String rootPath = root.getCanonicalPath();
        String resultPath = result.getCanonicalPath();
        if (!resultPath.startsWith(rootPath + File.separator)) {
            throw new IllegalArgumentException("Wine prefix delta path escapes root: " +
                    relativePath);
        }
        return result;
    }

    private static void copyFile(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Unable to create Wine prefix delta destination directory");
        }
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            output.getFD().sync();
        }
        destination.setReadable(true, true);
        destination.setWritable(true, true);
        destination.setExecutable(source.canExecute(), false);
    }

    private static void recoverInterruptedTransaction(List<PlannedEntry> plan)
            throws IOException {
        boolean hasBackup = false;
        boolean hasPrepared = false;
        int baselineDestinations = 0;
        int candidateDestinations = 0;

        for (PlannedEntry item : plan) {
            item.prepared = new File(item.destination.getAbsolutePath() + ".wine-delta-new");
            item.backup = new File(item.destination.getAbsolutePath() + ".wine-delta-backup");

            if (Files.isSymbolicLink(item.prepared.toPath()) ||
                    Files.isSymbolicLink(item.backup.toPath())) {
                throw new IllegalStateException(
                        "Wine prefix delta transaction path is a symlink for " +
                                item.entry.prefixPath);
            }
            if (item.prepared.exists()) {
                hasPrepared = true;
                if (!item.prepared.isFile() ||
                        !item.entry.candidateSha256.equals(sha256(item.prepared))) {
                    throw new IllegalStateException(
                            "Wine prefix delta stale prepared hash mismatch: " +
                                    item.entry.prefixPath);
                }
            }
            if (item.backup.exists()) {
                hasBackup = true;
                if (!item.backup.isFile() ||
                        !item.entry.baselineSha256.equals(sha256(item.backup))) {
                    throw new IllegalStateException(
                            "Wine prefix delta stale backup hash mismatch: " +
                                    item.entry.prefixPath);
                }
            }
        }

        if (!hasBackup && !hasPrepared) return;

        for (PlannedEntry item : plan) {
            if (!item.destination.exists()) {
                if (!item.backup.exists()) {
                    throw new IllegalStateException(
                            "Wine prefix delta destination is missing without a backup: " +
                                    item.entry.prefixPath);
                }
                continue;
            }
            if (!item.destination.isFile() ||
                    Files.isSymbolicLink(item.destination.toPath())) {
                throw new IllegalStateException(
                        "Wine prefix delta destination is unsafe during recovery: " +
                                item.entry.prefixPath);
            }
            String destinationSha256 = sha256(item.destination);
            if (item.entry.baselineSha256.equals(destinationSha256)) baselineDestinations++;
            else if (item.entry.candidateSha256.equals(destinationSha256)) candidateDestinations++;
            else {
                throw new IllegalStateException(
                        "Wine prefix delta destination has an unknown recovery hash: " +
                                item.entry.prefixPath);
            }
        }

        if (hasBackup && candidateDestinations == plan.size()) {
            // Activation and verification completed for every entry; a process
            // interruption only prevented transaction-artifact cleanup.
            for (PlannedEntry item : plan) {
                deleteTransactionFile(item.backup, item.entry.prefixPath);
                deleteTransactionFile(item.prepared, item.entry.prefixPath);
            }
            return;
        }

        if (hasBackup) {
            // Activation stopped part way through. Roll the entire set back to
            // the declared baseline before attempting a fresh transaction.
            for (int i = plan.size() - 1; i >= 0; i--) {
                PlannedEntry item = plan.get(i);
                if (!item.destination.exists()) {
                    if (!item.backup.exists() || !item.backup.renameTo(item.destination)) {
                        throw new IOException(
                                "Unable to restore interrupted Wine prefix delta backup: " +
                                        item.entry.prefixPath);
                    }
                }
                else {
                    String destinationSha256 = sha256(item.destination);
                    if (item.entry.candidateSha256.equals(destinationSha256)) {
                        if (!item.backup.exists()) {
                            throw new IllegalStateException(
                                    "Wine prefix delta cannot roll back candidate without backup: " +
                                            item.entry.prefixPath);
                        }
                        if (!item.destination.delete() ||
                                !item.backup.renameTo(item.destination)) {
                            throw new IOException(
                                    "Unable to roll back interrupted Wine prefix delta: " +
                                            item.entry.prefixPath);
                        }
                    }
                    else {
                        deleteTransactionFile(item.backup, item.entry.prefixPath);
                    }
                }
                deleteTransactionFile(item.prepared, item.entry.prefixPath);
            }
            for (PlannedEntry item : plan) {
                if (!item.destination.isFile() ||
                        !item.entry.baselineSha256.equals(sha256(item.destination))) {
                    throw new IllegalStateException(
                            "Wine prefix delta rollback verification failed: " +
                                    item.entry.prefixPath);
                }
            }
            return;
        }

        // Prepared files without backups mean activation never started. The
        // destinations must be uniformly baseline (pre-activation) or candidate
        // (post-commit cleanup); a mixed set has no safe automatic recovery.
        if (baselineDestinations != plan.size() && candidateDestinations != plan.size()) {
            throw new IllegalStateException(
                    "Wine prefix delta has an ambiguous interrupted transaction");
        }
        for (PlannedEntry item : plan) {
            deleteTransactionFile(item.prepared, item.entry.prefixPath);
        }
    }

    private static void deleteTransactionFile(File file, String path) throws IOException {
        if (file != null && file.exists() && !file.delete()) {
            throw new IOException("Unable to clean Wine prefix delta transaction file for " +
                    path);
        }
    }

    private static IOException rollback(List<PlannedEntry> replacements) {
        IOException failure = null;
        for (int i = replacements.size() - 1; i >= 0; i--) {
            PlannedEntry item = replacements.get(i);
            if (!item.activated) continue;
            if (item.destination.exists() && !item.destination.delete() && failure == null) {
                failure = new IOException(
                        "Unable to remove failed Wine prefix delta destination: " +
                                item.entry.prefixPath);
            }
            if (item.backup.exists() && !item.backup.renameTo(item.destination) &&
                    failure == null) {
                failure = new IOException(
                        "Unable to restore Wine prefix delta destination: " +
                                item.entry.prefixPath);
            }
        }
        return failure;
    }
}
