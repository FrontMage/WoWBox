package com.winlator.box;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

/** Prepares Wine's prefix-update marker as part of an existing release transaction. */
final class WinebootTimestamp {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final byte[] DISABLE = "disable".getBytes(StandardCharsets.US_ASCII);
    // Wine compares strtoul(marker) to wine.inf's mtime. The validator must
    // confirm that the actually activated wine.inf has a nonzero mtime.
    private static final byte[] STALE = "0\n".getBytes(StandardCharsets.US_ASCII);

    private WinebootTimestamp() {}

    static BoxReleaseTransaction.Entry prepare(File appRoot, File prefix, File stage)
            throws IOException {
        Path root = appRoot.toPath().toAbsolutePath().normalize();
        Path prefixPath = prefix.toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW) ||
                !prefixPath.startsWith(root) || Files.isSymbolicLink(prefixPath) ||
                !Files.isDirectory(prefixPath, NOFOLLOW)) {
            throw new IOException("Unsafe Wine prefix for release update");
        }
        Path ancestor = root;
        for (Path part : root.relativize(prefixPath)) {
            ancestor = ancestor.resolve(part);
            if (Files.isSymbolicLink(ancestor)) throw new IOException("Unsafe Wine prefix parent");
        }
        Path timestamp = prefixPath.resolve(".update-timestamp");
        if (Files.notExists(timestamp, NOFOLLOW)) return null; // Wine updates a missing marker.
        if (Files.isSymbolicLink(timestamp) || !Files.isRegularFile(timestamp, NOFOLLOW))
            throw new IOException("Unsafe Wine update timestamp");

        // Wine honors any marker whose first seven bytes are 'disable'. Never
        // replace it, even if it has a suffix or more data after that prefix.
        byte[] first = new byte[DISABLE.length];
        int count = 0;
        try (java.io.InputStream input = Files.newInputStream(timestamp)) {
            while (count < first.length) {
                int n = input.read(first, count, first.length - count);
                if (n < 0) break;
                if (n == 0) continue;
                count += n;
            }
        }
        if (count == DISABLE.length && Arrays.equals(first, DISABLE)) return null;

        if (Files.size(timestamp) > 100) throw new IOException("Unknown Wine update timestamp");
        byte[] original = Files.readAllBytes(timestamp);
        String text = new String(original, StandardCharsets.US_ASCII);
        // Wine writes unsigned decimal in CRT text mode, where a newline may
        // be CRLF. Reject unknown user-authored markers rather than overwrite.
        if (!text.matches("[0-9]{1,20}(?:\\r?\\n)?"))
            throw new IOException("Unknown Wine update timestamp");
        String before = BoxReleaseTransaction.sha256(timestamp.toFile(), false);
        // Detect an edit that raced the snapshot before preparing a candidate.
        if (!Arrays.equals(original, Files.readAllBytes(timestamp)))
            throw new IOException("Wine update timestamp changed during preparation");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(timestamp, NOFOLLOW);
        Path candidate = new File(stage, "wineboot-timestamp").toPath();
        try (FileOutputStream output = new FileOutputStream(candidate.toFile())) {
            output.write(STALE);
            output.flush();
            output.getFD().sync();
        }
        Files.setPosixFilePermissions(candidate, permissions);
        String relative = root.relativize(timestamp).toString().replace(File.separatorChar, '/');
        return new BoxReleaseTransaction.Entry(relative, candidate.toFile(), before,
                BoxReleaseTransaction.sha256(candidate.toFile(), false), false);
    }

    static void requireActivatedInfHasNonzeroTimestamp(File wineDir) throws IOException {
        File inf = new File(wineDir, "share/wine/wine.inf");
        if (!inf.isFile() || Files.isSymbolicLink(inf.toPath()) || inf.lastModified() < 1000)
            throw new IOException("Wine INF timestamp cannot trigger prefix update");
    }
}
