package com.winlator.box;

import com.winlator.core.WineRegistryEditor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

/** Windows PATH support for plain ARM64 DLLs, without changing game files. */
public final class NativeDxEnvironment {
    public static final String TARGET_PREFIX =
            "/home/xuser-box/.wine/drive_c/windows/wowbox-native-arm64/";
    private static final String WINDOWS_PREFIX = "C:\\windows\\wowbox-native-arm64\\";
    private static final String OWNER_KEY = "Software\\WoWBox\\NativeDx";

    private NativeDxEnvironment() {}

    public static String windowsDirectory(BoxSpec.Layer layer) {
        String target = layer != null && layer.target != null ? layer.target : "";
        if (!target.startsWith(TARGET_PREFIX)) {
            throw new IllegalArgumentException("Native DX target must be in its owned prefix directory");
        }
        String basename = target.substring(TARGET_PREFIX.length());
        if (!basename.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || basename.contains("..")) {
            throw new IllegalArgumentException("Unsafe native DX directory");
        }
        return WINDOWS_PREFIX + basename;
    }

    static final class Update {
        final String path;
        final String ownedDirectory;

        Update(String path, String ownedDirectory) {
            this.path = path;
            this.ownedDirectory = ownedDirectory;
        }
    }

    static Update mergePath(String path, String oldOwned, String desired) {
        if (!isOwnedDirectory(desired)) throw new IllegalArgumentException("Unsafe native DX PATH");
        String original = path != null ? path : "";
        boolean removeOld = isOwnedDirectory(oldOwned);
        ArrayList<String> kept = new ArrayList<>();
        boolean foundDesired = false;
        boolean retainedOwned = false;
        for (String entry : original.split(";", -1)) {
            if (removeOld && entry.equalsIgnoreCase(oldOwned) &&
                    !entry.equalsIgnoreCase(desired)) continue;
            kept.add(entry);
            if (entry.equalsIgnoreCase(desired)) {
                foundDesired = true;
                retainedOwned |= removeOld && entry.equalsIgnoreCase(oldOwned);
            }
        }
        String result = String.join(";", kept);
        if (!foundDesired) {
            result += (result.isEmpty() || result.endsWith(";") ? "" : ";") + desired;
        }
        // A directory already present before Box owned it remains user-owned.
        return new Update(result, !foundDesired || retainedOwned ? desired : "");
    }

    private static boolean isOwnedDirectory(String directory) {
        if (directory == null || !directory.regionMatches(true, 0,
                WINDOWS_PREFIX, 0, WINDOWS_PREFIX.length())) return false;
        String basename = directory.substring(WINDOWS_PREFIX.length());
        return basename.matches("[A-Za-z0-9][A-Za-z0-9._-]*") && !basename.contains("..");
    }

    static final class PathValue {
        String key = "Environment";
        String name = "Path";
        String value = "";
        boolean expand;
        boolean exists;
    }

    static PathValue readPath(File registry) throws IOException {
        PathValue result = new PathValue();
        boolean environment = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(registry))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[")) {
                    int end = line.indexOf(']');
                    String key = end > 0 ? line.substring(1, end) : "";
                    environment = "Environment".equalsIgnoreCase(key);
                    if (environment) result.key = key;
                    continue;
                }
                if (!environment || !line.startsWith("\"")) continue;
                int end = line.indexOf("\"=");
                if (end < 0) continue;
                String name = line.substring(1, end);
                if (!"Path".equalsIgnoreCase(name)) continue;
                if (result.exists) throw new IOException("Ambiguous Windows PATH registry values");
                String value = line.substring(end + 2);
                result.expand = value.startsWith("str(2):");
                if (result.expand) value = value.substring("str(2):".length());
                if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
                    throw new IOException("Unsupported Windows PATH registry type");
                }
                result.value = value.substring(1, value.length() - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\");
                result.name = name;
                result.exists = true;
            }
        }
        return result;
    }

    public static void reconcile(File registry, String desired) throws IOException {
        PathValue current = readPath(registry);
        try (WineRegistryEditor editor = new WineRegistryEditor(registry)) {
            String owned = editor.getStringValue(OWNER_KEY, "PathDirectory", "");
            Update update = mergePath(current.value, owned, desired);
            if (!update.path.equals(current.value)) {
                if (current.expand) editor.setExpandStringValue(current.key, current.name, update.path);
                else editor.setStringValue(current.key, current.name, update.path);
            }
            if (!update.ownedDirectory.equals(owned)) {
                editor.setStringValue(OWNER_KEY, "PathDirectory", update.ownedDirectory);
            }
        }
        requirePath(registry, desired);
    }

    public static void requirePath(File registry, String desired) throws IOException {
        for (String entry : readPath(registry).value.split(";", -1)) {
            if (entry.equalsIgnoreCase(desired)) return;
        }
        throw new IOException("Native DX Windows PATH is missing");
    }
}
