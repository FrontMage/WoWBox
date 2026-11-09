package com.winlator.box;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;

/** Immutable, independently verified driver directory. Never replaces an existing path. */
final class AtomicGpuOverrideStore {
    interface StageWriter { void extract(File stage) throws Exception; }

    private AtomicGpuOverrideStore() {}

    static synchronized void prepare(File parent, String driverId, String library,
                                     String metaSha, String libSha, StageWriter writer) throws Exception {
        if (!parent.isDirectory() || Files.isSymbolicLink(parent.toPath()) ||
                !driverId.matches("[A-Za-z0-9][A-Za-z0-9._+-]*") ||
                !library.matches("[A-Za-z0-9][A-Za-z0-9._+-]*")) throw new IOException("unsafe_gpu_parent");
        File lockPath = new File(parent, ".wowbox-redmagic-v39.lock");
        if (Files.isSymbolicLink(lockPath.toPath()) || (lockPath.exists() && !lockPath.isFile())) {
            throw new IOException("unsafe_gpu_lock");
        }
        try (RandomAccessFile lockFile = new RandomAccessFile(lockPath, "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            File destination = new File(parent, driverId);
            if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
                if (!verified(destination, library, metaSha, libSha)) throw new IOException("gpu_destination_conflict");
                return;
            }
            File stage = Files.createTempDirectory(parent.toPath(), ".wowbox-redmagic-v39-").toFile();
            try {
                writer.extract(stage);
                if (!verified(stage, library, metaSha, libSha)) throw new IOException("gpu_staged_hash_mismatch");
                if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
                    if (!verified(destination, library, metaSha, libSha)) throw new IOException("gpu_destination_conflict");
                } else if (!stage.renameTo(destination)) throw new IOException("gpu_activate_failed");
            } finally {
                if (stage.exists()) {
                    // Only files in a trusted, hash-pinned two-entry driver archive are removed.
                    new File(stage, "meta.json").delete();
                    new File(stage, library).delete();
                    stage.delete();
                }
            }
            if (!verified(destination, library, metaSha, libSha)) throw new IOException("gpu_activated_hash_mismatch");
        }
    }

    static boolean verified(File dir, String library, String metaSha, String libSha) {
        try {
            if (!dir.isDirectory() || Files.isSymbolicLink(dir.toPath())) return false;
            File[] entries = dir.listFiles();
            File meta = new File(dir, "meta.json");
            File so = new File(dir, library);
            if (entries == null || !meta.isFile() || !so.isFile() || Files.isSymbolicLink(meta.toPath()) ||
                    Files.isSymbolicLink(so.toPath()) || !metaSha.equals(WinePrefixDeltaReconciler.sha256(meta)) ||
                    !libSha.equals(WinePrefixDeltaReconciler.sha256(so))) return false;
            for (File entry : entries) {
                if (Files.isSymbolicLink(entry.toPath())) return false;
                String name = entry.getName();
                if (name.equals("meta.json") || name.equals(library)) continue;
                if (name.equals(".wowbox-owner")) {
                    if (!entry.isFile() || entry.length() != 22 ||
                            !"wowbox-initial-gpu-v1\n".equals(new String(Files.readAllBytes(entry.toPath()), java.nio.charset.StandardCharsets.US_ASCII))) return false;
                } else if (name.equals("temp")) {
                    if (!entry.isDirectory()) return false;
                    File[] tempFiles = entry.listFiles();
                    if (tempFiles == null) return false;
                    for (File temp : tempFiles) {
                        if (Files.isSymbolicLink(temp.toPath()) || !temp.isFile() ||
                                !temp.getName().matches("[0-9]+_patched\\.so")) return false;
                    }
                } else return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }
}
