package com.winlator.box;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.BuildConfig;
import com.winlator.XServerDisplayActivity;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Optional UU diagnostics, independent of the nine required Box layers. */
public final class RenderDocCaptureManager {
    private static final Object ASSET_LOCK = new Object();
    public static final String PREF_ENABLED = "renderdoc_capture_enabled";
    private static final String PREF_LAUNCH = "renderdoc_capture_last_launch";
    private static final String ASSET_ROOT = "renderdoc/1.44-uu-v1";
    private static final String[] REQUIRED_ROLES = {
            "layer", "loader", "targetControl", "guard", "thumbnail", "replay"};
    private static final String[] REPLAY_ENV = {
            "TU_DEBUG", "MESA_VK_WSI_DEBUG", "WRAPPER_BLIT", "WRAPPER_DISABLE_PRESENT_WAIT",
            "WRAPPER_LAYER_PATH", "WRAPPER_CACHE_PATH", "ADRENOTOOLS_DRIVER_PATH",
            "ADRENOTOOLS_DRIVER_NAME", "ADRENOTOOLS_HOOKS_PATH", "VK_ICD_FILENAMES",
            "ADRENOTOOLS_LIBRARY_PATH", "ADRENOTOOLS_GPU_MAPPING_PATH", "ADRENOTOOLS_REDIRECT_PATH",
            "DISABLE_BCN_COMPUTE", "BCN_COMPUTE_AUTO", "WRAPPER_EMULATE_BCN", "WRAPPER_USE_BCN_CACHE",
            "FD_DEV_FEATURES",
            "LD_LIBRARY_PATH", "LD_PRELOAD"};

    private final Context context;
    private final SharedPreferences preferences;
    private byte[] manifestBytes;
    private String manifestDigest;

    public RenderDocCaptureManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = BuildConfig.UU_PROBE_BUILD
                ? PreferenceManager.getDefaultSharedPreferences(this.context) : null;
    }

    public boolean isEnabled() {
        return BuildConfig.UU_PROBE_BUILD && preferences.getBoolean(PREF_ENABLED, false);
    }

    private File assetsDirectory(JSONObject manifest) throws Exception {
        return new File(context.getFilesDir(), "diagnostics/renderdoc/"
                + manifest.getString("id") + "-" + manifestDigest);
    }

    private JSONObject bundledManifest() throws Exception {
        try (InputStream input = context.getAssets().open(ASSET_ROOT + "/manifest.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            byte[] source = bytes.toByteArray();
            JSONObject manifest = new JSONObject(new String(source, StandardCharsets.UTF_8));
            validateManifest(manifest);
            manifestBytes = source;
            manifestDigest = RenderDocAssetIntegrity.sha256(source);
            return manifest;
        }
    }

    private static void validateManifest(JSONObject manifest) throws JSONException {
        if (manifest.getInt("schemaVersion") != 1 || !"1.44-uu-v1".equals(manifest.getString("id"))) {
            throw new IllegalArgumentException("asset_manifest_version");
        }
        JSONObject runtime = manifest.getJSONObject("validatedRuntime");
        if (!RenderDocEnvironment.VALIDATED_WINE_ARCHIVE.equals(runtime.getString("wineArchiveSha256"))
                || !RenderDocEnvironment.VALIDATED_UNIX_NTDLL.equals(runtime.getString("unixNtdllSha256"))) {
            throw new IllegalArgumentException("asset_runtime_contract");
        }
        Set<String> roles = new HashSet<>();
        Set<String> paths = new HashSet<>();
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject entry = files.getJSONObject(i);
            String path = entry.getString("path");
            String role = entry.getString("role");
            if (!RenderDocAssetIntegrity.safeBasename(path)
                    || !entry.getString("sha256").matches("[0-9a-f]{64}")
                    || entry.getLong("bytes") <= 0 || !paths.add(path) || !roles.add(role)) {
                throw new IllegalArgumentException("asset_manifest_entry");
            }
        }
        for (String role : REQUIRED_ROLES) {
            if (!roles.contains(role)) throw new IllegalArgumentException("asset_role_missing:" + role);
        }
    }

    private JSONObject runtimeStatus(boolean arm64ec) throws JSONException {
        JSONObject status = new JSONObject();
        BoxSpec.Layer wine = null;
        if (BuildConfig.UU_PROBE_BUILD) {
            for (BoxSpec.Layer layer : BoxRuntime.get(context).getSpec().layers) {
                if ("wine-runtime".equals(layer.type)) { wine = layer; break; }
            }
        }
        String archive = wine != null ? wine.checksum : "";
        File unix = wine != null
                ? new File(new File(context.getFilesDir(), "imagefs"),
                        wine.target.replaceFirst("^/", "") + "/lib/wine/aarch64-unix/ntdll.so")
                : null;
        String hash = "";
        try { if (unix != null && unix.isFile()) hash = RenderDocAssetIntegrity.sha256(unix); }
        catch (Exception ignored) {}
        boolean supported = RenderDocEnvironment.isValidatedRuntime(
                BuildConfig.UU_PROBE_BUILD, true, arm64ec, archive, hash);
        status.put("supported", supported);
        status.put("reason", supported ? "" : !BuildConfig.UU_PROBE_BUILD
                ? "uu_box_only" : "runtime_not_validated");
        status.put("wineArchiveSha256", archive);
        JSONObject ntdll = new JSONObject();
        ntdll.put("path", unix != null ? unix.getAbsolutePath() : "");
        ntdll.put("sha256", hash);
        status.put("ntdllUnix", ntdll);
        return status;
    }

    private static void verifyPayload(File directory, JSONObject manifest) throws Exception {
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject entry = files.getJSONObject(i);
            File file = new File(directory, entry.getString("path"));
            RenderDocAssetIntegrity.requireFile(file, entry.getLong("bytes"), entry.getString("sha256"));
            if ("loader".equals(entry.getString("role"))) {
                RenderDocAssetIntegrity.requireLoaderAlias(directory,
                        entry.getLong("bytes"), entry.getString("sha256"));
            }
        }
    }

    private static void verifyFiles(File directory, JSONObject manifest, String manifestDigest) throws Exception {
        verifyPayload(directory, manifest);
        if (!manifestDigest.equals(RenderDocAssetIntegrity.sha256(new File(directory, "manifest.json")))) {
            throw new IOException("asset_manifest_identity");
        }
        JSONArray files = manifest.getJSONArray("files");
        File layer = new File(directory, "layers/VkLayer_RenderDoc.json");
        if (!layer.isFile()) throw new IOException("layer_manifest_missing");
        JSONObject layerJson = new JSONObject(AtomicFileSupport.readUtf8(layer)).getJSONObject("layer");
        String layerLibrary = "";
        for (int i = 0; i < files.length(); i++) {
            JSONObject entry = files.getJSONObject(i);
            if ("layer".equals(entry.getString("role"))) {
                layerLibrary = new File(directory, entry.getString("path")).getAbsolutePath();
            }
        }
        if (!RenderDocEnvironment.LAYER.equals(layerJson.getString("name"))
                || !layerLibrary.equals(layerJson.getString("library_path"))) {
            throw new IOException("layer_manifest_identity");
        }
    }

    /** Installs immutable optional assets before persisting an explicit enabled setting. */
    public void prepare() throws Exception {
        synchronized (ASSET_LOCK) { prepareLocked(); }
    }

    private void prepareLocked() throws Exception {
        if (!BuildConfig.UU_PROBE_BUILD) throw new IllegalStateException("uu_box_only");
        JSONObject runtime = runtimeStatus(BoxRuntime.get(context).getWineRuntimeIdentifier().contains("arm64ec"));
        if (!runtime.getBoolean("supported")) throw new IllegalStateException(runtime.getString("reason"));
        JSONObject manifest = bundledManifest();
        File destination = assetsDirectory(manifest);
        try { verifyFiles(destination, manifest, manifestDigest); return; }
        catch (Exception ignored) {}
        if (destination.exists() && XServerDisplayActivity.hasActiveSession()) {
            throw new IllegalStateException("asset_repair_requires_idle_session");
        }
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("asset_directory_failed");
        File staging = new File(parent, destination.getName() + ".pending-" + UUID.randomUUID());
        File backup = new File(parent, destination.getName() + ".backup-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("asset_staging_failed");
        boolean backedUp = false;
        try {
            JSONArray files = manifest.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i);
                File outputFile = new File(staging, entry.getString("path"));
                try (InputStream input = context.getAssets().open(ASSET_ROOT + "/" + entry.getString("path"));
                     FileOutputStream output = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    output.getFD().sync();
                }
                FileUtils.chmod(outputFile, entry.optBoolean("executable", false) ? 0700 : 0600);
            }
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i);
                if ("loader".equals(entry.getString("role"))) {
                    RenderDocAssetIntegrity.createLoaderAlias(staging, entry.getString("path"),
                            entry.getLong("bytes"), entry.getString("sha256"));
                    FileUtils.chmod(new File(staging, "libvulkan.so"), 0600);
                }
            }
            try (FileOutputStream output = new FileOutputStream(new File(staging, "manifest.json"))) {
                output.write(manifestBytes);
                output.getFD().sync();
            }
            verifyPayload(staging, manifest);
            // The generated layer manifest points to the final, versioned directory.
            writeLayerManifest(staging, destination, manifest);
            if (destination.exists()) {
                if (!destination.renameTo(backup)) throw new IOException("asset_backup_failed");
                backedUp = true;
            }
            if (!staging.renameTo(destination)) throw new IOException("asset_activate_failed");
            verifyFiles(destination, manifest, manifestDigest);
            if (backedUp) deleteOwnedTree(backup);
        }
        catch (Exception error) {
            deleteOwnedTree(staging);
            if (backedUp) {
                deleteOwnedTree(destination);
                backup.renameTo(destination);
            }
            throw error;
        }
    }

    private static void writeLayerManifest(File staging, File destination, JSONObject manifest) throws Exception {
        String library = "";
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject entry = files.getJSONObject(i);
            if ("layer".equals(entry.getString("role"))) library = entry.getString("path");
        }
        JSONObject layer = new JSONObject();
        layer.put("name", RenderDocEnvironment.LAYER);
        layer.put("type", "GLOBAL");
        layer.put("library_path", new File(destination, library).getAbsolutePath());
        layer.put("api_version", "1.3.0");
        layer.put("implementation_version", "44");
        layer.put("description", "RenderDoc 1.44 UU Box capture");
        layer.put("enable_environment", new JSONObject().put("ENABLE_VULKAN_RENDERDOC_CAPTURE", "1"));
        layer.put("disable_environment", new JSONObject().put("DISABLE_VULKAN_RENDERDOC_CAPTURE", "1"));
        layer.put("functions", new JSONObject()
                .put("vkGetInstanceProcAddr", "VK_LAYER_RENDERDOC_CaptureGetInstanceProcAddr")
                .put("vkGetDeviceProcAddr", "VK_LAYER_RENDERDOC_CaptureGetDeviceProcAddr")
                .put("vkNegotiateLoaderLayerInterfaceVersion", "VK_LAYER_RENDERDOC_CaptureNegotiateLoaderLayerInterfaceVersion"));
        JSONObject json = new JSONObject().put("file_format_version", "1.0.0").put("layer", layer);
        AtomicFileSupport.writeUtf8(new File(staging, "layers/VkLayer_RenderDoc.json"), json.toString(2));
    }

    public boolean saveEnabled(boolean enabled) throws Exception {
        if (!BuildConfig.UU_PROBE_BUILD) throw new IllegalStateException("uu_box_only");
        if (enabled) prepare();
        return preferences.edit().putBoolean(PREF_ENABLED, enabled).commit();
    }

    /** Owns the environment only in the UU Box caller; never updates persistent runtime env. */
    public void applyLaunchEnvironment(EnvVars environment, boolean arm64ec) {
        boolean enabled = isEnabled();
        String captureRoot = "";
        String layerDirectory = "";
        if (enabled) {
            try {
                if (!runtimeStatus(arm64ec).getBoolean("supported")) {
                    throw new IllegalStateException("runtime_not_validated");
                }
                JSONObject manifest = bundledManifest();
                File directory = assetsDirectory(manifest);
                verifyFiles(directory, manifest, manifestDigest);
                layerDirectory = new File(directory, "layers").getAbsolutePath();
                File output = new File(context.getFilesDir(), "rdoc-standard-" + UUID.randomUUID());
                if (!new File(output, "RenderDoc").mkdirs()) throw new IOException("capture_directory_failed");
                captureRoot = output.getAbsolutePath();
            }
            catch (Exception error) {
                throw new IllegalStateException("renderdoc_not_ready:" + error.getMessage(), error);
            }
        }
        RenderDocEnvironment.apply(environment, enabled, layerDirectory, captureRoot,
                context.getFilesDir().getAbsolutePath());
        JSONObject snapshot = new JSONObject();
        try {
            snapshot.put("enabled", enabled);
            snapshot.put("sessionId", BoxRuntime.get(context).getSessionManager().getCurrentSessionId());
            snapshot.put("captureRoot", captureRoot);
            snapshot.put("createdAt", System.currentTimeMillis());
            JSONObject replayEnvironment = new JSONObject();
            for (String key : REPLAY_ENV) {
                if (environment.has(key)) replayEnvironment.put(key, environment.get(key));
            }
            snapshot.put("replayEnvironment", replayEnvironment);
        }
        catch (JSONException ignored) {}
        preferences.edit().putString(PREF_LAUNCH, snapshot.toString()).commit();
    }

    /** Desired next-launch state and latest launch snapshot; not proof that the library is mapped. */
    public JSONObject buildStatus() {
        JSONObject result = new JSONObject();
        if (!BuildConfig.UU_PROBE_BUILD) {
            try {
                result.put("enabled", false);
                result.put("supported", false);
                result.put("ready", false);
                result.put("reason", "uu_box_only");
                result.put("restartRequired", false);
                result.put("currentSessionEnabled", false);
                result.put("currentSessionKnown", false);
                result.put("currentSessionStateSource", "unavailable");
                result.put("captureRoot", "");
                result.put("assets", new JSONObject());
                result.put("runtime", new JSONObject());
                result.put("replayEnvironment", new JSONObject());
                result.put("lastSession", new JSONObject());
            }
            catch (JSONException ignored) {}
            return result;
        }
        try {
            boolean active = XServerDisplayActivity.hasActiveSession();
            JSONObject last = new JSONObject(preferences.getString(PREF_LAUNCH, "{}"));
            boolean known = active && !last.optString("sessionId", "").isEmpty()
                    && last.optString("sessionId", "").equals(
                            BoxRuntime.get(context).getSessionManager().getCurrentSessionId());
            boolean current = known && last.optBoolean("enabled", false);
            JSONObject runtime = runtimeStatus(BuildConfig.UU_PROBE_BUILD
                    && BoxRuntime.get(context).getWineRuntimeIdentifier().contains("arm64ec"));
            boolean supported = runtime.getBoolean("supported");
            String reason = runtime.getString("reason");
            JSONObject assets = new JSONObject();
            boolean prepared = false;
            try {
                JSONObject manifest = bundledManifest();
                File directory = assetsDirectory(manifest);
                assets.put("id", manifest.getString("id"));
                assets.put("deploymentId", directory.getName());
                assets.put("manifestSha256", manifestDigest);
                assets.put("manifest", new File(directory, "manifest.json").getAbsolutePath());
                assets.put("layerDirectory", new File(directory, "layers").getAbsolutePath());
                JSONArray files = manifest.getJSONArray("files");
                for (int i = 0; i < files.length(); i++) {
                    JSONObject entry = new JSONObject(files.getJSONObject(i).toString());
                    entry.put("path", new File(directory, entry.getString("path")).getAbsolutePath());
                    assets.put(entry.getString("role"), entry);
                    if ("loader".equals(entry.getString("role"))) {
                        assets.put("loaderReplayAlias", new JSONObject()
                                .put("path", new File(directory, "libvulkan.so").getAbsolutePath())
                                .put("sha256", entry.getString("sha256"))
                                .put("bytes", entry.getLong("bytes")));
                    }
                }
                try { verifyFiles(directory, manifest, manifestDigest); prepared = true; }
                catch (Exception error) { if (reason.isEmpty()) reason = error.getMessage(); }
            }
            catch (Exception error) { if (reason.isEmpty()) reason = "assets_unavailable:" + error.getMessage(); }
            result.put("enabled", isEnabled());
            result.put("supported", supported);
            result.put("ready", supported && prepared);
            result.put("reason", reason);
            result.put("restartRequired", active && (!known || isEnabled() != current));
            result.put("currentSessionKnown", known);
            result.put("currentSessionEnabled", current);
            result.put("currentSessionStateSource", "launch_snapshot");
            result.put("captureRoot", known ? last.optString("captureRoot", "") : "");
            result.put("lastSession", last);
            result.put("replayEnvironment", last.optJSONObject("replayEnvironment") != null
                    ? last.getJSONObject("replayEnvironment") : new JSONObject());
            result.put("runtime", runtime);
            result.put("assets", assets);
        }
        catch (Exception error) {
            try { result.put("enabled", isEnabled()); result.put("ready", false);
                result.put("supported", false); result.put("reason", error.getMessage()); }
            catch (JSONException ignored) {}
        }
        return result;
    }

    private static void deleteOwnedTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteOwnedTree(child);
        file.delete();
    }
}
