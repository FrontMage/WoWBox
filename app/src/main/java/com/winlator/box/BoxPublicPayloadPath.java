package com.winlator.box;

import java.io.File;

/** Resolve only the published default directory against the current Android user. */
final class BoxPublicPayloadPath {
    static final String DEFAULT_DIRECTORY = "/storage/emulated/0/Download/FrontMage-BNet";
    static final String DEFAULT_SOURCE = "file:///storage/emulated/0/Download/FrontMage-BNet";

    private BoxPublicPayloadPath() {}

    static boolean isDefault(String kind, String source, String extractDir, String mountDrive) {
        return "directory".equalsIgnoreCase(kind) && DEFAULT_SOURCE.equals(source) &&
                DEFAULT_DIRECTORY.equals(extractDir) && "D".equals(mountDrive);
    }

    static File resolve(String kind, String source, String extractDir, String mountDrive,
                        File currentUserDownloads, File boxExternalRoot) {
        if (isDefault(kind, source, extractDir, mountDrive)) {
            if (currentUserDownloads == null) throw new IllegalArgumentException("Current user downloads directory is missing");
            return new File(currentUserDownloads, "FrontMage-BNet");
        }
        if (extractDir != null && !extractDir.isEmpty()) return new File(extractDir);
        if (boxExternalRoot == null) throw new IllegalArgumentException("Box external root is missing");
        return new File(boxExternalRoot, "payload");
    }
}
