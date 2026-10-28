package com.winlator.box;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/** Owned fresh installs may retry; unknown pre-existing data may never be erased. */
final class BoxInitialInstallGuard {
    private static final String OWNER = "wowbox-initial-install-v1:";

    private BoxInitialInstallGuard() {}

    static boolean mayResume(File marker, File spec, boolean installed) throws IOException {
        if (installed || marker == null || !marker.isFile() || Files.isSymbolicLink(marker.toPath()) ||
                spec == null || !spec.isFile() || Files.isSymbolicLink(spec.toPath())) return false;
        return (OWNER + digest(spec) + "\n").equals(AtomicFileSupport.readUtf8(marker));
    }

    static void beginOrResume(File imageRoot, File marker, File spec, boolean installed) throws IOException {
        if (installed) throw new IOException("existing_install: initial installation is disabled");
        if (mayResume(marker, spec, false)) return;
        if (marker.exists() || Files.isSymbolicLink(marker.toPath())) {
            throw new IOException("initial_install_marker_mismatch");
        }
        if (imageRoot.exists() || Files.isSymbolicLink(imageRoot.toPath())) {
            if (Files.isSymbolicLink(imageRoot.toPath()) || !imageRoot.isDirectory()) {
                throw new IOException("existing_imagefs: initial installation is disabled");
            }
            File[] content = imageRoot.listFiles();
            if (content == null || content.length != 0) {
                throw new IOException("existing_imagefs: initial installation is disabled");
            }
        }
        if (spec == null || !spec.isFile() || Files.isSymbolicLink(spec.toPath())) {
            throw new IOException("missing_initial_install_spec");
        }
        File parent = marker.getParentFile();
        if (parent == null || !parent.isDirectory() || Files.isSymbolicLink(parent.toPath())) {
            throw new IOException("unsafe_initial_install_marker_parent");
        }
        File pending = new File(marker.getAbsolutePath() + ".pending");
        if (Files.isSymbolicLink(pending.toPath()) || (pending.exists() && !pending.isFile())) {
            throw new IOException("unsafe_initial_install_pending_marker");
        }
        // No user imagefs exists yet, so a partial marker from process death
        // can be replaced. AtomicFileSupport fsyncs before the final rename.
        AtomicFileSupport.writeUtf8(marker, OWNER + digest(spec) + "\n");
    }

    static void complete(File marker) throws IOException {
        if (Files.isSymbolicLink(marker.toPath())) throw new IOException("unsafe_initial_install_marker");
        if (marker.exists() && !marker.delete()) throw new IOException("unable_to_clear_initial_install_marker");
    }

    private static String digest(File file) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] block = new byte[65536]; int n;
                while ((n = in.read(block)) != -1) hash.update(block, 0, n);
            }
            StringBuilder value = new StringBuilder();
            for (byte b : hash.digest()) value.append(String.format(Locale.US, "%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
