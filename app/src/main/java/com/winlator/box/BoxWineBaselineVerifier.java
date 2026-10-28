package com.winlator.box;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies a complete authenticated Wine baseline before a release migration. */
public final class BoxWineBaselineVerifier {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final int MAX_ENTRIES = 10000;

    private BoxWineBaselineVerifier() {}

    public static final class Entry {
        public final String path;
        public final String type;
        public final String sha256;
        public final List<String> allowedSha256;
        public final String linkTarget;

        public Entry(String path, String type, String sha256,
                     List<String> allowedSha256, String linkTarget) {
            this.path = path;
            this.type = type;
            this.sha256 = sha256;
            this.allowedSha256 = allowedSha256 == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(allowedSha256));
            this.linkTarget = linkTarget;
        }
    }

    /**
     * Caller authenticates the manifest bytes and archive/bridge identities.
     * Unknown, absent, changed or escaping entries abort before activation.
     */
    public static void verify(File wineRoot, List<Entry> entries) throws IOException {
        if (wineRoot == null || entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES)
            throw new IOException("Invalid Wine baseline manifest");
        Path root = wineRoot.toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW))
            throw new IOException("Invalid Wine baseline root");
        Map<String, Entry> expected = new HashMap<>();
        for (Entry entry : entries) {
            validateEntry(entry);
            if (expected.put(entry.path, entry) != null)
                throw new IOException("Duplicate Wine baseline entry: " + entry.path);
        }
        Set<String> seen = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (!dir.equals(root)) inspect(dir, "dir", null);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (attrs.isSymbolicLink()) inspect(file, "symlink", Files.readSymbolicLink(file));
                else if (attrs.isRegularFile()) inspect(file, "file", null);
                else throw new IOException("Unsupported Wine baseline entry: " + file);
                return FileVisitResult.CONTINUE;
            }

            private void inspect(Path path, String actualType, Path actualLink) throws IOException {
                String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
                Entry entry = expected.get(relative);
                if (entry == null) throw new IOException("Unexpected Wine baseline entry: " + relative);
                if (!entry.type.equals(actualType)) throw new IOException("Wine baseline type mismatch: " + relative);
                if ("file".equals(actualType)) {
                    String actual = BoxReleaseTransaction.sha256(path.toFile(), false);
                    if (!entry.allowedSha256.isEmpty()) {
                        if (!entry.allowedSha256.contains(actual))
                            throw new IOException("Wine baseline hash mismatch: " + relative);
                    }
                    else if (!entry.sha256.equals(actual)) {
                        throw new IOException("Wine baseline hash mismatch: " + relative);
                    }
                }
                else if ("symlink".equals(actualType)) {
                    if (actualLink == null || actualLink.isAbsolute() ||
                            !path.getParent().resolve(actualLink).normalize().startsWith(root) ||
                            !entry.linkTarget.equals(actualLink.toString().replace(File.separatorChar, '/')))
                        throw new IOException("Wine baseline symlink mismatch: " + relative);
                }
                seen.add(relative);
            }
        });
        if (seen.size() != expected.size()) {
            Set<String> missing = new HashSet<>(expected.keySet());
            missing.removeAll(seen);
            throw new IOException("Wine baseline entries missing: " + missing.size());
        }
    }

    private static void validateEntry(Entry entry) throws IOException {
        if (entry == null || entry.path == null || entry.path.isEmpty() ||
                entry.path.startsWith("/") || entry.path.indexOf('\\') >= 0 || entry.path.indexOf('\0') >= 0)
            throw new IOException("Invalid Wine baseline path");
        Path path = Paths.get(entry.path);
        if (path.isAbsolute() || !path.normalize().toString().equals(entry.path))
            throw new IOException("Unsafe Wine baseline path: " + entry.path);
        for (Path component : path) {
            if (".".equals(component.toString()) || "..".equals(component.toString()))
                throw new IOException("Unsafe Wine baseline path: " + entry.path);
        }
        if (!"file".equals(entry.type) && !"dir".equals(entry.type) && !"symlink".equals(entry.type))
            throw new IOException("Invalid Wine baseline entry type: " + entry.path);
        if ("file".equals(entry.type)) {
            if (!validHash(entry.sha256) || entry.linkTarget != null)
                throw new IOException("Invalid Wine baseline file: " + entry.path);
            for (String hash : entry.allowedSha256) if (!validHash(hash))
                throw new IOException("Invalid Wine baseline allowed hash: " + entry.path);
            if (!entry.allowedSha256.isEmpty() && !entry.allowedSha256.contains(entry.sha256))
                throw new IOException("Allowed hashes omit baseline: " + entry.path);
        }
        else if ("symlink".equals(entry.type)) {
            if (entry.linkTarget == null || entry.linkTarget.isEmpty() || entry.linkTarget.indexOf('\\') >= 0 ||
                    entry.sha256 != null || !entry.allowedSha256.isEmpty())
                throw new IOException("Invalid Wine baseline link: " + entry.path);
            Path link = Paths.get(entry.linkTarget);
            if (link.isAbsolute()) throw new IOException("Absolute Wine baseline link: " + entry.path);
            Path parent = path.getParent();
            Path resolved = (parent == null ? link : parent.resolve(link)).normalize();
            if (resolved.isAbsolute() || resolved.startsWith(".."))
                throw new IOException("Escaping Wine baseline link: " + entry.path);
        }
        else if (entry.sha256 != null || entry.linkTarget != null || !entry.allowedSha256.isEmpty()) {
            throw new IOException("Invalid Wine baseline directory: " + entry.path);
        }
    }

    private static boolean validHash(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }
}
