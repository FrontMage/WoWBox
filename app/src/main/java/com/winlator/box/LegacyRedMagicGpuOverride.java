package com.winlator.box;

import android.content.Context;
import android.util.Log;
import com.winlator.BuildConfig;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineSessionProcessController;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Map;

/** Immutable effective GPU overlay; legacy persisted state remains authoritative and unchanged. */
final class LegacyRedMagicGpuOverride {
    private static final String TAG = "LegacyRedMagicGpu";
    private LegacyRedMagicGpuOverride() {}

    static boolean applies(BoxSpec spec, JSONObject state, Map<String, String> env) {
        return LegacyRedMagicGpuDecision.applies(spec, state, env,
                RedMagicDevicePolicy.isTarget() && !BuildConfig.UU_PROBE_BUILD && !BuildConfig.AGENT_DRIVE_PROBE_BUILD,
                BuildConfig.APPLICATION_ID);
    }

    static void prepare(Context context, BoxSpec spec, JSONObject state, Map<String, String> env) {
        if (!applies(spec, state, env)) return;
        try {
            File parent = parent(context, true);
            File dest = new File(parent, LegacyRedMagicGpuDecision.NEW_ID);
            if (AtomicGpuOverrideStore.verified(dest, LegacyRedMagicGpuDecision.NEW_LIB,
                    LegacyRedMagicGpuDecision.NEW_META_SHA, LegacyRedMagicGpuDecision.NEW_LIB_SHA)) return;
            if (WineSessionProcessController.getInstance().hasActiveWineProcesses()) {
                Log.w(TAG, "Deferring GPU preparation while Wine is active");
                return;
            }
            if (!LegacyRedMagicGpuDecision.NEW_ARCHIVE_SHA.equals(assetSha(context, LegacyRedMagicGpuDecision.NEW_ASSET))) {
                throw new IllegalStateException("redmagic_gpu_asset_hash_mismatch");
            }
            AtomicGpuOverrideStore.prepare(parent, LegacyRedMagicGpuDecision.NEW_ID,
                    LegacyRedMagicGpuDecision.NEW_LIB, LegacyRedMagicGpuDecision.NEW_META_SHA,
                    LegacyRedMagicGpuDecision.NEW_LIB_SHA, stage -> {
                        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
                                LegacyRedMagicGpuDecision.NEW_ASSET, stage)) throw new IllegalStateException("gpu_extract_failed");
                    });
        } catch (Exception failure) {
            // Never delete or replace the original installed driver; preflight blocks this override.
            Log.e(TAG, "Unable to prepare RedMagic GPU override", failure);
        }
    }

    static JSONObject status(Context context, BoxSpec spec, JSONObject state, Map<String, String> env) throws JSONException {
        boolean applies = applies(spec, state, env);
        boolean verified = false;
        if (applies) {
            try {
                File dir = new File(parent(context, false), LegacyRedMagicGpuDecision.NEW_ID);
                verified = AtomicGpuOverrideStore.verified(dir, LegacyRedMagicGpuDecision.NEW_LIB,
                        LegacyRedMagicGpuDecision.NEW_META_SHA, LegacyRedMagicGpuDecision.NEW_LIB_SHA);
            } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("eligible", applies);
        result.put("selected", applies && verified);
        result.put("selectionScope", "next cold launch plan only; per-launch environment may override");
        result.put("currentSessionDriver", "not verified; warm sessions retain their original environment");
        result.put("actualSource", "inspect current guest process environment and mappings");
        result.put("ready", !applies || verified);
        result.put("overrideAvailable", applies && verified);
        result.put("fallbackToOriginal", false);
        result.put("driverId", applies ? LegacyRedMagicGpuDecision.NEW_ID : "");
        result.put("overrideSource", applies ? "asset://" + LegacyRedMagicGpuDecision.NEW_ASSET : "");
        result.put("overrideArchiveSha256", applies ? LegacyRedMagicGpuDecision.NEW_ARCHIVE_SHA : "");
        result.put("overrideLibrarySha256", applies ? LegacyRedMagicGpuDecision.NEW_LIB_SHA : "");
        result.put("persistedStateModified", false);
        return result;
    }

    private static File parent(Context context, boolean create) throws Exception {
        File root = context.getFilesDir().getCanonicalFile();
        File contents = new File(root, "contents");
        File parent = new File(contents, "adrenotools");
        for (File dir : new File[]{contents, parent}) {
            if (Files.isSymbolicLink(dir.toPath())) throw new IllegalStateException("unsafe_gpu_parent");
            if (!dir.exists() && create && !dir.mkdir() && !dir.isDirectory()) throw new IllegalStateException("gpu_parent_create_failed");
            if (!dir.isDirectory() || !dir.getCanonicalPath().startsWith(root.getPath() + File.separator)) {
                throw new IllegalStateException("unsafe_gpu_parent");
            }
        }
        return parent;
    }

    private static String assetSha(Context context, String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = context.getAssets().open(path)) {
            byte[] bytes = new byte[65536];
            for (int n; (n = input.read(bytes)) != -1;) digest.update(bytes, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }
}
