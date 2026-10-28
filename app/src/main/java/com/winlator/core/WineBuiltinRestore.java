package com.winlator.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Restores Wine-owned files from the selected runtime, never an unversioned prefix cache. */
public final class WineBuiltinRestore {
    private static final String TRANSACTION = ".wine-builtin-restore";

    private WineBuiltinRestore() {}

    /** Legacy preserve accepts its installed runtime without claiming archive identity. */
    public static boolean runtimeMarkerAllowed(String policy, boolean exists,
                                               boolean symlink, boolean checksumMatches) {
        if (symlink) return false;
        return exists ? checksumMatches : "preserve".equals(policy);
    }

    public static void restore(File runtimeRoot, File windowsDir, boolean arm64ec, String... names)
            throws IOException {
        restore(runtimeRoot, windowsDir, arm64ec ? "arm64ec" : "x86_64", false, names);
    }

    public static void restore(File runtimeRoot, File windowsDir, String arch,
                               boolean allowMissingComponents, String... names) throws IOException {
        requireRoots(runtimeRoot, windowsDir);
        File txn = new File(windowsDir, TRANSACTION);
        recover(txn, windowsDir);
        List<Item> items = plan(runtimeRoot, windowsDir, arch, allowMissingComponents, names);
        if (items.isEmpty()) return;
        if (!txn.mkdir()) throw new IOException("Cannot create Wine restore transaction: " + txn);
        try {
            for (int i = 0; i < items.size(); i++) {
                File staged = new File(txn, i + ".new");
                copy(items.get(i).source, staged);
                if (!MessageDigest.isEqual(sha256(items.get(i).source), sha256(staged)))
                    throw new IOException("Wine builtin source changed during copy: " + items.get(i).source);
            }
            StringBuilder manifest = new StringBuilder();
            for (Item item : items) manifest.append(item.dir).append('/').append(item.name)
                    .append('\t').append(item.existed ? '1' : '0').append('\n');
            writeSync(new File(txn, "manifest"), manifest.toString());
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                if (item.existed && !item.target.renameTo(new File(txn, i + ".old")))
                    throw new IOException("Cannot back up Wine builtin: " + item.target);
                if (!new File(txn, i + ".new").renameTo(item.target))
                    throw new IOException("Cannot activate Wine builtin: " + item.target);
            }
            writeSync(new File(txn, "committed"), "committed\n");
            cleanupCommitted(txn);
        }
        catch (IOException | RuntimeException failure) {
            try { recover(txn, windowsDir); }
            catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    /** Validate source and target availability before unrelated component changes. */
    public static void validate(File runtimeRoot, File windowsDir, boolean arm64ec, String... names)
            throws IOException {
        validate(runtimeRoot, windowsDir, arm64ec ? "arm64ec" : "x86_64", false, names);
    }

    public static void validate(File runtimeRoot, File windowsDir, String arch,
                                boolean allowMissingComponents, String... names) throws IOException {
        requireRoots(runtimeRoot, windowsDir);
        recover(new File(windowsDir, TRANSACTION), windowsDir);
        plan(runtimeRoot, windowsDir, arch, allowMissingComponents, names);
    }

    private static void requireRoots(File runtimeRoot, File windowsDir) throws IOException {
        if (runtimeRoot == null || !runtimeRoot.isDirectory() ||
                Files.isSymbolicLink(runtimeRoot.toPath()) || windowsDir == null ||
                !windowsDir.isDirectory() || Files.isSymbolicLink(windowsDir.toPath())) {
            throw new IOException("Wine runtime or Windows directory is missing or unsafe");
        }
    }

    private static List<Item> plan(File runtimeRoot, File windowsDir, String arch,
                                   boolean allowMissingComponents, String... names)
            throws IOException {
        requireRoots(runtimeRoot, windowsDir);
        if (!"arm64ec".equals(arch) && !"x86_64".equals(arch) && !"x86".equals(arch))
            throw new IOException("Unsupported Wine architecture: " + arch);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] destDirs = {"system32", "syswow64"};
        String[] srcDirs = {"arm64ec".equals(arch) ? "aarch64-windows" :
                ("x86".equals(arch) ? "i386-windows" : "x86_64-windows"), "i386-windows"};
        for (String name : names) {
            if (name == null || !name.matches("[A-Za-z0-9_.+-]+") || name.equals(".") || name.equals("..")) {
                throw new IOException("Unsafe Wine builtin name: " + name);
            }
            if (!seen.add(name.toLowerCase(Locale.US))) continue;
            for (int i = 0; i < destDirs.length; i++) {
                File dir = new File(windowsDir, destDirs[i]);
                if (!dir.exists()) continue;
                if (!dir.isDirectory() || Files.isSymbolicLink(dir.toPath()))
                    throw new IOException("Unsafe Wine prefix directory: " + dir);
                File source = new File(runtimeRoot, "lib/wine/" + srcDirs[i] + "/" + name);
                File target = new File(dir, name);
                if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(source.toPath()))
                    throw new IOException("Unsafe Wine builtin source or target: " + name);
                if (!source.isFile()) {
                    if (target.exists() && !allowMissingComponents)
                        throw new IOException("Wine builtin missing from runtime: " + source);
                    if (target.exists()) java.util.logging.Logger.getLogger(WineBuiltinRestore.class.getName())
                            .warning("No Wine builtin; retaining existing component file: " + target);
                    continue;
                }
                if (target.exists() && !target.isFile()) throw new IOException("Unsafe Wine builtin target: " + target);
                items.add(new Item(destDirs[i], name, source, target, target.exists()));
            }
        }
        return items;
    }

    private static final class Item {
        final String dir, name;
        final File source, target;
        final boolean existed;
        Item(String dir, String name, File source, File target, boolean existed) {
            this.dir = dir; this.name = name; this.source = source; this.target = target; this.existed = existed;
        }
    }

    private static void recover(File txn, File windowsDir) throws IOException {
        if (!txn.exists() && !Files.isSymbolicLink(txn.toPath())) return;
        if (!txn.isDirectory() || Files.isSymbolicLink(txn.toPath())) throw new IOException("Unsafe restore transaction");
        File manifest = new File(txn, "manifest");
        if (new File(txn, "committed").isFile()) { cleanupCommitted(txn); return; }
        if (!manifest.exists()) { deleteTree(txn); return; }
        if (!manifest.isFile() || Files.isSymbolicLink(manifest.toPath())) throw new IOException("Unsafe restore manifest");
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(manifest))) {
            String line; while ((line = reader.readLine()) != null) lines.add(line);
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String[] parts = lines.get(i).split("\\t", -1);
            if (parts.length != 2 || !parts[0].matches("(system32|syswow64)/[A-Za-z0-9_.+-]+") ||
                    !(parts[1].equals("0") || parts[1].equals("1"))) throw new IOException("Invalid restore manifest");
            File target = new File(windowsDir, parts[0]);
            File backup = new File(txn, i + ".old");
            File staged = new File(txn, i + ".new");
            if (Files.isSymbolicLink(target.getParentFile().toPath()) || Files.isSymbolicLink(target.toPath()) ||
                    Files.isSymbolicLink(backup.toPath()) || Files.isSymbolicLink(staged.toPath()))
                throw new IOException("Unsafe restore transaction file");
            if (backup.exists()) {
                if (target.exists() && !target.delete()) throw new IOException("Cannot undo Wine builtin: " + target);
                if (!backup.renameTo(target)) throw new IOException("Cannot restore Wine builtin backup: " + target);
            } else if (parts[1].equals("0") && !staged.exists() && target.exists() && !target.delete()) {
                throw new IOException("Cannot remove new Wine builtin: " + target);
            }
        }
        deleteTree(txn);
    }

    private static void copy(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            output.flush(); output.getFD().sync();
        }
    }

    private static byte[] sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    private static void writeSync(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8)); output.flush(); output.getFD().sync();
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Unsafe restore transaction entry: " + file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list restore transaction: " + file);
            for (File child : children) deleteTree(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot delete restore transaction: " + file);
    }

    private static void cleanupCommitted(File txn) throws IOException {
        File[] children = txn.listFiles();
        if (children == null) throw new IOException("Cannot list restore transaction: " + txn);
        File committed = new File(txn, "committed");
        // Keep the commit marker until all backups and the manifest are gone.
        // Otherwise an interrupted cleanup could be mistaken for a rollback.
        for (File child : children) if (!child.equals(committed)) deleteTree(child);
        deleteTree(committed);
        deleteTree(txn);
    }
}
