package com.winlator.box;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/** Cold-path integrity checks before loading or executing private diagnostic assets. */
final class RenderDocAssetIntegrity {
    private RenderDocAssetIntegrity() {}

    static boolean safeBasename(String path) {
        return path != null && path.matches("[A-Za-z0-9][A-Za-z0-9._-]*") && !path.contains("..");
    }

    static void requireFile(File file, long bytes, String expectedSha256) throws Exception {
        if (!file.isFile() || file.length() != bytes || !expectedSha256.equals(sha256(file))) {
            throw new IOException("asset_integrity:" + file.getName());
        }
    }

    /** RenderDoc opens libvulkan.so, while the SDK helpers link against the .so.1 SONAME. */
    static void createLoaderAlias(File directory, String loaderName, long bytes, String hash) throws Exception {
        if (!safeBasename(loaderName)) throw new IOException("loader_alias_source_path");
        File loader = new File(directory, loaderName);
        requireFile(loader, bytes, hash);
        File alias = new File(directory, "libvulkan.so");
        if (!alias.equals(loader)) {
            try { Files.createLink(alias.toPath(), loader.toPath()); }
            catch (IOException | UnsupportedOperationException linkFailure) {
                // No replacement: a pre-existing alias is never silently trusted or overwritten.
                Files.copy(loader.toPath(), alias.toPath());
                try (FileOutputStream output = new FileOutputStream(alias, true)) {
                    output.getFD().sync();
                }
            }
        }
        requireLoaderAlias(directory, bytes, hash);
    }

    static void requireLoaderAlias(File directory, long bytes, String hash) throws Exception {
        File alias = new File(directory, "libvulkan.so");
        if (Files.isSymbolicLink(alias.toPath())) throw new IOException("loader_alias_symlink");
        requireFile(alias, bytes, hash);
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }
}
