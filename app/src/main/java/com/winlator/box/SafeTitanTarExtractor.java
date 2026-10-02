package com.winlator.box;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SafeTitanTarExtractor {
    public interface Cancellation {
        boolean isCancelled();
    }

    public interface Progress {
        void onProgress(long files, long bytes);
    }

    public static final class Limits {
        public final long maxFiles;
        public final long maxBytes;

        public Limits(long maxFiles, long maxBytes) {
            this.maxFiles = maxFiles;
            this.maxBytes = maxBytes;
        }
    }

    public static final class Result {
        public final long wtfFiles;
        public final long addonFiles;
        public final long totalFiles;
        public final long totalBytes;
        public final String tarSha256;
        public final String contentSnapshot;

        Result(
                long wtfFiles,
                long addonFiles,
                long totalBytes,
                String tarSha256,
                String contentSnapshot) {
            this.wtfFiles = wtfFiles;
            this.addonFiles = addonFiles;
            this.totalFiles = wtfFiles + addonFiles;
            this.totalBytes = totalBytes;
            this.tarSha256 = tarSha256;
            this.contentSnapshot = contentSnapshot;
        }
    }

    private SafeTitanTarExtractor() {}

    public static Result extract(
            InputStream input,
            File destination,
            Limits limits,
            Cancellation cancellation,
            Progress progress) throws Exception {
        if (destination.exists() && !destination.isDirectory()) {
            throw new IOException("Staging path is not a directory");
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Unable to create staging directory");
        }
        File canonicalDestination = destination.getCanonicalFile();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        MessageDigest entryDigest = MessageDigest.getInstance("SHA-256");
        byte[] contentAccumulator = new byte[32];
        long wtfFiles = 0;
        long addonFiles = 0;
        long totalBytes = 0;
        Set<String> entries = new HashSet<>();
        byte[] buffer = new byte[128 * 1024];

        try (DigestInputStream digestInput = new DigestInputStream(input, digest);
             TarArchiveInputStream tar = new TarArchiveInputStream(digestInput, "UTF-8")) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                checkCancelled(cancellation);
                String name = normalizeEntryName(entry.getName());
                EntryScope scope = requireScope(name, entry.isDirectory());
                if (!entries.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Duplicate tar entry: " + name);
                }
                if (entry.isSparse()
                        || (!entry.isDirectory() && !entry.isFile())
                        || entry.isSymbolicLink()
                        || entry.isLink()
                        || entry.isCharacterDevice()
                        || entry.isBlockDevice()
                        || entry.isFIFO()) {
                    throw new IOException("Unsupported tar entry: " + name);
                }

                File output = new File(canonicalDestination, name).getCanonicalFile();
                requireContained(canonicalDestination, output);
                if (entry.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new IOException("Unable to create directory: " + name);
                    }
                    output.setReadable(true, true);
                    output.setWritable(true, true);
                    output.setExecutable(true, true);
                    continue;
                }

                if (entry.getSize() < 0 || entry.getSize() > limits.maxBytes - totalBytes) {
                    throw new IOException("Tar data exceeds size limit");
                }
                long nextFileCount = wtfFiles + addonFiles + 1;
                if (nextFileCount > limits.maxFiles) {
                    throw new IOException("Tar file count exceeds limit");
                }
                File parent = output.getParentFile();
                if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                    throw new IOException("Unable to create parent for: " + name);
                }
                long written = 0;
                try (BufferedOutputStream file =
                             new BufferedOutputStream(new FileOutputStream(output))) {
                    while (written < entry.getSize()) {
                        checkCancelled(cancellation);
                        int read = tar.read(
                                buffer,
                                0,
                                (int) Math.min(buffer.length, entry.getSize() - written));
                        if (read < 0) throw new IOException("Truncated tar entry: " + name);
                        if (read == 0) continue;
                        file.write(buffer, 0, read);
                        written += read;
                        if (progress != null) {
                            progress.onProgress(
                                    wtfFiles + addonFiles,
                                    totalBytes + written);
                        }
                    }
                }
                if (written != entry.getSize()) {
                    throw new IOException("Tar entry size mismatch: " + name);
                }
                output.setReadable(true, false);
                output.setWritable(true, true);
                output.setExecutable(false, false);
                if (entry.getLastModifiedDate() != null) {
                    output.setLastModified(entry.getLastModifiedDate().getTime());
                }
                totalBytes += written;
                byte[] entryHash = entryDigest.digest(
                        (name + "\t" + written + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for (int i = 0; i < contentAccumulator.length; i++) {
                    contentAccumulator[i] ^= entryHash[i];
                }
                if (scope == EntryScope.WTF) wtfFiles++;
                else addonFiles++;
                if (progress != null) {
                    progress.onProgress(wtfFiles + addonFiles, totalBytes);
                }
                checkCancelled(cancellation);
            }
        }

        if (wtfFiles + addonFiles == 0) throw new IOException("Tar contains no files");
        return new Result(
                wtfFiles,
                addonFiles,
                totalBytes,
                hex(digest.digest()),
                hex(contentAccumulator));
    }

    static String normalizeEntryName(String raw) throws IOException {
        if (raw == null) throw new IOException("Tar entry has no name");
        String name = raw;
        while (name.startsWith("./")) name = name.substring(2);
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\")
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.indexOf(':') >= 0) {
            throw new IOException("Unsafe tar path: " + raw);
        }
        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Unsafe tar path: " + raw);
            }
        }
        return name;
    }

    private static EntryScope requireScope(String name, boolean directory) throws IOException {
        if ("WTF".equalsIgnoreCase(name)) {
            if (!directory) throw new IOException("WTF root must be a directory");
            return EntryScope.WTF;
        }
        if (name.regionMatches(true, 0, "WTF/", 0, 4)) return EntryScope.WTF;
        if ("Interface".equalsIgnoreCase(name)) {
            if (!directory) throw new IOException("Interface root must be a directory");
            return EntryScope.ADDONS;
        }
        if ("Interface/AddOns".equalsIgnoreCase(name)) {
            if (!directory) throw new IOException("AddOns root must be a directory");
            return EntryScope.ADDONS;
        }
        if (name.regionMatches(true, 0, "Interface/AddOns/", 0, 17)) {
            return EntryScope.ADDONS;
        }
        throw new IOException("Tar entry is outside WTF/AddOns: " + name);
    }

    private static void requireContained(File root, File candidate) throws IOException {
        String rootPath = root.getPath();
        String candidatePath = candidate.getPath();
        if (!candidatePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Tar entry escapes staging directory");
        }
    }

    private static void checkCancelled(Cancellation cancellation) throws IOException {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new IOException("Sync cancelled");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    private enum EntryScope {
        WTF,
        ADDONS
    }
}
