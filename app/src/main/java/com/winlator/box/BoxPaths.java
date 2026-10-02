package com.winlator.box;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public final class BoxPaths {
    private BoxPaths() {}

    public static File getBoxDir(Context context) {
        return new File(context.getFilesDir(), "box");
    }

    public static File getSpecFile(Context context) {
        return new File(getBoxDir(context), "box-spec.json");
    }

    public static File getStateFile(Context context) {
        return new File(getBoxDir(context), "box-state.json");
    }

    public static File getOverridesFile(Context context) {
        return new File(getBoxDir(context), "runtime-env-overrides.json");
    }

    public static File getTokenFile(Context context) {
        return new File(getBoxDir(context), "debug-token.txt");
    }

    public static File getRuntimeOverlaysDir(Context context) {
        return new File(getBoxDir(context), "runtime-overlays");
    }

    public static File getExternalRoot(BoxSpec spec) {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "WinlatorBox/" + spec.boxId);
    }

    public static File getDownloadsDir(BoxSpec spec) {
        return new File(getExternalRoot(spec), "downloads");
    }

    public static File getPayloadDir(BoxSpec spec) {
        if (spec.payload.extractDir != null && !spec.payload.extractDir.isEmpty()) {
            return new File(spec.payload.extractDir);
        }
        return new File(getExternalRoot(spec), "payload");
    }

    public static File getLogsDir(BoxSpec spec) {
        return new File(getExternalRoot(spec), "logs");
    }

    public static File getStateExportDir(BoxSpec spec) {
        return new File(getExternalRoot(spec), "exports");
    }

    public static File getExternalPrefixProtectionFile(BoxSpec spec) {
        return new File(getExternalRoot(spec), "prefix-protected.json");
    }
}
