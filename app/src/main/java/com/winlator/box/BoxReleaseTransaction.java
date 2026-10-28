package com.winlator.box;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Process-crash-recoverable activation of an explicitly enumerated release.
 * Callers must stop Wine, verify the selected package and hold their normal
 * installation lock throughout preparation, activation and recovery. The
 * supplied candidates must already be independently authenticated.
 */
public final class BoxReleaseTransaction {
    private static final String MAGIC = "wowbox-release-transaction-v1";
    private static final long FREE_SPACE_RESERVE = 16L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    private BoxReleaseTransaction() {}

    public static final class Entry {
        public final String relativeTarget;
        public final File candidate;
        /** null denotes a target that must not exist before activation. */
        public final String expectedBeforeSha256;
        public final String expectedAfterSha256;
        public final boolean directory;

        public Entry(String relativeTarget, File candidate, String expectedBeforeSha256,
                     String expectedAfterSha256, boolean directory) {
            this.relativeTarget = relativeTarget;
            this.candidate = candidate;
            this.expectedBeforeSha256 = expectedBeforeSha256;
            this.expectedAfterSha256 = expectedAfterSha256;
            this.directory = directory;
        }
    }

    interface FaultInjector {
        void at(String point, int index) throws IOException;
    }

    interface SpaceProbe {
        long usableBytes(File parent);
    }

    /** Runs while rollback backups still exist; throw to reject activation. */
    public interface PreCommitValidator {
        void validate() throws Exception;
    }

    /** Compares the device identities of two existing parent directories. */
    public interface FileSystemVerifier {
        boolean sameFilesystem(File first, File second) throws IOException;
    }

    /** The new release is committed; do not roll it back as an uncommitted failure. */
    public static final class CommittedCleanupException extends IOException {
        CommittedCleanupException(Throwable cause) {
            super("Release committed, but final verification or cleanup failed", cause);
        }
    }

    private static final FaultInjector NO_FAULT = (point, index) -> {};
    private static final PreCommitValidator NO_VALIDATION = () -> {};
    private static final FileSystemVerifier NIO_FILESYSTEM_VERIFIER = (first, second) -> {
        try {
            return Files.getFileStore(first.toPath()).equals(Files.getFileStore(second.toPath()));
        } catch (UnsupportedOperationException error) {
            throw new IOException("Filesystem identity unavailable; use a platform adapter", error);
        }
    };

    public static synchronized void apply(File appRoot, File journalDir, List<Entry> entries)
            throws IOException {
        apply(appRoot, journalDir, entries, NO_VALIDATION, NIO_FILESYSTEM_VERIFIER, NO_FAULT, File::getUsableSpace);
    }

    public static synchronized void apply(File appRoot, File journalDir, List<Entry> entries,
                                          PreCommitValidator validator) throws IOException {
        if (validator == null) throw new IOException("Missing release validator");
        apply(appRoot, journalDir, entries, validator, NIO_FILESYSTEM_VERIFIER, NO_FAULT, File::getUsableSpace);
    }

    /** Android callers should compare android.system.Os.stat(...).st_dev here. */
    public static synchronized void apply(File appRoot, File journalDir, List<Entry> entries,
                                          PreCommitValidator validator,
                                          FileSystemVerifier fileSystemVerifier) throws IOException {
        if (validator == null) throw new IOException("Missing release validator");
        if (fileSystemVerifier == null) throw new IOException("Missing filesystem verifier");
        apply(appRoot, journalDir, entries, validator, fileSystemVerifier, NO_FAULT, File::getUsableSpace);
    }

    /** Variant for an operation that does not need a separate validation callback. */
    public static synchronized void apply(File appRoot, File journalDir, List<Entry> entries,
                                          FileSystemVerifier fileSystemVerifier) throws IOException {
        if (fileSystemVerifier == null) throw new IOException("Missing filesystem verifier");
        apply(appRoot, journalDir, entries, NO_VALIDATION, fileSystemVerifier, NO_FAULT, File::getUsableSpace);
    }

    static synchronized void apply(File appRoot, File journalDir, List<Entry> entries,
                                   FaultInjector faults, SpaceProbe space) throws IOException {
        apply(appRoot, journalDir, entries, NO_VALIDATION, NIO_FILESYSTEM_VERIFIER, faults, space);
    }

    static synchronized void apply(File appRoot, File journalDir, List<Entry> entries,
                                   PreCommitValidator validator, FaultInjector faults,
                                   SpaceProbe space) throws IOException {
        apply(appRoot, journalDir, entries, validator, NIO_FILESYSTEM_VERIFIER, faults, space);
    }

    static synchronized void apply(File appRoot, File journalDir, List<Entry> entries,
                                   PreCommitValidator validator, FileSystemVerifier fileSystemVerifier,
                                   FaultInjector faults, SpaceProbe space) throws IOException {
        Path root = checkedRoot(appRoot);
        Path journal = checkedJournal(root, journalDir);
        if (Files.exists(journal, NOFOLLOW)) throw new IOException("Release recovery required");
        if (entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES)
            throw new IOException("Invalid release entry count");
        List<Plan> plans = validateEntries(root, journal, entries);
        for (Plan plan : plans) {
            if (!fileSystemVerifier.sameFilesystem(journal.getParent().toFile(), plan.target.getParent().toFile()))
                throw new IOException("Release target is on a different filesystem: " + plan.target);
        }

        long bytes = 0;
        for (Plan plan : plans) {
            long size = storageBytes(plan.source);
            if (size > Long.MAX_VALUE - bytes) throw new IOException("Release size overflow");
            bytes += size;
        }
        if (bytes > Long.MAX_VALUE - FREE_SPACE_RESERVE ||
                space.usableBytes(journal.getParent().toFile()) < bytes + FREE_SPACE_RESERVE)
            throw new IOException("Insufficient free space to stage the release");

        Files.createDirectory(journal);
        boolean committed = false;
        try {
            writeSync(journal.resolve("owner.tmp"), MAGIC.getBytes(StandardCharsets.UTF_8));
            atomicMove(journal.resolve("owner.tmp"), journal.resolve("owner"));
            Files.createDirectory(journal.resolve("staged"));
            Files.createDirectory(journal.resolve("backup"));
            for (int i = 0; i < plans.size(); i++) {
                Plan plan = plans.get(i);
                copyCandidate(plan.source, staged(journal, i), plan.entry.directory, faults, i);
                requireHash(staged(journal, i), plan.entry.directory,
                        plan.entry.expectedAfterSha256, "Staged candidate");
                faults.at("after-stage", i);
            }
            // Recheck after copying, before any target is renamed.
            for (Plan plan : plans) requireBefore(plan);
            writeManifest(journal, plans);
            for (int i = 0; i < plans.size(); i++) {
                Plan plan = plans.get(i);
                requireBefore(plan);
                if (plan.entry.expectedBeforeSha256 != null) {
                    atomicMove(plan.target, backup(journal, i));
                    faults.at("after-backup", i);
                }
                atomicMove(staged(journal, i), plan.target);
                faults.at("after-activate", i);
                requireHash(plan.target, plan.entry.directory, plan.entry.expectedAfterSha256,
                        "Activated candidate");
            }
            for (Plan plan : plans) requireHash(plan.target, plan.entry.directory,
                    plan.entry.expectedAfterSha256, "Activated candidate");
            faults.at("before-validation", plans.size());
            try {
                validator.validate();
            }
            catch (IOException | RuntimeException failure) {
                throw failure;
            }
            catch (Exception failure) {
                throw new IOException("Release validation failed", failure);
            }
            // Validation must not silently modify a target that was just checked.
            for (Plan plan : plans) requireHash(plan.target, plan.entry.directory,
                    plan.entry.expectedAfterSha256, "Validated candidate");
            faults.at("after-validation", plans.size());
            writeSync(journal.resolve("commit.tmp"), MAGIC.getBytes(StandardCharsets.UTF_8));
            atomicMove(journal.resolve("commit.tmp"), journal.resolve("commit"));
            committed = true;
            faults.at("after-commit", plans.size());
            recover(appRoot, journalDir);
        }
        catch (IOException | RuntimeException failure) {
            if (committed) {
                CommittedCleanupException committedFailure = new CommittedCleanupException(failure);
                try { recover(appRoot, journalDir); }
                catch (IOException cleanup) { committedFailure.addSuppressed(cleanup); }
                throw committedFailure;
            }
            try { recover(appRoot, journalDir); }
            catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    /** Recover an interrupted transaction before permitting a Wine launch. */
    public static synchronized void recover(File appRoot, File journalDir) throws IOException {
        Path root = checkedRoot(appRoot);
        Path journal = checkedJournal(root, journalDir);
        if (!Files.exists(journal, NOFOLLOW)) return;
        if (!Files.isDirectory(journal, NOFOLLOW) || Files.isSymbolicLink(journal))
            throw new IOException("Unsafe release journal");
        Path owner = journal.resolve("owner");
        if (!Files.exists(owner, NOFOLLOW)) {
            // Only an empty journal or a partial owner marker is possible
            // before activation. Never guess about any other unknown entry.
            List<Path> children = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.list(journal)) { stream.forEach(children::add); }
            if (children.isEmpty()) { Files.delete(journal); return; }
            Path partial = journal.resolve("owner.tmp");
            if (children.size() == 1 && children.get(0).equals(partial) &&
                    Files.isRegularFile(partial, NOFOLLOW) &&
                    MAGIC.startsWith(new String(Files.readAllBytes(partial), StandardCharsets.UTF_8))) {
                Files.delete(partial);
                Files.delete(journal);
                return;
            }
            throw new IOException("Unknown release journal owner");
        }
        if (!Files.isRegularFile(owner, NOFOLLOW) ||
                !MAGIC.equals(new String(Files.readAllBytes(owner), StandardCharsets.UTF_8)))
            throw new IOException("Unknown release journal owner");
        for (String name : new String[]{"staged", "backup"}) {
            Path child = journal.resolve(name);
            if (Files.exists(child, NOFOLLOW) &&
                    (Files.isSymbolicLink(child) || !Files.isDirectory(child, NOFOLLOW)))
                throw new IOException("Unsafe release journal directory: " + child);
        }
        Path manifest = journal.resolve("manifest");
        if (!Files.exists(manifest, NOFOLLOW)) {
            // Activation cannot start before an atomically published manifest.
            Path backupDir = journal.resolve("backup");
            if (Files.isDirectory(backupDir, NOFOLLOW)) {
                try (java.util.stream.Stream<Path> children = Files.list(backupDir)) {
                    if (children.findAny().isPresent()) throw new IOException("Journal without manifest has backups");
                }
            }
            cleanupJournal(journal);
            return;
        }
        List<Plan> plans = readManifest(root, journal, manifest);
        Path commit = journal.resolve("commit");
        if (Files.exists(commit, NOFOLLOW)) {
            if (!Files.isRegularFile(commit, NOFOLLOW) ||
                    !MAGIC.equals(new String(Files.readAllBytes(commit), StandardCharsets.UTF_8)))
                throw new IOException("Invalid release commit marker");
            for (Plan plan : plans) requireHash(plan.target, plan.entry.directory,
                    plan.entry.expectedAfterSha256, "Committed target");
            cleanupJournal(journal);
            return;
        }

        // Validate every recovery action first so an unknown file cannot lead
        // to a partly rolled-back Wine/prefix/FEX/spec combination.
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            Path backup = backup(journal, i);
            requireNoSymlinkParents(root, plan.target);
            boolean backupExists = Files.exists(backup, NOFOLLOW);
            boolean targetExists = Files.exists(plan.target, NOFOLLOW);
            if (backupExists) {
                if (plan.entry.expectedBeforeSha256 == null)
                    throw new IOException("Unexpected release backup: " + plan.target);
                requireHash(backup, plan.entry.directory, plan.entry.expectedBeforeSha256, "Release backup");
                if (targetExists) requireHash(plan.target, plan.entry.directory,
                        plan.entry.expectedAfterSha256, "Uncommitted target");
            }
            else if (plan.entry.expectedBeforeSha256 != null) {
                requireHash(plan.target, plan.entry.directory, plan.entry.expectedBeforeSha256,
                        "Original target");
            }
            else if (targetExists) {
                requireHash(plan.target, plan.entry.directory, plan.entry.expectedAfterSha256,
                        "New uncommitted target");
            }
        }
        for (int i = plans.size() - 1; i >= 0; i--) {
            Plan plan = plans.get(i);
            Path backup = backup(journal, i);
            if (Files.exists(backup, NOFOLLOW)) {
                if (Files.exists(plan.target, NOFOLLOW)) deleteTree(plan.target);
                atomicMove(backup, plan.target);
            }
            else if (plan.entry.expectedBeforeSha256 == null && Files.exists(plan.target, NOFOLLOW)) {
                deleteTree(plan.target);
            }
        }
        cleanupJournal(journal);
    }

    /** File SHA-256 or a deterministic tree hash including safe symlinks and modes. */
    public static String sha256(File target, boolean directory) throws IOException {
        Path path = target.toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) ||
                (directory ? !Files.isDirectory(path, NOFOLLOW) : !Files.isRegularFile(path, NOFOLLOW)))
            throw new IOException("Unexpected file type: " + path);
        MessageDigest digest = newDigest();
        if (!directory) hashFile(path, digest);
        else {
            List<Path> paths = new ArrayList<>();
            try (java.util.stream.Stream<Path> walk = Files.walk(path)) { walk.forEach(paths::add); }
            paths.sort(Comparator.comparing(item -> path.relativize(item).toString()));
            for (Path item : paths) {
                BasicFileAttributes attr = Files.readAttributes(item, BasicFileAttributes.class, NOFOLLOW);
                String relative = path.relativize(item).toString().replace(File.separatorChar, '/');
                update(digest, relative);
                if (attr.isSymbolicLink()) {
                    Path link = Files.readSymbolicLink(item);
                    checkRelativeLink(path, item, link);
                    update(digest, "L");
                    update(digest, link.toString());
                }
                else if (attr.isDirectory()) { update(digest, "D"); update(digest, mode(item)); }
                else if (attr.isRegularFile()) {
                    update(digest, "F"); update(digest, mode(item));
                    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(attr.size()).array());
                    hashFile(item, digest);
                }
                else throw new IOException("Unsupported Wine tree entry: " + item);
            }
        }
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest()) out.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return out.toString();
    }

    private static String mode(Path path) throws IOException {
        Set<PosixFilePermission> p = Files.getPosixFilePermissions(path, NOFOLLOW);
        StringBuilder result = new StringBuilder();
        for (PosixFilePermission permission : PosixFilePermission.values()) result.append(p.contains(permission) ? '1' : '0');
        return result.toString();
    }

    private static void update(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void hashFile(Path path, MessageDigest digest) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] data = new byte[65536]; int count;
            while ((count = in.read(data)) != -1) digest.update(data, 0, count);
        }
    }

    private static MessageDigest newDigest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException(error); }
    }

    private static final class Plan {
        final Entry entry;
        final Path source;
        final Path target;
        Plan(Entry entry, Path source, Path target) { this.entry = entry; this.source = source; this.target = target; }
    }

    private static List<Plan> validateEntries(Path root, Path journal, List<Entry> entries) throws IOException {
        List<Plan> result = new ArrayList<>();
        Set<Path> targets = new HashSet<>();
        for (Entry entry : entries) {
            if (entry == null || entry.candidate == null || !validHash(entry.expectedAfterSha256) ||
                    (entry.expectedBeforeSha256 != null && !validHash(entry.expectedBeforeSha256)))
                throw new IOException("Invalid release entry");
            Path target = checkedTarget(root, entry.relativeTarget);
            if (target.startsWith(journal) || journal.startsWith(target) || !targets.add(target))
                throw new IOException("Duplicate or overlapping journal target");
            for (Path other : targets) {
                if (!other.equals(target) && (other.startsWith(target) || target.startsWith(other)))
                    throw new IOException("Overlapping release targets");
            }
            Path source = entry.candidate.toPath().toAbsolutePath().normalize();
            if (source.startsWith(journal) || Files.isSymbolicLink(source))
                throw new IOException("Unsafe release candidate");
            Plan plan = new Plan(entry, source, target);
            requireNoSymlinkParents(root, target);
            requireBefore(plan);
            requireHash(source, entry.directory, entry.expectedAfterSha256, "Release candidate");
            result.add(plan);
        }
        return result;
    }

    private static Path checkedRoot(File appRoot) throws IOException {
        if (appRoot == null) throw new IOException("Missing app root");
        Path root = appRoot.toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW))
            throw new IOException("Unsafe app root");
        return root;
    }

    private static Path checkedJournal(Path root, File journalDir) throws IOException {
        if (journalDir == null) throw new IOException("Missing release journal");
        Path journal = journalDir.toPath().toAbsolutePath().normalize();
        if (!journal.startsWith(root) || journal.equals(root)) throw new IOException("Release journal outside app root");
        requireNoSymlinkParents(root, journal);
        if (!Files.isDirectory(journal.getParent(), NOFOLLOW)) throw new IOException("Missing journal parent");
        return journal;
    }

    private static Path checkedTarget(Path root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.indexOf('\\') >= 0)
            throw new IOException("Invalid release target");
        Path path = java.nio.file.Paths.get(relative);
        if (path.isAbsolute()) throw new IOException("Absolute release target");
        for (Path part : path) if (".".equals(part.toString()) || "..".equals(part.toString()))
            throw new IOException("Unsafe release target");
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Release target outside app root");
        return target;
    }

    private static void requireNoSymlinkParents(Path root, Path target) throws IOException {
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symlink in release target: " + current);
        }
    }

    private static boolean validHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static void requireBefore(Plan plan) throws IOException {
        if (plan.entry.expectedBeforeSha256 == null) {
            if (Files.exists(plan.target, NOFOLLOW)) throw new IOException("Unexpected existing target: " + plan.target);
        }
        else requireHash(plan.target, plan.entry.directory, plan.entry.expectedBeforeSha256, "Original target");
        if (!Files.isDirectory(plan.target.getParent(), NOFOLLOW) || Files.isSymbolicLink(plan.target.getParent()))
            throw new IOException("Missing or unsafe release target parent: " + plan.target);
    }

    private static void requireHash(Path path, boolean directory, String expected, String label) throws IOException {
        if (!expected.equals(sha256(path.toFile(), directory))) throw new IOException(label + " hash mismatch: " + path);
    }

    private static long storageBytes(Path source) throws IOException {
        if (Files.isRegularFile(source, NOFOLLOW)) return Files.size(source);
        final long[] total = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {
                if (attr.isRegularFile()) {
                    if (Long.MAX_VALUE - total[0] < attr.size()) throw new IOException("Release size overflow");
                    total[0] += attr.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private static void copyCandidate(Path source, Path destination, boolean directory,
                                      FaultInjector faults, int index) throws IOException {
        if (!directory) { copyFile(source, destination, faults, index); return; }
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.copy(dir, destination.resolve(source.relativize(dir)), StandardCopyOption.COPY_ATTRIBUTES, NOFOLLOW);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = destination.resolve(source.relativize(file));
                if (attrs.isSymbolicLink()) {
                    Path link = Files.readSymbolicLink(file);
                    checkRelativeLink(source, file, link);
                    Files.createSymbolicLink(target, link);
                }
                else if (attrs.isRegularFile()) copyFile(file, target, faults, index);
                else throw new IOException("Unsupported Wine tree entry: " + file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyFile(Path source, Path destination, FaultInjector faults, int index) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source.toFile()));
             FileOutputStream file = new FileOutputStream(destination.toFile());
             BufferedOutputStream output = new BufferedOutputStream(file)) {
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) {
                output.write(bytes, 0, count);
                faults.at("during-copy", index);
            }
            output.flush(); file.getFD().sync();
        }
        Files.setPosixFilePermissions(destination, Files.getPosixFilePermissions(source, NOFOLLOW));
    }

    private static void checkRelativeLink(Path treeRoot, Path linkPath, Path link) throws IOException {
        if (link.isAbsolute() || !linkPath.getParent().resolve(link).normalize().startsWith(treeRoot))
            throw new IOException("Wine tree symlink escapes runtime: " + linkPath);
    }

    private static void writeManifest(Path journal, List<Plan> plans) throws IOException {
        Path tmp = journal.resolve("manifest.tmp");
        try (FileOutputStream file = new FileOutputStream(tmp.toFile());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file))) {
            out.writeUTF(MAGIC); out.writeInt(plans.size());
            for (Plan plan : plans) {
                out.writeUTF(plan.entry.relativeTarget);
                out.writeBoolean(plan.entry.directory);
                out.writeBoolean(plan.entry.expectedBeforeSha256 != null);
                if (plan.entry.expectedBeforeSha256 != null) out.writeUTF(plan.entry.expectedBeforeSha256);
                out.writeUTF(plan.entry.expectedAfterSha256);
            }
            out.flush(); file.getFD().sync();
        }
        atomicMove(tmp, journal.resolve("manifest"));
    }

    private static List<Plan> readManifest(Path root, Path journal, Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest, NOFOLLOW) || Files.isSymbolicLink(manifest))
            throw new IOException("Unsafe release manifest");
        List<Plan> plans = new ArrayList<>();
        Set<Path> targets = new HashSet<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(manifest.toFile())))) {
            if (!MAGIC.equals(in.readUTF())) throw new IOException("Invalid release manifest");
            int count = in.readInt();
            if (count <= 0 || count > MAX_ENTRIES) throw new IOException("Invalid release manifest size");
            for (int i = 0; i < count; i++) {
                String relative = in.readUTF(); boolean directory = in.readBoolean();
                String before = in.readBoolean() ? in.readUTF() : null; String after = in.readUTF();
                if (!validHash(after) || (before != null && !validHash(before))) throw new IOException("Invalid release manifest hash");
                Path target = checkedTarget(root, relative);
                if (target.startsWith(journal) || journal.startsWith(target) || !targets.add(target))
                    throw new IOException("Unsafe release manifest target");
                for (Path other : targets) if (!other.equals(target) && (other.startsWith(target) || target.startsWith(other)))
                    throw new IOException("Overlapping release manifest targets");
                plans.add(new Plan(new Entry(relative, null, before, after, directory), null, target));
            }
            if (in.read() != -1) throw new IOException("Trailing release manifest data");
        } catch (EOFException error) { throw new IOException("Truncated release manifest", error); }
        return plans;
    }

    private static void writeSync(Path path, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            out.write(data); out.flush(); out.getFD().sync();
        }
    }

    private static Path staged(Path journal, int index) { return journal.resolve("staged").resolve(Integer.toString(index)); }
    private static Path backup(Path journal, int index) { return journal.resolve("backup").resolve(Integer.toString(index)); }

    private static void atomicMove(Path source, Path destination) throws IOException {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void cleanupJournal(Path journal) throws IOException {
        // Keep ownership and, for committed work, the commit marker until
        // staged files and backups are gone. Repeating recovery after an
        // interrupted cleanup must not mistake committed work for rollback.
        deleteTree(journal.resolve("staged"));
        deleteTree(journal.resolve("backup"));
        Files.deleteIfExists(journal.resolve("manifest.tmp"));
        Files.deleteIfExists(journal.resolve("manifest"));
        Files.deleteIfExists(journal.resolve("commit.tmp"));
        Files.deleteIfExists(journal.resolve("commit"));
        Files.deleteIfExists(journal.resolve("owner.tmp"));
        Files.deleteIfExists(journal.resolve("owner"));
        Files.delete(journal);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, NOFOLLOW)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
