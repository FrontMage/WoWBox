package com.winlator.box;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import com.winlator.XServerDisplayActivity;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Owns the private LSFG DLL, startup-only configuration and launch environment. */
public final class FrameGenerationManager {
    public static final String LAYER_TYPE = "frame-generation-stack";
    public static final String PREF_ENABLED = "frame_generation_enabled";
    public static final String PREF_MULTIPLIER = "frame_generation_multiplier";
    public static final String PREF_FLOW_SCALE = "frame_generation_flow_scale";
    public static final String PREF_PERFORMANCE = "frame_generation_performance_mode";
    public static final String PREF_LAST_ERROR = "frame_generation_last_error";
    public static final int DEFAULT_MULTIPLIER = 2;
    public static final float DEFAULT_FLOW_SCALE = 0.80f;
    public static final boolean DEFAULT_PERFORMANCE = true;
    public static final String PRESENT_MODE = "fifo";
    public static final int OUTPUT_FPS_CAP = 60;
    public static final String PRESENT_WAIT_COMPAT_ENV = "WRAPPER_DISABLE_PRESENT_WAIT";

    private static final String PROCESS_IDENTIFIER = "wow-box-lsfg";
    private static final String DLL_NAME = "Lossless.dll";
    private static final String CONFIG_NAME = "conf.toml";
    private static final String LAYER_LIBRARY =
            "/usr/lib/liblsfg-vk-layer.so";
    private static final String LAYER_MANIFEST =
            "/usr/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json";

    private final Context context;
    private final SharedPreferences preferences;

    public FrameGenerationManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
    }

    public File getPrivateDirectory() {
        return new File(context.getFilesDir(), "box/frame-generation");
    }

    public File getDllFile() {
        return new File(getPrivateDirectory(), DLL_NAME);
    }

    public File getConfigFile() {
        return new File(getPrivateDirectory(), CONFIG_NAME);
    }

    public LosslessDllValidator.Validation validateInstalledDll() {
        return LosslessDllValidator.validate(getDllFile());
    }

    public LosslessDllValidator.Validation importDll(Uri uri) {
        if (uri == null) return rememberFailure(LosslessDllValidator.Validation.invalid("uri_missing"));
        File directory = getPrivateDirectory();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return rememberFailure(LosslessDllValidator.Validation.invalid("private_directory_failed"));
        }

        File pending = new File(directory, DLL_NAME + ".importing");
        File backup = new File(directory, DLL_NAME + ".backup");
        FileUtils.delete(pending);
        FileUtils.delete(backup);

        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(pending)) {
            if (input == null) throw new IllegalArgumentException("uri_open_failed");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > LosslessDllValidator.MAX_DLL_SIZE) {
                    throw new IllegalArgumentException("dll_too_large");
                }
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        catch (Exception e) {
            FileUtils.delete(pending);
            return rememberFailure(
                    LosslessDllValidator.Validation.invalid("dll_import_failed:" + e.getMessage()));
        }

        LosslessDllValidator.Validation validation = LosslessDllValidator.validate(pending);
        if (!validation.valid) {
            FileUtils.delete(pending);
            return rememberFailure(validation);
        }

        File destination = getDllFile();
        boolean backedUp = destination.isFile() && destination.renameTo(backup);
        if (destination.exists() && !backedUp) {
            FileUtils.delete(pending);
            return rememberFailure(
                    LosslessDllValidator.Validation.invalid("dll_backup_failed"));
        }
        if (!pending.renameTo(destination)) {
            if (backedUp) backup.renameTo(destination);
            FileUtils.delete(pending);
            return rememberFailure(
                    LosslessDllValidator.Validation.invalid("dll_activate_failed"));
        }
        FileUtils.chmod(destination, 0600);
        FileUtils.delete(backup);
        preferences.edit().putString(PREF_LAST_ERROR, "").apply();
        // The layer watches conf.toml and can rebuild its swapchain when the
        // file changes. Keep imports startup-only just like settings changes.
        if (!XServerDisplayActivity.hasActiveSession()) writeConfig();
        return validation;
    }

    public void putSettings(
            SharedPreferences.Editor editor,
            boolean enabled,
            int multiplier,
            float flowScale,
            boolean performanceMode) {
        editor.putBoolean(PREF_ENABLED, enabled);
        editor.putInt(PREF_MULTIPLIER, normalizeMultiplier(multiplier));
        editor.putFloat(PREF_FLOW_SCALE, normalizeFlowScale(flowScale));
        editor.putBoolean(PREF_PERFORMANCE, performanceMode);
    }

    public boolean saveSettings(
            boolean enabled,
            int multiplier,
            float flowScale,
            boolean performanceMode) {
        SharedPreferences.Editor editor = preferences.edit();
        putSettings(editor, enabled, multiplier, flowScale, performanceMode);
        boolean saved = editor.commit();
        if (!saved) return false;
        LosslessDllValidator.Validation dll = validateInstalledDll();
        if (!dll.valid) {
            if (!enabled) {
                preferences.edit().putString(PREF_LAST_ERROR, "").apply();
                return true;
            }
            preferences.edit().putString(PREF_LAST_ERROR, dll.reason).apply();
            return false;
        }
        // Preferences are the desired next-launch state. Do not touch the
        // currently watched TOML while a Box session is alive.
        if (XServerDisplayActivity.hasActiveSession()) {
            preferences.edit().putString(PREF_LAST_ERROR, "").apply();
            return true;
        }
        return writeConfig();
    }

    public int getMultiplier() {
        return normalizeMultiplier(preferences.getInt(PREF_MULTIPLIER, DEFAULT_MULTIPLIER));
    }

    public float getFlowScale() {
        return normalizeFlowScale(preferences.getFloat(PREF_FLOW_SCALE, DEFAULT_FLOW_SCALE));
    }

    public boolean isPerformanceMode() {
        return preferences.getBoolean(PREF_PERFORMANCE, DEFAULT_PERFORMANCE);
    }

    public boolean isEnabled() {
        return preferences.getBoolean(PREF_ENABLED, false);
    }

    public boolean writeConfig() {
        LosslessDllValidator.Validation dll = validateInstalledDll();
        if (!dll.valid) {
            preferences.edit().putString(PREF_LAST_ERROR, dll.reason).apply();
            return false;
        }

        File directory = getPrivateDirectory();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            preferences.edit().putString(PREF_LAST_ERROR, "config_directory_failed").apply();
            return false;
        }

        String config = buildConfig(
                getDllFile().getAbsolutePath(),
                getMultiplier(),
                getFlowScale(),
                isPerformanceMode());
        File pending = new File(directory, CONFIG_NAME + ".new");
        FileUtils.delete(pending);
        if (!FileUtils.writeString(pending, config)) {
            preferences.edit().putString(PREF_LAST_ERROR, "config_write_failed").apply();
            return false;
        }
        File destination = getConfigFile();
        File backup = new File(directory, CONFIG_NAME + ".backup");
        FileUtils.delete(backup);
        boolean backedUp = destination.isFile() && destination.renameTo(backup);
        if (destination.exists() && !backedUp) {
            FileUtils.delete(pending);
            preferences.edit().putString(PREF_LAST_ERROR, "config_backup_failed").apply();
            return false;
        }
        if (!pending.renameTo(destination)) {
            if (backedUp) backup.renameTo(destination);
            FileUtils.delete(pending);
            preferences.edit().putString(PREF_LAST_ERROR, "config_activate_failed").apply();
            return false;
        }
        FileUtils.chmod(destination, 0600);
        FileUtils.delete(backup);
        preferences.edit().putString(PREF_LAST_ERROR, "").apply();
        return true;
    }

    public boolean applyLaunchEnv(EnvVars envVars) {
        envVars.remove("LSFG_CONFIG");
        envVars.remove("LSFG_PROCESS");
        envVars.remove("LSFG_PROCESS_EXE");
        envVars.remove("LSFG_DLL_PATH_UNIX");

        LosslessDllValidator.Validation dll = validateInstalledDll();
        boolean active = isEnabled() && dll.valid && isLayerInstalled() && writeConfig();
        if (!active) {
            envVars.put("DISABLE_LSFG", "1");
            if (isEnabled()) {
                String reason = !dll.valid ? dll.reason
                        : !isLayerInstalled() ? "frame_generation_layer_missing"
                        : "frame_generation_config_invalid";
                preferences.edit().putString(PREF_LAST_ERROR, reason).apply();
            }
            else {
                preferences.edit().putString(PREF_LAST_ERROR, "").apply();
            }
            return false;
        }

        envVars.remove("DISABLE_LSFG");
        envVars.put("LSFG_CONFIG", getConfigFile().getAbsolutePath());
        envVars.put("LSFG_PROCESS", PROCESS_IDENTIFIER);
        envVars.put("LSFG_DLL_PATH_UNIX", getDllFile().getAbsolutePath());
        // LSFG expands one application present into multiple real presents.
        // vkd3d-proton's VK_KHR_present_wait frame-latency worker assumes a
        // one-to-one present-id stream and can stall while creating the WoW
        // D3D12 swapchain. The managed wrapper already has a scoped switch
        // that hides this optional extension; force it only for active LSFG
        // launches and leave ordinary rendering unchanged.
        applyVulkanCompatibilityEnv(envVars);
        // The pinned lsfg-vk layer has no output-FPS limiter. Cap DXVK's real
        // presents before interpolation so the selected multiplier cannot
        // produce more than OUTPUT_FPS_CAP frames per second.
        envVars.put("DXVK_FRAME_RATE", String.valueOf(sourceFpsCapForMultiplier(getMultiplier())));
        preferences.edit().putString(PREF_LAST_ERROR, "").apply();
        return true;
    }

    public JSONObject buildStatus() throws JSONException {
        LosslessDllValidator.Validation dll = validateInstalledDll();
        BoxSpec.Layer layer = findLayer();
        File library = resolveImageFsPath(LAYER_LIBRARY);
        File manifest = resolveImageFsPath(LAYER_MANIFEST);
        String expectedConfig = dll.valid
                ? buildConfig(
                        getDllFile().getAbsolutePath(),
                        getMultiplier(),
                        getFlowScale(),
                        isPerformanceMode())
                : "";
        String currentConfig = getConfigFile().isFile()
                ? FileUtils.readString(getConfigFile())
                : "";

        JSONObject result = new JSONObject();
        result.put("supported", layer != null);
        result.put("enabled", isEnabled());
        result.put("restartRequired", XServerDisplayActivity.hasActiveSession());
        result.put("multiplier", getMultiplier());
        result.put("flowScale", getFlowScale());
        result.put("performanceMode", isPerformanceMode());
        result.put("presentMode", PRESENT_MODE);
        result.put("presentWaitDisabled", isEnabled());
        result.put("outputFpsCap", OUTPUT_FPS_CAP);
        result.put("sourceFpsCap", sourceFpsCapForMultiplier(getMultiplier()));
        result.put("hdrMode", false);
        result.put("lastError", preferences.getString(PREF_LAST_ERROR, ""));

        JSONObject dllJson = new JSONObject();
        dllJson.put("path", getDllFile().getAbsolutePath());
        dllJson.put("exists", getDllFile().isFile());
        dllJson.put("valid", dll.valid);
        dllJson.put("reason", dll.reason);
        dllJson.put("version", dll.version);
        dllJson.put("size", dll.size);
        dllJson.put("sha256", dll.sha256);
        dllJson.put("machine", String.format(Locale.US, "0x%04x", dll.machine));
        JSONArray missing = new JSONArray();
        for (int id : dll.missingResourceIds) missing.put(id);
        dllJson.put("missingResourceIds", missing);
        result.put("dll", dllJson);

        JSONObject layerJson = new JSONObject();
        layerJson.put("type", LAYER_TYPE);
        layerJson.put("source", layer != null ? layer.source : "");
        layerJson.put("archiveSha256", layer != null ? layer.checksum : "");
        layerJson.put("library", fileStatus(library));
        layerJson.put("manifest", fileStatus(manifest));
        layerJson.put("installed", library.isFile() && manifest.isFile());
        result.put("layer", layerJson);

        JSONObject configJson = new JSONObject();
        configJson.put("path", getConfigFile().getAbsolutePath());
        configJson.put("exists", getConfigFile().isFile());
        configJson.put("matchesSettings", dll.valid && expectedConfig.equals(currentConfig));
        configJson.put("sha256", getConfigFile().isFile() ? sha256(getConfigFile()) : "");
        result.put("config", configJson);

        boolean ready = layer != null && library.isFile() && manifest.isFile() &&
                (!isEnabled() || (dll.valid && expectedConfig.equals(currentConfig)));
        result.put("ready", ready);
        result.put("activeOnNextLaunch", ready && isEnabled());
        return result;
    }

    public static String buildConfig(
            String dllPath, int multiplier, float flowScale, boolean performanceMode) {
        String safePath = dllPath == null ? "" : dllPath
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return "version = 1\n\n" +
                "[global]\n" +
                "dll = \"" + safePath + "\"\n" +
                "no_fp16 = false\n\n" +
                "[[game]]\n" +
                "exe = \"" + PROCESS_IDENTIFIER + "\"\n" +
                "multiplier = " + normalizeMultiplier(multiplier) + "\n" +
                "flow_scale = " +
                String.format(Locale.US, "%.2f", normalizeFlowScale(flowScale)) + "\n" +
                "performance_mode = " + (performanceMode ? "true" : "false") + "\n" +
                "hdr_mode = false\n" +
                "experimental_present_mode = \"" + PRESENT_MODE + "\"\n";
    }

    public static int normalizeMultiplier(int value) {
        return Math.max(2, Math.min(4, value));
    }

    public static float normalizeFlowScale(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return DEFAULT_FLOW_SCALE;
        return Math.max(0.25f, Math.min(1.0f, value));
    }

    public static int sourceFpsCapForMultiplier(int multiplier) {
        return OUTPUT_FPS_CAP / normalizeMultiplier(multiplier);
    }

    static void applyVulkanCompatibilityEnv(EnvVars envVars) {
        envVars.put(PRESENT_WAIT_COMPAT_ENV, "1");
    }

    private LosslessDllValidator.Validation rememberFailure(
            LosslessDllValidator.Validation validation) {
        preferences.edit().putString(PREF_LAST_ERROR, validation.reason).apply();
        return validation;
    }

    private BoxSpec.Layer findLayer() {
        for (BoxSpec.Layer layer : BoxRuntime.get(context).getSpec().layers) {
            if (LAYER_TYPE.equals(layer.type)) return layer;
        }
        return null;
    }

    private boolean isLayerInstalled() {
        return resolveImageFsPath(LAYER_LIBRARY).isFile() &&
                resolveImageFsPath(LAYER_MANIFEST).isFile();
    }

    private File resolveImageFsPath(String absolutePath) {
        String relative = absolutePath;
        while (relative.startsWith("/")) relative = relative.substring(1);
        return new File(ImageFs.find(context).getRootDir(), relative);
    }

    private JSONObject fileStatus(File file) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("path", file.getAbsolutePath());
        result.put("exists", file.isFile());
        result.put("size", file.isFile() ? file.length() : 0);
        result.put("sha256", file.isFile() ? sha256(file) : "");
        return result;
    }

    private static String sha256(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder value = new StringBuilder();
            for (byte item : digest.digest()) {
                value.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return value.toString();
        }
        catch (Exception ignored) {
            return "";
        }
    }
}
