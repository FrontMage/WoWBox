package com.winlator.box;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
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
        final List<String> baselineSha256Alternates;
        final String candidateSha256;
        final boolean winebootMayRemove;

        Entry(
                String prefixPath,
                String runtimePath,
                String baselineSha256,
                String candidateSha256) {
            this(prefixPath, runtimePath, baselineSha256, candidateSha256, Collections.emptyList(), false);
        }

        Entry(String prefixPath, String runtimePath, String baselineSha256,
              String candidateSha256, List<String> alternates) {
            this(prefixPath, runtimePath, baselineSha256, candidateSha256, alternates, false);
        }

        Entry(String prefixPath, String runtimePath, String baselineSha256,
              String candidateSha256, List<String> alternates, boolean winebootMayRemove) {
            this.prefixPath = prefixPath;
            this.runtimePath = runtimePath;
            this.baselineSha256 = normalizeSha256(baselineSha256, "baselineSha256");
            this.candidateSha256 = normalizeSha256(candidateSha256, "candidateSha256");
            ArrayList<String> normalized = new ArrayList<>();
            if (alternates != null) for (String value : alternates) {
                String sha = normalizeSha256(value, "baselineSha256Alternates");
                if (sha.equals(this.baselineSha256) || sha.equals(this.candidateSha256) || normalized.contains(sha)) {
                    throw new IllegalArgumentException("Duplicate or candidate Wine prefix baseline");
                }
                normalized.add(sha);
            }
            this.baselineSha256Alternates = Collections.unmodifiableList(normalized);
            if (winebootMayRemove && !isWinebootOptionalPath(prefixPath)) {
                throw new IllegalArgumentException("Wineboot optional policy does not allow: " + prefixPath);
            }
            this.winebootMayRemove = winebootMayRemove;
        }

        boolean matchesBaseline(String value) {
            return baselineSha256.equals(value) || baselineSha256Alternates.contains(value);
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

    /**
     * Seed missing targets only in a newly extracted, not-yet-used prefix.
     * Existing prefixes must continue to use the strict reconcile method.
     */
    static void seedFreshMissing(File runtimeRoot, File prefixWindowsRoot, List<Entry> entries)
            throws IOException {
        if (runtimeRoot == null || !runtimeRoot.isDirectory() ||
                Files.isSymbolicLink(runtimeRoot.toPath()) || prefixWindowsRoot == null ||
                !prefixWindowsRoot.isDirectory() || Files.isSymbolicLink(prefixWindowsRoot.toPath()) ||
                entries == null || entries.isEmpty()) {
            throw new IllegalStateException("Invalid fresh Wine prefix delta roots or entries");
        }
        File runtime = runtimeRoot.getCanonicalFile();
        File prefix = prefixWindowsRoot.getCanonicalFile();
        Set<String> destinations = new HashSet<>();
        ArrayList<PlannedEntry> missing = new ArrayList<>();
        for (Entry entry : entries) {
            validateEntry(entry);
            if (!destinations.add(entry.prefixPath.toLowerCase(Locale.US))) {
                throw new IllegalArgumentException("Duplicate Wine prefix delta path: " + entry.prefixPath);
            }
            File source = resolveContained(runtime, entry.runtimePath);
            File destination = resolveContained(prefix, entry.prefixPath);
            if (!source.getName().equalsIgnoreCase(destination.getName()) ||
                    !source.isFile() || Files.isSymbolicLink(source.toPath()) ||
                    !entry.candidateSha256.equals(sha256(source))) {
                throw new IllegalStateException("Invalid fresh Wine prefix delta source: " + entry.runtimePath);
            }
            if (!Files.notExists(destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!destination.isFile() || Files.isSymbolicLink(destination.toPath())) {
                    throw new IllegalStateException("Unsafe fresh Wine prefix destination: " + entry.prefixPath);
                }
                String actual = sha256(destination);
                if (!entry.matchesBaseline(actual) && !entry.candidateSha256.equals(actual)) {
                    throw new IllegalStateException("Unknown fresh Wine prefix destination hash: " + entry.prefixPath);
                }
            } else if (!entry.winebootMayRemove) {
                missing.add(new PlannedEntry(entry, source, destination, true));
            }
        }
        try {
            for (PlannedEntry item : missing) {
                item.prepared = new File(item.destination.getAbsolutePath() + ".wine-fresh-new");
                if (item.prepared.exists() || Files.isSymbolicLink(item.prepared.toPath())) {
                    throw new IllegalStateException("Stale fresh Wine prefix file: " + item.prepared);
                }
                copyFile(item.source, item.prepared);
                if (!item.entry.candidateSha256.equals(sha256(item.prepared))) {
                    throw new IllegalStateException("Fresh Wine prefix prepared hash mismatch: " + item.entry.prefixPath);
                }
            }
            for (PlannedEntry item : missing) {
                if (item.destination.exists() || Files.isSymbolicLink(item.destination.toPath()) ||
                        !item.prepared.renameTo(item.destination)) {
                    throw new IllegalStateException("Unable to seed fresh Wine prefix: " + item.entry.prefixPath);
                }
                item.activated = true;
            }
        } catch (RuntimeException | IOException failure) {
            for (PlannedEntry item : missing) {
                if (item.activated && item.destination.exists() && !item.destination.delete()) {
                    failure.addSuppressed(new IOException("Cannot undo fresh Wine prefix seed: " + item.destination));
                }
            }
            throw failure;
        } finally {
            for (PlannedEntry item : missing) {
                if (item.prepared != null && item.prepared.exists()) item.prepared.delete();
            }
        }
    }

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
            if (Files.isSymbolicLink(item.destination.toPath())) {
                throw new IllegalStateException("Wine prefix delta destination is unsafe: " + item.destination);
            }
            boolean absent = Files.notExists(item.destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (absent && item.entry.winebootMayRemove) {
                if (!item.entry.candidateSha256.equals(sha256(item.source))) {
                    throw new IllegalStateException("Wine prefix delta source hash mismatch: " + item.entry.runtimePath);
                }
                alreadyCurrent++;
                continue;
            }
            if (!item.destination.isFile()) {
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
            else if (item.entry.matchesBaseline(currentSha256)) {
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
            File destination;
            try { destination = resolveContained(canonicalPrefixRoot, entry.prefixPath); }
            catch (IllegalArgumentException unsafePath) { return false; }
            if (Files.isSymbolicLink(destination.toPath())) return false;
            if (entry.winebootMayRemove &&
                    Files.notExists(destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            if (!destination.isFile() ||
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

    /** Exact entries explicitly removed by the pinned Wine INF in both views. */
    static boolean isWinebootOptionalPath(String path) {
        if (path == null) return false;
        int slash = path.indexOf('/');
        if (slash < 0 || path.indexOf('/', slash + 1) >= 0) return false;
        String dir = path.substring(0, slash);
        if (!"system32".equals(dir) && !"syswow64".equals(dir)) return false;
        switch (path.substring(slash + 1)) {
            case "aspnet_regiis.exe": case "ngen.exe": case "fusion.dll":
            case "diasymreader.dll": case "mscorwks.dll": case "regsvcs.exe":
            case "regasm.exe": case "servicemodelreg.exe":
            case "presentationfontcache.exe": case "comctl32_v6.dll":
                return true;
            default: return false;
        }
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

    private static boolean existsNoFollowOrThrow(File file, String description) throws IOException {
        if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true;
        if (Files.notExists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        throw new IOException("Cannot determine Wine prefix " + description + ": " + file);
    }

    private static void recoverInterruptedTransaction(List<PlannedEntry> plan)
            throws IOException {
        boolean hasBackup = false;
        boolean hasPrepared = false;
        int candidateDestinations = 0;
        int optionalAbsentDestinations = 0;
        Set<PlannedEntry> originalCandidates = new HashSet<>();

        // Preflight every backup, staged file and target before mutating any of
        // them. A candidate without a backup and without a staged file can be
        // an entry which was already current before this transaction began.
        for (PlannedEntry item : plan) {
            item.prepared = new File(item.destination.getAbsolutePath() + ".wine-delta-new");
            item.backup = new File(item.destination.getAbsolutePath() + ".wine-delta-backup");
            if (Files.isSymbolicLink(item.prepared.toPath()) || Files.isSymbolicLink(item.backup.toPath()) ||
                    Files.isSymbolicLink(item.destination.toPath())) {
                throw new IllegalStateException("Wine prefix delta transaction path is a symlink: " + item.entry.prefixPath);
            }
            boolean prepared = existsNoFollowOrThrow(item.prepared, "prepared file");
            boolean backup = existsNoFollowOrThrow(item.backup, "backup");
            boolean destination = existsNoFollowOrThrow(item.destination, "destination");
            if (prepared) {
                hasPrepared = true;
                if (!item.prepared.isFile() || !item.entry.candidateSha256.equals(sha256(item.prepared))) {
                    throw new IllegalStateException("Wine prefix delta stale prepared hash mismatch: " + item.entry.prefixPath);
                }
            }
            if (backup) {
                hasBackup = true;
                if (!item.backup.isFile() || !item.entry.matchesBaseline(sha256(item.backup))) {
                    throw new IllegalStateException("Wine prefix delta stale backup hash mismatch: " + item.entry.prefixPath);
                }
            }
            if (!destination) {
                if (backup) continue;
                if (item.entry.winebootMayRemove && !prepared) {
                    optionalAbsentDestinations++;
                    continue;
                }
                throw new IllegalStateException("Wine prefix delta destination is missing without a backup: " + item.entry.prefixPath);
            }
            if (!item.destination.isFile()) {
                throw new IllegalStateException("Wine prefix delta destination is unsafe during recovery: " + item.entry.prefixPath);
            }
            String hash = sha256(item.destination);
            if (item.entry.candidateSha256.equals(hash)) {
                candidateDestinations++;
                if (!backup) {
                    if (prepared) throw new IllegalStateException("Ambiguous current and prepared Wine prefix entry: " + item.entry.prefixPath);
                    originalCandidates.add(item);
                }
            } else if (item.entry.matchesBaseline(hash)) {
                if (backup) throw new IllegalStateException("Ambiguous baseline and backup Wine prefix entry: " + item.entry.prefixPath);
            } else {
                throw new IllegalStateException("Wine prefix delta rejected unknown destination hash during recovery: " + item.entry.prefixPath);
            }
        }
        if (!hasBackup && !hasPrepared) return;

        if (hasBackup && candidateDestinations + optionalAbsentDestinations == plan.size()) {
            // Every effective target is already the candidate; only cleanup was interrupted.
            for (PlannedEntry item : plan) {
                deleteTransactionFile(item.backup, item.entry.prefixPath);
                deleteTransactionFile(item.prepared, item.entry.prefixPath);
            }
            return;
        }

        // Without a durable per-entry journal, a current candidate without a
        // backup could have been current initially or could have lost its
        // backup. Never guess in a mixed partially activated transaction.
        if (hasBackup && !originalCandidates.isEmpty()) {
            throw new IllegalStateException("Ambiguous Wine prefix candidate without backup");
        }

        if (hasBackup) {
            for (int i = plan.size() - 1; i >= 0; i--) {
                PlannedEntry item = plan.get(i);
                boolean backup = existsNoFollowOrThrow(item.backup, "backup");
                boolean destination = existsNoFollowOrThrow(item.destination, "destination");
                if (backup) {
                    if (destination && !item.destination.delete()) {
                        throw new IOException("Unable to remove interrupted Wine prefix candidate: " + item.entry.prefixPath);
                    }
                    if (!item.backup.renameTo(item.destination)) {
                        throw new IOException("Unable to restore interrupted Wine prefix backup: " + item.entry.prefixPath);
                    }
                }
                deleteTransactionFile(item.prepared, item.entry.prefixPath);
            }
            for (PlannedEntry item : plan) {
                if (item.entry.winebootMayRemove && !existsNoFollowOrThrow(item.destination, "destination")) continue;
                if (!item.destination.isFile()) throw new IllegalStateException("Wine prefix rollback is missing: " + item.entry.prefixPath);
                String hash = sha256(item.destination);
                if (originalCandidates.contains(item) ? !item.entry.candidateSha256.equals(hash) : !item.entry.matchesBaseline(hash)) {
                    throw new IllegalStateException("Wine prefix rollback verification failed: " + item.entry.prefixPath);
                }
            }
            return;
        }

        // Only prepared files exist. No backup means no target was replaced;
        // mixed original-baseline and originally-current entries are valid.
        for (PlannedEntry item : plan) deleteTransactionFile(item.prepared, item.entry.prefixPath);
    }

    private static void deleteTransactionFile(File file, String path) throws IOException {
        if (file != null && existsNoFollowOrThrow(file, "transaction file") && !file.delete()) {
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
