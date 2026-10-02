package com.winlator.box;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.winlator.BuildConfig;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.StringUtils;
import com.winlator.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class BoxRuntime {
    private static final String TAG = "BoxRuntime";
    private static final String LEGACY_DLLU_BOX_ID = "dllu-wow-bionic-109-fixes";
    private static final String FRONTMAGE_BOX_ID = "frontmage-bnet-arm64";
    private static final String STATE_BOX_INPUT_GESTURE_MIGRATION_VERSION = "boxInputGestureMigrationVersion";
    private static final int BOX_INPUT_GESTURE_MIGRATION_VERSION = 1;
    private static final String STATE_BOX_HUD_MIGRATION_VERSION = "boxHudMigrationVersion";
    private static final int BOX_HUD_MIGRATION_VERSION = 1;
    private static final String STATE_BOX_BRANDING_MIGRATION_VERSION = "boxBrandingMigrationVersion";
    private static final int BOX_BRANDING_MIGRATION_VERSION = 1;
    private static final String STATE_WOW_INPUT_BRIDGE_MIGRATION_VERSION = "wowInputBridgeMigrationVersion";
    private static final String STATE_WOW_INPUT_BRIDGE_MIGRATION_SOURCE = "wowInputBridgeMigrationSource";
    private static final String STATE_WOW_INPUT_BRIDGE_MIGRATION_CHECKSUM = "wowInputBridgeMigrationChecksum";
    private static final int WOW_INPUT_BRIDGE_MIGRATION_VERSION = 1;
    public static final String BOX_CONTAINER_NAME = BoxBrandingMigration.WOW_BOX_CONTAINER_NAME;
    public static final String PREF_OPEN_LINKS_IN_ANDROID_BROWSER =
            "open_links_in_android_browser";
    public static final boolean DEFAULT_OPEN_LINKS_IN_ANDROID_BROWSER = true;
    private static BoxRuntime instance;

    private final Context context;
    private BoxSpec spec;
    private BoxSessionManager sessionManager;

    private BoxRuntime(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized BoxRuntime get(Context context) {
        if (instance == null) instance = new BoxRuntime(context);
        return instance;
    }

    public synchronized BoxSessionManager getSessionManager() {
        if (sessionManager == null) sessionManager = new BoxSessionManager(context);
        return sessionManager;
    }

    public synchronized BoxSpec getSpec() {
        if (spec == null) {
            ensureBoxDir();
            recoverSpecTransactionIfNeeded();
            spec = BoxSpec.load(context, BoxPaths.getSpecFile(context));
            migrateLegacyBoxSpecIfNeeded();
            migrateRequiredInputBridgeLayerIfNeeded();
            if (!BuildConfig.AGENT_DRIVE_PROBE_BUILD) {
                migrateRequiredFrameGenerationLayerIfNeeded();
            }
            migrateRetiredLaunchTargetsIfNeeded();
            migrateBattleNetGpuRenderingArgsIfNeeded();
            migrateWineDebugOwnershipIfNeeded();
            migrateVerifiedWowInputBridgeStateIfNeeded();
            persistSpecIfMissing();
            injectAgentDriveProbeTargets();
        }
        return spec;
    }

    private void injectAgentDriveProbeTargets() {
        if (!BuildConfig.AGENT_DRIVE_PROBE_BUILD || spec == null || spec.launch == null) return;
        addAgentDriveProbeTarget(
                "drive-info-probe-i686",
                "Drive information probe (i686)",
                "D:\\DriveInfoProbe-i686.exe");
        addAgentDriveProbeTarget(
                "drive-info-probe-x64",
                "Drive information probe (x64 control)",
                "D:\\DriveInfoProbe-x64.exe");
    }

    private void addAgentDriveProbeTarget(String id, String label, String guestPath) {
        if (spec.launch.findTarget(id) != null) return;
        BoxSpec.Target target = new BoxSpec.Target();
        target.id = id;
        target.label = label;
        target.kind = "executable";
        target.guestPath = guestPath;
        target.workingDirectory = "D:\\";
        target.requiresExists = true;
        target.hidden = false;
        spec.launch.targets.add(target);
    }

    private void migrateRetiredLaunchTargetsIfNeeded() {
        if (spec == null || spec.launch == null) return;

        boolean changed = spec.launch.targets.removeIf(target ->
                "sc2-via-bnet".equals(target.id) ||
                        "d2r-via-bnet".equals(target.id) ||
                        "wow-via-bnet".equals(target.id) ||
                        "wow-direct-bnet-running".equals(target.id));
        BoxSpec.Target testD3D = spec.launch.findTarget("testd3d");
        if (testD3D != null && !testD3D.hidden) {
            testD3D.hidden = true;
            changed = true;
        }
        if (spec.launch.desktopButtonEnabled) {
            spec.launch.desktopButtonEnabled = false;
            changed = true;
        }
        if (!"WoW Box".equals(spec.displayName)) {
            spec.displayName = "WoW Box";
            changed = true;
        }

        String requiredDefault = spec.launch.findTarget("bnet") != null ? "bnet" : "";
        if (!requiredDefault.equals(spec.launch.defaultTargetId)) {
            spec.launch.defaultTargetId = requiredDefault;
            changed = true;
        }
        if (!changed) return;

        FileUtils.writeString(BoxPaths.getSpecFile(context), specToString(spec));
        Log.i(TAG, "Migrated persisted launcher to the public WoW Box surface");
    }

    private void migrateRequiredInputBridgeLayerIfNeeded() {
        if (spec == null) return;
        BoxSpec bundled = BoxSpec.fromJsonString(FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
        if (!FRONTMAGE_BOX_ID.equals(spec.boxId) || !spec.boxId.equals(bundled.boxId)) return;

        BoxSpec.Layer required = findLayer(bundled, "input-bridge-stack");
        BoxSpec.Layer current = findLayer(spec, "input-bridge-stack");
        if (required == null) return;
        bindInputBridgeToActiveWineRuntime(spec, required);
        if (current != null &&
                required.source.equals(current.source) &&
                required.checksum.equalsIgnoreCase(current.checksum) &&
                required.runtimeIdentifier.equals(current.runtimeIdentifier) &&
                required.target.equals(current.target)) {
            return;
        }

        int index = current != null ? spec.layers.indexOf(current) : spec.layers.size();
        if (current != null) spec.layers.remove(current);
        spec.layers.add(index, required);
        FileUtils.writeString(BoxPaths.getSpecFile(context), specToString(spec));
        Log.i(TAG, "Updated the required WoW input bridge identity in the persisted Box spec");
    }

    private void migrateRequiredFrameGenerationLayerIfNeeded() {
        if (spec == null) return;
        BoxSpec bundled = BoxSpec.fromJsonString(
                FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
        if (!FRONTMAGE_BOX_ID.equals(spec.boxId) || !spec.boxId.equals(bundled.boxId)) return;

        BoxSpec.Layer required = findLayer(bundled, FrameGenerationManager.LAYER_TYPE);
        BoxSpec.Layer current = findLayer(spec, FrameGenerationManager.LAYER_TYPE);
        if (required == null) return;
        if (current != null &&
                required.source.equals(current.source) &&
                required.checksum.equalsIgnoreCase(current.checksum) &&
                required.runtimeIdentifier.equals(current.runtimeIdentifier) &&
                required.target.equals(current.target)) {
            return;
        }

        int index = current != null ? spec.layers.indexOf(current) : -1;
        if (current != null) spec.layers.remove(current);
        if (index < 0) {
            BoxSpec.Layer graphics = findLayer(spec, "graphics-wrapper-stack");
            index = graphics != null ? spec.layers.indexOf(graphics) + 1 : spec.layers.size();
        }
        spec.layers.add(Math.min(index, spec.layers.size()), required);
        FileUtils.writeString(BoxPaths.getSpecFile(context), specToString(spec));
        Log.i(TAG, "Updated the required frame generation layer identity in the persisted Box spec");
    }

    private void migrateBattleNetGpuRenderingArgsIfNeeded() {
        if (spec == null || spec.launch == null) return;

        BoxSpec.Target target = spec.launch.findTarget("bnet");
        if (target == null) return;
        boolean changed = target.args.removeIf(arg ->
                "--disable-gpu".equals(arg) || "--disable-gpu-compositing".equals(arg));
        if (!changed) return;

        FileUtils.writeString(BoxPaths.getSpecFile(context), specToString(spec));
        Log.i(TAG, "Removed legacy software rendering arguments from the Battle.net launch target");
    }

    private void migrateWineDebugOwnershipIfNeeded() {
        if (spec == null) return;
        boolean changed = spec.env.base.remove("WINEDEBUG") != null;
        changed |= spec.env.overrides.remove("WINEDEBUG") != null;
        if (changed) {
            FileUtils.writeString(BoxPaths.getSpecFile(context), specToString(spec));
            Log.i(TAG, "Removed legacy WINEDEBUG from persisted Box spec");
        }
    }

    private static BoxSpec.Layer findLayer(BoxSpec source, String type) {
        if (source == null || type == null) return null;
        for (BoxSpec.Layer layer : source.layers) {
            if (type.equals(layer.type)) return layer;
        }
        return null;
    }

    private void migrateVerifiedWowInputBridgeStateIfNeeded() {
        BoxSpec.Layer required = findLayer(spec, "input-bridge-stack");
        if (required == null || hasWowInputBridgeMigrationIdentity(getState(), required)) return;

        JSONObject state = getState();
        JSONObject record = findInstalledInputBridgeRecord(state);
        if (record == null ||
                !required.source.equals(record.optString("source", "")) ||
                !required.checksum.equalsIgnoreCase(record.optString("checksum", ""))) {
            return;
        }

        JSONArray files = record.optJSONArray("criticalFiles");
        if (!verifiedInputBridgeFilesMatchRecord(required, files)) return;
        markWowInputBridgeMigrationComplete(required);
        Log.i(TAG, "Adopted the already verified four-file WoW input bridge installation");
    }

    private boolean verifiedInputBridgeFilesMatchRecord(BoxSpec.Layer layer, JSONArray files) {
        if (files == null || files.length() != 4) return false;
        String runtimeId = resolveRuntimeIdentifier(layer);
        String prefix = layer.target;
        String runtime = "/opt/" + runtimeId + "/lib/wine/aarch64-windows";
        String[] requiredPaths = {
                prefix + "/xinput1_3.dll",
                prefix + "/xinput1_4.dll",
                runtime + "/xinput1_3.dll",
                runtime + "/xinput1_4.dll"
        };

        for (String requiredPath : requiredPaths) {
            JSONObject record = null;
            for (int i = 0; i < files.length(); i++) {
                JSONObject candidate = files.optJSONObject(i);
                if (candidate != null && requiredPath.equals(candidate.optString("path", ""))) {
                    record = candidate;
                    break;
                }
            }
            if (record == null || !record.optBoolean("exists", false)) return false;
            String expected = record.optString("sha256", "");
            File actual = resolveRuntimeOverlayTarget(requiredPath);
            if (expected.isEmpty() || !actual.isFile() ||
                    !expected.equalsIgnoreCase(sha256File(actual))) {
                return false;
            }
        }
        return true;
    }

    public synchronized boolean isWowInputBridgeMigrationComplete() {
        JSONObject state = getState();
        BoxSpec.Layer required = findLayer(getSpec(), "input-bridge-stack");
        JSONObject record = findInstalledInputBridgeRecord(state);
        return hasWowInputBridgeMigrationIdentity(state, required) &&
                record != null &&
                required.source.equals(record.optString("source", "")) &&
                required.checksum.equalsIgnoreCase(record.optString("checksum", "")) &&
                verifiedInputBridgeFilesMatchRecord(required, record.optJSONArray("criticalFiles"));
    }

    private boolean hasWowInputBridgeMigrationIdentity(JSONObject state, BoxSpec.Layer required) {
        return required != null &&
                state.optInt(STATE_WOW_INPUT_BRIDGE_MIGRATION_VERSION, 0) >=
                        WOW_INPUT_BRIDGE_MIGRATION_VERSION &&
                required.source.equals(state.optString(STATE_WOW_INPUT_BRIDGE_MIGRATION_SOURCE, "")) &&
                required.checksum.equalsIgnoreCase(
                        state.optString(STATE_WOW_INPUT_BRIDGE_MIGRATION_CHECKSUM, ""));
    }

    private JSONObject findInstalledInputBridgeRecord(JSONObject state) {
        JSONArray installed = state != null ? state.optJSONArray("installedLayers") : null;
        if (installed == null) return null;
        for (int i = 0; i < installed.length(); i++) {
            JSONObject item = installed.optJSONObject(i);
            if (item != null && "input-bridge-stack".equals(item.optString("type", ""))) {
                return item;
            }
        }
        return null;
    }

    public synchronized void requireWowInputBridgeMigrationComplete() {
        if (!isWowInputBridgeMigrationComplete()) {
            throw new IllegalStateException("wow_input_bridge_migration_required");
        }
    }

    public synchronized void markWowInputBridgeMigrationComplete(BoxSpec.Layer installedLayer) {
        if (installedLayer == null || !"input-bridge-stack".equals(installedLayer.type)) {
            throw new IllegalArgumentException("input_bridge_layer_required");
        }
        JSONObject state = getState();
        try {
            state.put(STATE_WOW_INPUT_BRIDGE_MIGRATION_VERSION, WOW_INPUT_BRIDGE_MIGRATION_VERSION);
            state.put(STATE_WOW_INPUT_BRIDGE_MIGRATION_SOURCE, installedLayer.source);
            state.put(STATE_WOW_INPUT_BRIDGE_MIGRATION_CHECKSUM, installedLayer.checksum);
            state.put("wowInputBridgeMigratedAt", System.currentTimeMillis());
            saveState(state);
        }
        catch (JSONException e) {
            throw new IllegalStateException("failed_to_persist_wow_input_bridge_migration", e);
        }
    }

    public synchronized JSONObject buildWowInputBridgeMigrationStatus() {
        JSONObject result = new JSONObject();
        BoxSpec.Layer required = findLayer(getSpec(), "input-bridge-stack");
        JSONObject state = getState();
        try {
            result.put("requiredVersion", WOW_INPUT_BRIDGE_MIGRATION_VERSION);
            result.put("installedVersion",
                    state.optInt(STATE_WOW_INPUT_BRIDGE_MIGRATION_VERSION, 0));
            result.put("complete", isWowInputBridgeMigrationComplete());
            result.put("source", required != null ? required.source : "");
            result.put("checksum", required != null ? required.checksum : "");
        }
        catch (JSONException ignored) {}
        return result;
    }

    public synchronized void saveSpec(BoxSpec spec) {
        if (spec == null) throw new IllegalArgumentException("Box spec is missing");
        ensureBoxDir();
        File file = BoxPaths.getSpecFile(context);
        File pending = new File(file.getAbsolutePath() + ".layer-update-new");
        File backup = new File(file.getAbsolutePath() + ".layer-update-backup");
        recoverSpecTransactionIfNeeded();
        if (FileUtils.isSymlink(file) ||
                FileUtils.isSymlink(pending) ||
                FileUtils.isSymlink(backup)) {
            throw new IllegalStateException("Unsafe Box spec transaction path");
        }

        try {
            writeSpecFile(pending, specToString(spec));
            if (file.exists() && !file.renameTo(backup)) {
                throw new IllegalStateException("Unable to back up active Box spec");
            }
            if (!pending.renameTo(file)) {
                if (backup.exists()) backup.renameTo(file);
                throw new IllegalStateException("Unable to activate Box spec");
            }
            this.spec = spec;
            if (backup.exists() && !backup.delete()) {
                Log.w(TAG, "Unable to clean committed Box spec backup " + backup);
            }
        }
        catch (RuntimeException | java.io.IOException failure) {
            if (!file.exists() && backup.exists() && !backup.renameTo(file)) {
                failure.addSuppressed(
                        new IllegalStateException("Unable to restore active Box spec"));
            }
            if (pending.exists() && !pending.delete()) {
                failure.addSuppressed(
                        new IllegalStateException("Unable to remove pending Box spec"));
            }
            throw new IllegalStateException("Unable to persist Box spec", failure);
        }
    }

    public synchronized BoxSpec buildSpecWithBundledLayersForUpdate(String... layerTypes) {
        if (layerTypes == null || layerTypes.length == 0) {
            throw new IllegalArgumentException("layer_type_missing");
        }
        try {
            BoxSpec candidate = BoxSpec.fromJson(new JSONObject(specToString(getSpec())));
            BoxSpec bundled = BoxSpec.fromJson(new JSONObject(
                    FileUtils.readString(context, BoxSpec.getBundledAssetPath())));
            if (!candidate.boxId.equals(bundled.boxId)) {
                throw new IllegalStateException("bundled_box_id_mismatch");
            }

            for (String layerType : layerTypes) {
                BoxSpec.Layer replacement = findLayer(bundled, layerType);
                if (replacement == null) {
                    throw new IllegalStateException("bundled_layer_missing: " + layerType);
                }
                int replaceIndex = -1;
                for (int i = 0; i < candidate.layers.size(); i++) {
                    if (layerType.equals(candidate.layers.get(i).type)) {
                        replaceIndex = i;
                        break;
                    }
                }
                if (replaceIndex >= 0) candidate.layers.set(replaceIndex, replacement);
                else candidate.layers.add(replacement);
            }
            return candidate;
        }
        catch (JSONException error) {
            throw new IllegalStateException("invalid_bundled_spec", error);
        }
    }

    public synchronized void activateSpecForLayerUpdate(BoxSpec candidate) {
        BoxSpec previous;
        try {
            previous = BoxSpec.fromJson(new JSONObject(specToString(getSpec())));
        }
        catch (JSONException error) {
            throw new IllegalStateException("invalid_active_spec", error);
        }

        try {
            saveSpec(candidate);
            getOrCreateContainer();
        }
        catch (RuntimeException activationFailure) {
            try {
                saveSpec(previous);
                getOrCreateContainer();
            }
            catch (RuntimeException rollbackFailure) {
                activationFailure.addSuppressed(rollbackFailure);
            }
            throw activationFailure;
        }
    }

    public synchronized BoxSpec refreshSpecFromBundledAssetForInitialInstall() {
        if (isPrefixProtected()) throw new IllegalStateException("protected_prefix");
        return refreshSpecFromBundledAsset(false);
    }

    /**
     * Refreshes immutable layer identities without authorizing a full reinstall.
     * A targeted layer installer still selects and writes only its named layer,
     * so the protected prefix remains outside the mutation boundary.
     */
    public synchronized BoxSpec refreshSpecFromBundledAssetForLayerUpdate() {
        return refreshSpecFromBundledAsset(true);
    }

    /**
     * Refreshes one immutable layer without replacing the user's active graphics,
     * launch, environment, or payload selections.
     */
    public synchronized BoxSpec refreshLayerFromBundledAssetForUpdate(String layerType) {
        if (layerType == null || layerType.trim().isEmpty()) {
            throw new IllegalArgumentException("layer_type_missing");
        }

        BoxSpec current = getSpec();
        BoxSpec bundled = BoxSpec.fromJsonString(FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
        if (!current.boxId.equals(bundled.boxId)) {
            throw new IllegalStateException("bundled_box_id_mismatch");
        }

        BoxSpec.Layer bundledLayer = null;
        for (BoxSpec.Layer layer : bundled.layers) {
            if (layerType.equals(layer.type)) {
                bundledLayer = layer;
                break;
            }
        }
        if (bundledLayer == null) {
            throw new IllegalStateException("bundled_layer_missing: " + layerType);
        }
        if ("input-bridge-stack".equals(layerType)) {
            bindInputBridgeToActiveWineRuntime(current, bundledLayer);
        }

        int replaceIndex = -1;
        for (int i = 0; i < current.layers.size(); i++) {
            if (layerType.equals(current.layers.get(i).type)) {
                replaceIndex = i;
                break;
            }
        }
        if (replaceIndex >= 0) current.layers.set(replaceIndex, bundledLayer);
        else {
            int wineIndex = -1;
            for (int i = 0; i < current.layers.size(); i++) {
                if ("wine-runtime".equals(current.layers.get(i).type)) {
                    wineIndex = i;
                    break;
                }
            }
            current.layers.add(wineIndex >= 0 ? wineIndex + 1 : current.layers.size(), bundledLayer);
        }

        saveSpec(current);
        getOrCreateContainer();
        return current;
    }

    private static void bindInputBridgeToActiveWineRuntime(
            BoxSpec activeSpec,
            BoxSpec.Layer inputBridgeLayer) {
        if (activeSpec == null || inputBridgeLayer == null) return;
        BoxSpec.Layer wineRuntime = findLayer(activeSpec, "wine-runtime");
        if (wineRuntime == null
                || wineRuntime.runtimeIdentifier == null
                || wineRuntime.runtimeIdentifier.trim().isEmpty()) {
            return;
        }
        inputBridgeLayer.runtimeIdentifier = wineRuntime.runtimeIdentifier;
    }

    private BoxSpec refreshSpecFromBundledAsset(boolean syncExistingContainer) {
        BoxSpec current = getSpec();
        BoxSpec bundled = BoxSpec.fromJsonString(FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
        if (!current.boxId.equals(bundled.boxId)) {
            throw new IllegalStateException("bundled_box_id_mismatch");
        }
        saveSpec(bundled);
        if (syncExistingContainer) getOrCreateContainer();
        return bundled;
    }

    /**
     * The FrontMage rename changed immutable asset names and the external Box
     * directory, but the Odin prefix is intentionally persistent. Migrate only
     * the one known DLLU profile in place; never run the initial installer and
     * never replace /home/xuser-box.
     */
    private void migrateLegacyBoxSpecIfNeeded() {
        if (spec == null || !LEGACY_DLLU_BOX_ID.equals(spec.boxId)) return;

        BoxSpec bundled = BoxSpec.fromJsonString(FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
        if (!FRONTMAGE_BOX_ID.equals(bundled.boxId)) return;

        BoxSpec legacy = spec;
        File legacyPayload = BoxPaths.getPayloadDir(legacy);
        File newPayload = BoxPaths.getPayloadDir(bundled);
        boolean payloadReady = migrateDirectoryByRename(legacyPayload, newPayload);
        if (!payloadReady && legacyPayload.isDirectory()) {
            String path = legacyPayload.getAbsolutePath();
            bundled.payload.source = Uri.fromFile(legacyPayload).toString();
            bundled.payload.extractDir = path;
            Log.w(TAG, "Keeping legacy payload path after rename failure: " + path);
        }

        File legacyExternalRoot = BoxPaths.getExternalRoot(legacy);
        File newExternalRoot = BoxPaths.getExternalRoot(bundled);
        boolean externalRootReady = migrateDirectoryByRename(legacyExternalRoot, newExternalRoot);

        if (legacy.launch != null && legacy.launch.defaultTargetId != null &&
                bundled.launch.findTarget(legacy.launch.defaultTargetId) != null) {
            bundled.launch.defaultTargetId = legacy.launch.defaultTargetId;
        }

        JSONObject state = getState();
        migrateInstalledLayerAliases(state, bundled);
        try {
            state.put("legacyBoxId", LEGACY_DLLU_BOX_ID);
            state.put("boxId", FRONTMAGE_BOX_ID);
            state.put("boxSpecMigratedAt", System.currentTimeMillis());
            state.put("payloadPathMigrated", payloadReady);
            state.put("externalRootMigrated", externalRootReady);
            if (!payloadReady && legacyPayload.isDirectory()) {
                state.put("legacyPayloadPath", legacyPayload.getAbsolutePath());
            }
            if (!externalRootReady && legacyExternalRoot.isDirectory()) {
                state.put("legacyExternalRoot", legacyExternalRoot.getAbsolutePath());
            }
        }
        catch (JSONException ignored) {}

        spec = bundled;
        FileUtils.writeString(BoxPaths.getSpecFile(context), specToString(bundled));
        saveState(state);
        Log.i(TAG, "Migrated Box spec " + LEGACY_DLLU_BOX_ID + " -> " + FRONTMAGE_BOX_ID +
                " payloadReady=" + payloadReady + " externalRootReady=" + externalRootReady);
    }

    private boolean migrateDirectoryByRename(File source, File target) {
        if (target.isDirectory()) return true;
        if (!source.isDirectory()) return !target.exists();
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        return source.renameTo(target) || target.isDirectory();
    }

    /**
     * Preserve verification records only when the immutable bytes are unchanged.
     * Changed Wine, FEX and DX layers deliberately remain stale until their
     * targeted installers complete.
     */
    private void migrateInstalledLayerAliases(JSONObject state, BoxSpec bundled) {
        JSONArray installed = state.optJSONArray("installedLayers");
        if (installed == null) return;
        for (int i = 0; i < installed.length(); i++) {
            JSONObject item = installed.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "");
            String checksum = item.optString("checksum", "");
            if (type.isEmpty() || checksum.isEmpty()) continue;
            for (BoxSpec.Layer layer : bundled.layers) {
                if (!type.equals(layer.type) || !checksum.equalsIgnoreCase(layer.checksum)) continue;
                try {
                    item.put("source", layer.source);
                    item.put("runtimeIdentifier", layer.runtimeIdentifier);
                    item.put("target", layer.target);
                    item.put("migratedAliasAt", System.currentTimeMillis());
                }
                catch (JSONException ignored) {}
                break;
            }
        }
    }

    public synchronized String getDebugToken() {
        ensureBoxDir();
        File tokenFile = BoxPaths.getTokenFile(context);
        if (tokenFile.isFile()) return FileUtils.readString(tokenFile).trim();

        byte[] data = new byte[16];
        new SecureRandom().nextBytes(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format(Locale.US, "%02x", b));
        String token = sb.toString();
        FileUtils.writeString(tokenFile, token);
        return token;
    }

    public synchronized JSONObject getState() {
        File file = BoxPaths.getStateFile(context);
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(FileUtils.readString(file));
        }
        catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    public synchronized void saveState(JSONObject state) {
        ensureBoxDir();
        FileUtils.writeString(BoxPaths.getStateFile(context), state.toString());
    }

    public synchronized boolean isPrefixProtected() {
        if (getState().optBoolean("prefixProtected", false)) return true;
        return BoxPaths.getExternalPrefixProtectionFile(getSpec()).isFile();
    }

    public synchronized JSONObject protectPrefix(String reason) {
        JSONObject state = getState();
        long protectedAt = state.optLong("prefixProtectedAt", 0L);
        if (protectedAt == 0L) protectedAt = System.currentTimeMillis();
        String safeReason = reason != null && !reason.trim().isEmpty() ? reason.trim() : "persistent-prefix";
        try {
            state.put("prefixProtected", true);
            state.put("prefixProtectedAt", protectedAt);
            state.put("prefixProtectionReason", safeReason);
            saveState(state);

            JSONObject marker = new JSONObject();
            marker.put("boxId", getSpec().boxId);
            marker.put("protectedAt", protectedAt);
            marker.put("reason", safeReason);
            File markerFile = BoxPaths.getExternalPrefixProtectionFile(getSpec());
            File parent = markerFile.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            if (!FileUtils.writeString(markerFile, marker.toString(2))) {
                throw new IllegalStateException("failed to persist external prefix protection marker");
            }
            return marker;
        }
        catch (Exception e) {
            throw new IllegalStateException("failed to protect prefix", e);
        }
    }

    public synchronized JSONObject getRuntimeOverlay() {
        JSONObject overlay = getState().optJSONObject("runtimeOverlay");
        return overlay != null ? overlay : new JSONObject();
    }

    public synchronized void saveRuntimeOverlay(JSONObject overlay) {
        JSONObject state = getState();
        try {
            if (overlay == null || overlay.length() == 0) state.remove("runtimeOverlay");
            else state.put("runtimeOverlay", overlay);
        }
        catch (JSONException ignored) {}
        saveState(state);
    }

    public synchronized File stageRuntimeOverlayPayload(String targetPath, String sha256) {
        if (!isValidRuntimeOverlayPath(targetPath) || sha256 == null || sha256.isEmpty()) return null;
        File source = resolveRuntimeOverlayTarget(targetPath);
        if (!source.isFile()) return null;

        File storeDir = new File(BoxPaths.getBoxDir(context), "runtime-overlay-files");
        if (!storeDir.isDirectory() && !storeDir.mkdirs()) return null;

        File staged = new File(storeDir, sha256.toLowerCase(Locale.US) + ".bin");
        if (!FileUtils.copy(source, staged)) return null;
        return staged;
    }

    public synchronized boolean applyRuntimeOverlay() {
        JSONObject overlay = getRuntimeOverlay();
        JSONArray entries = overlay.optJSONArray("entries");
        if (entries == null || entries.length() == 0) return true;

        boolean ok = true;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;

            String targetPath = entry.optString("targetPath", entry.optString("path", ""));
            String overlayFilePath = entry.optString("overlayFile", "");
            if (overlayFilePath.isEmpty()) continue;

            File source = new File(overlayFilePath);
            File target = resolveRuntimeOverlayTarget(targetPath);
            boolean copied = isValidRuntimeOverlayPath(targetPath) && source.isFile() && FileUtils.copy(source, target);
            if (!copied) {
                ok = false;
                Log.w(TAG, "Failed to apply runtime overlay target=" + targetPath);
            }
        }
        return ok;
    }

    public File resolveRuntimeOverlayTarget(String targetPath) {
        return new File(ImageFs.find(context).getRootDir(), stripLeadingSlash(targetPath));
    }

    public boolean isValidRuntimeOverlayPath(String targetPath) {
        if (targetPath == null || targetPath.isEmpty()) return false;
        if (!targetPath.startsWith("/")) return false;
        if (targetPath.contains("..")) return false;
        return targetPath.startsWith("/opt/") || targetPath.startsWith("/home/xuser-box/.wine/");
    }

    public synchronized LinkedHashMap<String, String> getRuntimeOverrides() {
        LinkedHashMap<String, String> overrides = new LinkedHashMap<>();
        File file = BoxPaths.getOverridesFile(context);
        if (!file.isFile()) return overrides;
        try {
            JSONObject json = new JSONObject(FileUtils.readString(file));
            JSONArray names = json.names();
            if (names == null) return overrides;
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                overrides.put(name, json.optString(name, ""));
            }
        }
        catch (JSONException ignored) {}
        return overrides;
    }

    public synchronized void saveRuntimeOverrides(Map<String, String> overrides) {
        ensureBoxDir();
        FileUtils.writeString(BoxPaths.getOverridesFile(context), new JSONObject(overrides).toString());
    }

    public synchronized Container getOrCreateContainer() {
        Container container = new Container(1);
        File rootDir = getContainerRootDir();
        container.setRootDir(rootDir);
        File configFile = container.getConfigFile();
	    if (configFile.isFile()) {
	        try {
            container.loadData(new JSONObject(FileUtils.readString(configFile)));
            syncContainerDrivesFromSpec(container);
            syncContainerEnvVarsFromSpec(container);
            syncContainerGraphicsFromSpec(container);
	        }
	        catch (JSONException ignored) {}
	    } else {
	        configureContainer(container);
	        container.saveData();
        }
        migrateBoxInputGestureIfNeeded(container);
        migrateBoxHudIfNeeded(container);
        migrateBoxBrandingIfNeeded(container);
        return container;
    }

    public synchronized void configureContainer(Container container) {
        BoxSpec spec = getSpec();
        container.setName(BOX_CONTAINER_NAME);
        container.setWoW64Mode(true);
        container.setStartupSelection(Container.STARTUP_SELECTION_ESSENTIAL);
        container.setShowFPS(true);
        container.setGraphicsDriver("wrapper");
        String dxvkVersion = spec.graphics.dxvkVersion != null ? spec.graphics.dxvkVersion.trim() : "";
        container.setDXWrapper(spec.graphics.vkd3dEnabled ? "dxvk+vkd3d" : "dxvk");
        if (!dxvkVersion.isEmpty()) {
            container.setDXWrapperConfig("version=" + dxvkVersion + ",framerate=0,maxDeviceMemory=0");
        }
        container.setAudioDriver(Container.DEFAULT_AUDIO_DRIVER);
        container.setScreenSize(Container.DEFAULT_SCREEN_SIZE);
        container.setWinComponents(Container.FALLBACK_WINCOMPONENTS);
        container.setDesktopTheme(container.getDesktopTheme());
        container.setFEXCorePreset(spec.env.fexPreset);
        container.setFEXCoreVersion(DefaultVersion.FEXCORE);
        String wineVersion = resolveWineVersionFromSpec(spec);
        container.setWineVersion(wineVersion);
        String driverId = resolveDriverIdFromSpec(spec);
        String graphicsConfig = Container.DEFAULT_GRAPHICS_DRIVER_CONFIG.replace("version=turnip26.0.0", "version=" + driverId);
        container.setGraphicsDriverConfig(graphicsConfig);
        container.setEnvVars(buildContainerEnvVars().toString());
        container.setDrives(buildDrivesString(spec));
        container.putExtra(Container.EXTRA_ICP_TAP_DRAG_MOUSE_GESTURE, "1");
        container.saveData();
    }

    public synchronized boolean isTouchGestureEnabled() {
        Container container = getOrCreateContainer();
        return "1".equals(container.getExtra(Container.EXTRA_ICP_TAP_DRAG_MOUSE_GESTURE, "0"));
    }

    public synchronized void setTouchGestureEnabled(boolean enabled) {
        setTouchGestureEnabled(getOrCreateContainer(), enabled);
    }

    public synchronized void setTouchGestureEnabled(Container container, boolean enabled) {
        if (container == null) return;
        container.putExtra(Container.EXTRA_ICP_TAP_DRAG_MOUSE_GESTURE, enabled ? "1" : "0");
        container.saveData();
        markBoxInputGestureMigrationComplete();
    }

    public synchronized boolean isHudEnabled() {
        return getOrCreateContainer().isShowFPS();
    }

    public synchronized void setHudEnabled(boolean enabled) {
        setHudEnabled(getOrCreateContainer(), enabled);
    }

    public synchronized void setHudEnabled(Container container, boolean enabled) {
        if (container == null) return;
        container.setShowFPS(enabled);
        container.saveData();
        markBoxHudMigrationComplete();
    }

    private void migrateBoxInputGestureIfNeeded(Container container) {
        JSONObject state = getState();
        if (state.optInt(STATE_BOX_INPUT_GESTURE_MIGRATION_VERSION, 0) >= BOX_INPUT_GESTURE_MIGRATION_VERSION) {
            return;
        }
        container.putExtra(Container.EXTRA_ICP_TAP_DRAG_MOUSE_GESTURE, "1");
        container.saveData();
        markBoxInputGestureMigrationComplete(state);
    }

    private void markBoxInputGestureMigrationComplete() {
        markBoxInputGestureMigrationComplete(getState());
    }

    private void markBoxInputGestureMigrationComplete(JSONObject state) {
        try {
            state.put(STATE_BOX_INPUT_GESTURE_MIGRATION_VERSION, BOX_INPUT_GESTURE_MIGRATION_VERSION);
            saveState(state);
        }
        catch (JSONException e) {
            Log.w(TAG, "Failed to persist Box input gesture migration state", e);
        }
    }

    private void migrateBoxHudIfNeeded(Container container) {
        JSONObject state = getState();
        if (state.optInt(STATE_BOX_HUD_MIGRATION_VERSION, 0) >= BOX_HUD_MIGRATION_VERSION) {
            return;
        }
        container.setShowFPS(true);
        container.saveData();
        markBoxHudMigrationComplete(state);
    }

    private void markBoxHudMigrationComplete() {
        markBoxHudMigrationComplete(getState());
    }

    private void markBoxHudMigrationComplete(JSONObject state) {
        try {
            state.put(STATE_BOX_HUD_MIGRATION_VERSION, BOX_HUD_MIGRATION_VERSION);
            saveState(state);
        }
        catch (JSONException e) {
            Log.w(TAG, "Failed to persist Box HUD migration state", e);
        }
    }

    private void migrateBoxBrandingIfNeeded(Container container) {
        JSONObject state = getState();
        if (state.optInt(STATE_BOX_BRANDING_MIGRATION_VERSION, 0) >= BOX_BRANDING_MIGRATION_VERSION) {
            return;
        }

        BoxBrandingMigration.Result result = BoxBrandingMigration.migrate(
                container.getName(),
                container.getDesktopTheme());
        if (result.changed) {
            container.setName(result.name);
            container.setDesktopTheme(result.desktopTheme);
            container.saveData();
            Log.i(TAG, "Migrated legacy default container branding to WoW Box");
        }
        try {
            state.put(STATE_BOX_BRANDING_MIGRATION_VERSION, BOX_BRANDING_MIGRATION_VERSION);
            saveState(state);
        }
        catch (JSONException e) {
            Log.w(TAG, "Failed to persist Box branding migration state", e);
        }
    }

    public synchronized EnvSnapshot buildEnvSnapshot() {
        BoxSpec spec = getSpec();
        EnvSnapshot snapshot = new EnvSnapshot();
        snapshot.addAll("base", spec.env.base);
        snapshot.add("preset", "BOX_FEX_PRESET", spec.env.fexPreset);
        snapshot.addAll("overrides", spec.env.overrides);
        snapshot.addAll("runtime", getRuntimeOverrides());
        return snapshot;
    }

    public synchronized EnvVars buildContainerEnvVars() {
        EnvVars envVars = new EnvVars();
        EnvSnapshot snapshot = buildEnvSnapshot();
        for (Map.Entry<String, String> entry : snapshot.values.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) envVars.put(entry.getKey(), entry.getValue());
        }
        // WINEDEBUG is owned by WineDebugConfig/Settings. Do not persist a
        // second copy in the Box container environment.
        envVars.remove("WINEDEBUG");
        if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");
        return envVars;
    }

    public synchronized ArrayList<MountPoint> getMountPoints() {
        BoxSpec spec = getSpec();
        ArrayList<MountPoint> mounts = new ArrayList<>();
        String payloadPath = resolveSourceToHostPath(spec.payload.source);
        File payloadDir = BoxPaths.getPayloadDir(spec);
        if ("directory".equalsIgnoreCase(spec.payload.kind)) {
            payloadPath = payloadDir.getAbsolutePath();
        } else if ("http".equals(uriScheme(spec.payload.source)) || "https".equals(uriScheme(spec.payload.source))) {
            payloadPath = payloadDir.getAbsolutePath();
        } else if ("archive".equals(spec.payload.kind)) {
            payloadPath = payloadDir.getAbsolutePath();
        }
        mounts.add(new MountPoint(spec.payload.mountDrive, payloadPath));
        for (BoxSpec.Mount mount : spec.extraMounts) {
            mounts.add(new MountPoint(mount.mountDrive, resolveSourceToHostPath(mount.source)));
        }
        return mounts;
    }

    public synchronized boolean hasManagedBaseImageFs() {
        for (BoxSpec.Layer layer : getSpec().layers) {
            if ("base-imagefs".equals(layer.type)) return true;
        }
        return false;
    }

    public synchronized ArrayList<BoxExecutable> scanExecutables() {
        ArrayList<BoxExecutable> executables = new ArrayList<>();
        for (MountPoint mountPoint : getMountPoints()) {
            File root = new File(mountPoint.hostPath);
            scanExecutablesRecursive(root, root, mountPoint.drive, executables, 0);
        }
        Collections.sort(executables, Comparator
                .comparingInt((BoxExecutable a) -> "WowClassic-arm64.exe".equalsIgnoreCase(a.name) ? 0 : 1)
                .thenComparing(a -> a.relativePath.toLowerCase(Locale.US)));
        return executables;
    }

    public synchronized ArrayList<BoxSpec.Target> getVisibleLaunchTargets() {
        ArrayList<BoxSpec.Target> targets = new ArrayList<>();
        boolean protectedPrefix = isPrefixProtected();
        for (BoxSpec.Target target : getSpec().launch.targets) {
            if (target.hidden) continue;
            if (protectedPrefix && "installer".equalsIgnoreCase(target.kind)) continue;
            if (!target.requiresTargetId.isEmpty() && !launchTargetExistsById(target.requiresTargetId)) continue;
            if (!target.hiddenWhenTargetIdExists.isEmpty() &&
                    launchTargetExistsById(target.hiddenWhenTargetIdExists)) continue;
            if (target.requiresExists && !launchTargetExists(target)) continue;
            targets.add(target);
        }
        final String defaultId = getSpec().launch.defaultTargetId;
        Collections.sort(targets, Comparator.comparingInt(target -> defaultId.equals(target.id) ? 0 : 1));
        return targets;
    }

    public synchronized BoxSpec.Target requireLaunchTarget(String targetId) {
        requireWowInputBridgeMigrationComplete();
        BoxSpec.Target target = getSpec().launch.findTarget(targetId);
        if (target == null) throw new IllegalArgumentException("unknown launch target: " + targetId);
        validateLaunchTarget(target);
        if (isPrefixProtected() && "installer".equalsIgnoreCase(target.kind)) {
            throw new IllegalStateException("protected_prefix: installer targets are disabled");
        }
        if (!target.requiresTargetId.isEmpty() && !launchTargetExistsById(target.requiresTargetId)) {
            throw new IllegalStateException("required launch target does not exist: " + target.requiresTargetId);
        }
        if (target.requiresExists && !launchTargetExists(target)) {
            throw new IllegalStateException("launch target does not exist: " + target.id);
        }
        return target;
    }

    public synchronized boolean launchTargetExistsById(String targetId) {
        BoxSpec.Target target = getSpec().launch.findTarget(targetId);
        return target != null && launchTargetExists(target);
    }

    public synchronized boolean launchTargetExists(BoxSpec.Target target) {
        File file = resolveGuestPath(target != null ? target.guestPath : "");
        return file != null && file.isFile();
    }

    public synchronized JSONObject setDefaultLaunchTarget(String targetId) throws JSONException {
        BoxSpec.Target target = requireLaunchTarget(targetId);
        BoxSpec current = getSpec();
        current.launch.defaultTargetId = target.id;
        saveSpec(current);
        JSONObject state = getState();
        state.put("defaultLaunchTargetId", target.id);
        state.put("defaultLaunchTargetChangedAt", System.currentTimeMillis());
        saveState(state);
        JSONObject result = target.toJson();
        result.put("exists", launchTargetExists(target));
        result.put("prefixProtected", isPrefixProtected());
        return result;
    }

    public synchronized JSONObject selectDxvkCandidate(String version) throws JSONException {
        BoxSpec current = getSpec();
        BoxSpec.DxvkCandidate candidate = current.graphics.findDxvkCandidate(version);
        if (candidate == null) {
            BoxSpec bundled = BoxSpec.fromJsonString(FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
            if (!current.boxId.equals(bundled.boxId)) {
                throw new IllegalStateException("bundled_box_id_mismatch");
            }
            candidate = bundled.graphics.findDxvkCandidate(version);
            if (candidate != null) {
                current.graphics.dxvkCandidates.clear();
                current.graphics.dxvkCandidates.addAll(bundled.graphics.dxvkCandidates);
            }
        }
        if (candidate == null) throw new IllegalArgumentException("unknown DXVK candidate: " + version);
        if (candidate.source.isEmpty() || candidate.checksum.isEmpty()) {
            throw new IllegalStateException("incomplete DXVK candidate: " + version);
        }

        BoxSpec.Layer dxvkLayer = null;
        for (BoxSpec.Layer layer : current.layers) {
            if ("dx-wrapper-stack".equals(layer.type)) {
                dxvkLayer = layer;
                break;
            }
        }
        if (dxvkLayer == null) throw new IllegalStateException("dx-wrapper-stack layer is missing");

        current.graphics.dxvkVersion = candidate.version;
        dxvkLayer.source = candidate.source;
        dxvkLayer.checksum = candidate.checksum;
        saveSpec(current);
        getOrCreateContainer();

        JSONObject state = getState();
        state.put("selectedDxvkVersion", candidate.version);
        state.put("selectedDxvkSource", candidate.source);
        state.put("selectedDxvkAt", System.currentTimeMillis());
        saveState(state);

        JSONObject result = candidate.toJson();
        result.put("layer", dxvkLayer.toJson());
        return result;
    }

    public synchronized JSONObject selectTurnipCandidate(String version) throws JSONException {
        BoxSpec current = getSpec();
        BoxSpec.TurnipCandidate candidate = current.graphics.findTurnipCandidate(version);
        if (candidate == null) {
            BoxSpec bundled = BoxSpec.fromJsonString(FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
            if (!current.boxId.equals(bundled.boxId)) {
                throw new IllegalStateException("bundled_box_id_mismatch");
            }
            candidate = bundled.graphics.findTurnipCandidate(version);
            if (candidate != null) {
                current.graphics.turnipCandidates.clear();
                current.graphics.turnipCandidates.addAll(bundled.graphics.turnipCandidates);
            }
        }
        if (candidate == null) throw new IllegalArgumentException("unknown Turnip candidate: " + version);
        if (candidate.source.isEmpty() || candidate.checksum.isEmpty() || candidate.driverId.isEmpty()) {
            throw new IllegalStateException("incomplete Turnip candidate: " + version);
        }

        BoxSpec.Layer gpuLayer = null;
        for (BoxSpec.Layer layer : current.layers) {
            if ("gpu-driver-stack".equals(layer.type)) {
                gpuLayer = layer;
                break;
            }
        }
        if (gpuLayer == null) throw new IllegalStateException("gpu-driver-stack layer is missing");

        current.graphics.turnipVersion = candidate.version;
        gpuLayer.source = candidate.source;
        gpuLayer.checksum = candidate.checksum;
        gpuLayer.target = candidate.driverId;
        saveSpec(current);
        getOrCreateContainer();

        JSONObject state = getState();
        state.put("selectedTurnipVersion", candidate.version);
        state.put("selectedTurnipSource", candidate.source);
        state.put("selectedTurnipAt", System.currentTimeMillis());
        saveState(state);

        JSONObject result = candidate.toJson();
        result.put("layer", gpuLayer.toJson());
        return result;
    }

    public synchronized void recordLastLaunch(String path, boolean desktop) {
        JSONObject state = getState();
        try {
            state.put("lastLaunchPath", path != null ? path : "");
            state.put("lastLaunchDesktop", desktop);
            state.put("lastLaunchAt", System.currentTimeMillis());
        }
        catch (JSONException ignored) {}
        saveState(state);
    }

    public synchronized JSONObject buildDebugSnapshot() {
        JSONObject json = new JSONObject();
        try {
            json.put("spec", getSpec().toJson());
            json.put("state", getState());
            json.put("env", buildEnvSnapshot().toJson());
            json.put("mounts", buildMountsJson());
            json.put("layers", buildInstalledLayersJson());
            json.put("runtimeOverlay", getRuntimeOverlay());
            json.put("rootRuntimeOverlays", buildRootRuntimeOverlaysStatus());
            json.put("currentSession", getSessionManager().getCurrentSessionMeta());
        }
        catch (JSONException ignored) {}
        return json;
    }

    public synchronized JSONObject buildRootRuntimeOverlaysStatus() {
        JSONObject result = new JSONObject();
        JSONArray overlays = new JSONArray();
        boolean allOk = true;
        File root = BoxPaths.getRuntimeOverlaysDir(context);
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                File manifestFile = new File(dir, "applied.json");
                if (!manifestFile.isFile()) continue;
                JSONObject overlay = new JSONObject();
                boolean overlayOk = true;
                try {
                    JSONObject manifest = new JSONObject(FileUtils.readString(manifestFile));
                    overlay.put("overlayId", manifest.optString("overlayId", dir.getName()));
                    overlay.put("runtimeId", manifest.optString("runtimeId", ""));
                    JSONArray entries = manifest.optJSONArray("entries");
                    JSONArray verifiedEntries = new JSONArray();
                    if (entries == null || entries.length() == 0) overlayOk = false;
                    else {
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject entry = entries.optJSONObject(i);
                            if (entry == null) {
                                overlayOk = false;
                                continue;
                            }
                            String targetPath = entry.optString("targetPath", "");
                            String expected = entry.optString("sha256", "");
                            File target = resolveRuntimeOverlayTarget(targetPath);
                            String actual = target.isFile() ? sha256File(target) : "";
                            boolean ok = isValidRootOverlayTarget(targetPath, manifest.optString("runtimeId", "")) &&
                                    !expected.isEmpty() && expected.equalsIgnoreCase(actual);
                            JSONObject verified = new JSONObject(entry.toString());
                            verified.put("actualSha256", actual);
                            verified.put("ok", ok);
                            verifiedEntries.put(verified);
                            overlayOk &= ok;
                        }
                    }
                    overlay.put("entries", verifiedEntries);
                }
                catch (Exception e) {
                    overlayOk = false;
                    try { overlay.put("error", e.toString()); }
                    catch (JSONException ignored) {}
                }
                try { overlay.put("ok", overlayOk); }
                catch (JSONException ignored) {}
                overlays.put(overlay);
                allOk &= overlayOk;
            }
        }
        try {
            result.put("ok", allOk);
            result.put("overlays", overlays);
        }
        catch (JSONException ignored) {}
        return result;
    }

    private boolean isValidRootOverlayTarget(String targetPath, String runtimeId) {
        if (targetPath == null || targetPath.contains("..") || !targetPath.startsWith("/")) return false;
        if ("/libwow64fex.dll".equals(targetPath) || "/libarm64ecfex.dll".equals(targetPath)) return true;
        if (runtimeId != null && runtimeId.matches("[A-Za-z0-9][A-Za-z0-9._-]+") &&
                targetPath.startsWith("/opt/" + runtimeId + "/")) return true;
        return targetPath.startsWith("/home/xuser-box/.wine/drive_c/windows/system32/") ||
                targetPath.startsWith("/home/xuser-box/.wine/drive_c/windows/syswow64/");
    }

    private static String sha256File(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest()) out.append(String.format(Locale.US, "%02x", value & 0xff));
            return out.toString();
        }
        catch (Exception ignored) {
            return "";
        }
    }

    public synchronized JSONArray buildMountsJson() {
        JSONArray array = new JSONArray();
        for (MountPoint mountPoint : getMountPoints()) {
            JSONObject json = new JSONObject();
            try {
                json.put("drive", mountPoint.drive);
                json.put("hostPath", mountPoint.hostPath);
            }
            catch (JSONException ignored) {}
            array.put(json);
        }
        return array;
    }

    public synchronized JSONArray buildInstalledLayersJson() {
        JSONArray array = new JSONArray();
        JSONObject state = getState();
        JSONArray installed = state.optJSONArray("installedLayers");
        if (installed != null) return installed;
        for (BoxSpec.Layer layer : getSpec().layers) {
            JSONObject json = new JSONObject();
            try {
                json.put("type", layer.type);
                json.put("source", layer.source);
                json.put("checksum", layer.checksum);
                json.put("installedAt", JSONObject.NULL);
            }
            catch (JSONException ignored) {}
            array.put(json);
        }
        return array;
    }

    public void launchDesktop() {
        launchDesktop(BoxDebugConfig.none());
    }

    public String launchDesktop(BoxDebugConfig debugConfig) {
        requireWowInputBridgeMigrationComplete();
        getOrCreateContainer();
        recordLastLaunch("", true);
        String sessionId = getSessionManager().createSession("desktop", "", debugConfig);
        getSessionManager().markLaunchRequested(sessionId);
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("box_launch_mode", "desktop");
        intent.putExtra("box_session_id", sessionId);
        if (debugConfig != null) debugConfig.applyToIntent(intent);
        context.startActivity(intent);
        return sessionId;
    }

    public void launchExecutable(String absoluteHostPath) {
        launchExecutable(absoluteHostPath, BoxDebugConfig.none());
    }

    public String launchExecutable(String absoluteHostPath, BoxDebugConfig debugConfig) {
        requireWowInputBridgeMigrationComplete();
        getOrCreateContainer();
        recordLastLaunch(absoluteHostPath, false);
        String sessionId = getSessionManager().createSession("executable", absoluteHostPath, debugConfig);
        getSessionManager().markLaunchRequested(sessionId);
        Log.i(TAG, "launchExecutable sessionId=" + sessionId + " path=" + absoluteHostPath +
                " debugProfile=" + (debugConfig != null ? debugConfig.profile : ""));
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("box_launch_mode", "executable");
        intent.putExtra("box_executable_path", absoluteHostPath);
        intent.putExtra("box_session_id", sessionId);
        if (debugConfig != null) debugConfig.applyToIntent(intent);
        context.startActivity(intent);
        return sessionId;
    }

    public String launchTarget(String targetId, BoxDebugConfig debugConfig) {
        BoxSpec.Target target = requireLaunchTarget(targetId);
        BoxSpec bundledSpec = BoxSpec.fromJsonString(
                FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
        int controlsProfileId = resolveControlsProfileId(
                target,
                bundledSpec.launch.findTarget(target.id));
        getOrCreateContainer();
        recordLastLaunch(target.guestPath, false);
        String sessionId = getSessionManager().createSession("target", target.guestPath, debugConfig);
        getSessionManager().markLaunchRequested(sessionId);
        Log.i(TAG, "launchTarget sessionId=" + sessionId + " id=" + target.id + " guestPath=" + target.guestPath);
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("box_launch_mode", "target");
        intent.putExtra("box_launch_target_id", target.id);
        intent.putExtra("box_executable_guest_path", target.guestPath);
        intent.putExtra("box_working_directory", target.workingDirectory);
        intent.putStringArrayListExtra("box_executable_args", new ArrayList<>(target.args));
        intent.putExtra("box_controls_profile_id", controlsProfileId);
        intent.putExtra("box_session_id", sessionId);
        if (debugConfig != null) debugConfig.applyToIntent(intent);
        context.startActivity(intent);
        return sessionId;
    }

    static int resolveControlsProfileId(BoxSpec.Target activeTarget, BoxSpec.Target bundledTarget) {
        if (activeTarget == null) return 0;
        if (activeTarget.controlsProfileId > 0) return activeTarget.controlsProfileId;
        if (bundledTarget == null || bundledTarget.controlsProfileId <= 0) return 0;
        if (!activeTarget.id.equals(bundledTarget.id) ||
                !activeTarget.guestPath.equalsIgnoreCase(bundledTarget.guestPath)) {
            return 0;
        }
        return bundledTarget.controlsProfileId;
    }

    public boolean isInstalled() {
        JSONObject state = getState();
        return state.optBoolean("installed", false) && getContainerRootDir().isDirectory();
    }

    public File getContainerRootDir() {
        return new File(ImageFs.find(context).getRootDir(), "home/xuser-box");
    }

    public File getContainerSymlink() {
        return new File(ImageFs.find(context).getRootDir(), ImageFs.HOME_PATH);
    }

    public synchronized File resolveGuestPath(String guestPath) {
        if (!isValidGuestPath(guestPath)) return null;
        return resolveGuestLocation(guestPath);
    }

    public synchronized File resolveGuestDirectory(String guestPath) {
        if (!isValidGuestDirectory(guestPath)) return null;
        return resolveGuestLocation(guestPath);
    }

    private File resolveGuestLocation(String guestPath) {
        char drive = Character.toUpperCase(guestPath.charAt(0));
        String relative = guestPath.substring(3).replace('\\', File.separatorChar).replace('/', File.separatorChar);
        File root = null;
        if (drive == 'C') root = new File(getContainerRootDir(), ".wine/drive_c");
        else {
            for (MountPoint mountPoint : getMountPoints()) {
                if (mountPoint.drive != null && !mountPoint.drive.isEmpty() &&
                        Character.toUpperCase(mountPoint.drive.charAt(0)) == drive) {
                    root = new File(mountPoint.hostPath);
                    break;
                }
            }
        }
        if (root == null) return null;
        try {
            File canonicalRoot = root.getCanonicalFile();
            File candidate = new File(canonicalRoot, relative).getCanonicalFile();
            String rootPath = canonicalRoot.getPath();
            if (!candidate.getPath().equals(rootPath) && !candidate.getPath().startsWith(rootPath + File.separator)) return null;
            return candidate;
        }
        catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isValidGuestPath(String path) {
        if (path == null || path.length() < 4 || !Character.isLetter(path.charAt(0)) || path.charAt(1) != ':' ||
                (path.charAt(2) != '\\' && path.charAt(2) != '/')) return false;
        if (path.length() > 1024 || path.indexOf('"') != -1) return false;
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) return false;
        String normalized = path.substring(3).replace('/', '\\');
        for (String component : normalized.split("\\\\")) if ("..".equals(component)) return false;
        return path.toLowerCase(Locale.US).endsWith(".exe");
    }

    public static void validateLaunchTarget(BoxSpec.Target target) {
        if (target.id == null || !target.id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("invalid launch target id");
        }
        if (!"executable".equalsIgnoreCase(target.kind) && !"installer".equalsIgnoreCase(target.kind)) {
            throw new IllegalArgumentException("invalid launch target kind for " + target.id);
        }
        if (!isValidGuestPath(target.guestPath)) throw new IllegalArgumentException("invalid guest path for " + target.id);
        if (target.workingDirectory != null && !target.workingDirectory.isEmpty() && !isValidGuestDirectory(target.workingDirectory)) {
            throw new IllegalArgumentException("invalid working directory for " + target.id);
        }
        if (target.workingDirectory != null && !target.workingDirectory.isEmpty() &&
                Character.toUpperCase(target.workingDirectory.charAt(0)) != Character.toUpperCase(target.guestPath.charAt(0))) {
            throw new IllegalArgumentException("working directory drive does not match executable for " + target.id);
        }
        if (target.args.size() > 128) throw new IllegalArgumentException("too many arguments for " + target.id);
        if (target.controlsProfileId < 0) throw new IllegalArgumentException("invalid controls profile for " + target.id);
        for (String arg : target.args) {
            if (arg == null) throw new IllegalArgumentException("null argument for " + target.id);
            if (arg.length() > 4096 || arg.indexOf('"') != -1) {
                throw new IllegalArgumentException("unsafe argument for " + target.id);
            }
            for (int i = 0; i < arg.length(); i++) {
                if (Character.isISOControl(arg.charAt(i))) throw new IllegalArgumentException("control character in argument for " + target.id);
            }
        }
    }

    static boolean isValidGuestDirectory(String path) {
        if (path == null || path.length() < 3 || !Character.isLetter(path.charAt(0)) || path.charAt(1) != ':' ||
                (path.charAt(2) != '\\' && path.charAt(2) != '/')) return false;
        if (path.length() > 1024 || path.indexOf('"') != -1) return false;
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) return false;
        for (String component : path.substring(3).replace('/', '\\').split("\\\\")) if ("..".equals(component)) return false;
        return true;
    }

    public String resolveSourceToHostPath(String source) {
        if (source == null || source.isEmpty()) return "";
        Uri uri = Uri.parse(source);
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) return source;
        if ("file".equalsIgnoreCase(scheme)) return uri.getPath();
        return source;
    }

    private String stripLeadingSlash(String path) {
        while (path != null && path.startsWith("/")) path = path.substring(1);
        return path != null ? path : "";
    }

    public static String hostPathToGuestWindowsPath(Container container, String hostPath) {
        if (hostPath == null || hostPath.isEmpty()) return "";
        String normalizedHost = new File(hostPath).getAbsolutePath();
        for (String[] drive : container.drivesIterator()) {
            File driveRoot = new File(drive[1]).getAbsoluteFile();
            String rootPath = driveRoot.getAbsolutePath();
            if (normalizedHost.equals(rootPath) || normalizedHost.startsWith(rootPath + "/")) {
                String relative = normalizedHost.substring(rootPath.length()).replace('/', '\\');
                if (relative.startsWith("\\")) {
                    return drive[0] + ":" + relative;
                }
                return drive[0] + ":\\" + relative;
            }
        }
        return hostPath.replace('/', '\\');
    }

    private static String uriScheme(String source) {
        if (source == null) return "";
        String scheme = Uri.parse(source).getScheme();
        return scheme != null ? scheme.toLowerCase(Locale.US) : "";
    }

    private static String specToString(BoxSpec spec) {
        try {
            return spec.toJson().toString(2);
        }
        catch (JSONException ignored) {
            return "{}";
        }
    }

    private void scanExecutablesRecursive(File root, File current, String drive, ArrayList<BoxExecutable> out, int depth) {
        if (current == null || !current.exists() || depth > 6) return;
        if (current.isFile()) {
            String name = current.getName();
            if (name.toLowerCase(Locale.US).endsWith(".exe")) {
                String relative = root.toPath().relativize(current.toPath()).toString().replace(File.separatorChar, '/');
                out.add(new BoxExecutable(name, current.getAbsolutePath(), relative, drive, current.lastModified()));
            }
            return;
        }

        File[] files = current.listFiles();
        if (files == null) return;
        for (File file : files) scanExecutablesRecursive(root, file, drive, out, depth + 1);
    }

    private void ensureBoxDir() {
        File boxDir = BoxPaths.getBoxDir(context);
        if (!boxDir.isDirectory()) boxDir.mkdirs();
    }

    private void recoverSpecTransactionIfNeeded() {
        File file = BoxPaths.getSpecFile(context);
        File pending = new File(file.getAbsolutePath() + ".layer-update-new");
        File backup = new File(file.getAbsolutePath() + ".layer-update-backup");
        if (FileUtils.isSymlink(file) ||
                FileUtils.isSymlink(pending) ||
                FileUtils.isSymlink(backup)) {
            throw new IllegalStateException("Unsafe Box spec transaction path");
        }

        if (backup.exists()) {
            boolean activeValid = file.isFile() && isValidSpecFile(file);
            if (activeValid) {
                if (!backup.delete()) {
                    throw new IllegalStateException(
                            "Unable to finalize Box spec transaction");
                }
            }
            else {
                if (file.exists() && !file.delete()) {
                    throw new IllegalStateException(
                            "Unable to remove incomplete Box spec");
                }
                if (!backup.renameTo(file)) {
                    throw new IllegalStateException(
                            "Unable to restore Box spec transaction");
                }
            }
        }
        if (pending.exists() && !pending.delete()) {
            throw new IllegalStateException("Unable to clean pending Box spec");
        }
    }

    private static boolean isValidSpecFile(File file) {
        try {
            BoxSpec parsed = BoxSpec.fromJson(new JSONObject(FileUtils.readString(file)));
            return parsed.boxId != null && !parsed.boxId.isEmpty() && !parsed.layers.isEmpty();
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private static void writeSpecFile(File file, String value) throws java.io.IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    private void persistSpecIfMissing() {
        File specFile = BoxPaths.getSpecFile(context);
        if (!specFile.isFile()) FileUtils.writeString(specFile, specToString(spec));
    }

    private String buildDrivesString(BoxSpec spec) {
        StringBuilder sb = new StringBuilder();
        for (MountPoint mountPoint : getMountPoints()) {
            if (mountPoint.hostPath == null || mountPoint.hostPath.isEmpty()) continue;
            sb.append(mountPoint.drive).append(":").append(mountPoint.hostPath);
        }
        return sb.toString();
    }

	private void syncContainerDrivesFromSpec(Container container) {
	    if (container == null) return;
	    String expectedDrives = buildDrivesString(getSpec());
	    String currentDrives = container.getDrives();
	    if (expectedDrives.equals(currentDrives)) return;
        container.setDrives(expectedDrives);
	    container.saveData();
	    Log.i(TAG, "syncContainerDrivesFromSpec drives=" + expectedDrives);
	}

	private void syncContainerEnvVarsFromSpec(Container container) {
	    if (container == null) return;
	    String expectedEnvVars = buildContainerEnvVars().toString();
	    String currentEnvVars = container.getEnvVars();
	    if (!expectedEnvVars.equals(currentEnvVars)) {
	        container.setEnvVars(expectedEnvVars);
	        container.saveData();
	        Log.i(TAG, "syncContainerEnvVarsFromSpec envVars updated");
	    }
	    String expectedWineVersion = resolveWineVersionFromSpec(getSpec());
	    if (!expectedWineVersion.equals(container.getWineVersion())) {
	        container.setWineVersion(expectedWineVersion);
	        container.saveData();
	        Log.i(TAG, "syncContainerEnvVarsFromSpec wineVersion updated to " + expectedWineVersion);
	    }
	}

    private void syncContainerGraphicsFromSpec(Container container) {
        if (container == null) return;
        BoxSpec spec = getSpec();
        BoxSpec.Graphics graphics = spec.graphics;
        String expectedDriver = "wrapper";
        String expectedDriverId = resolveDriverIdFromSpec(spec);
        String expectedDriverConfig = replaceGraphicsDriverVersion(container.getGraphicsDriverConfig(), expectedDriverId);
        String expectedWrapper = graphics.vkd3dEnabled ? "dxvk+vkd3d" : "dxvk";
        String expectedConfig = graphics.dxvkVersion == null || graphics.dxvkVersion.trim().isEmpty()
                ? container.getDXWrapperConfig()
                : "version=" + graphics.dxvkVersion.trim() + ",framerate=0,maxDeviceMemory=0";
        boolean changed = false;
        if (!expectedDriver.equals(container.getGraphicsDriver())) {
            container.setGraphicsDriver(expectedDriver);
            changed = true;
        }
        if (!expectedDriverConfig.equals(container.getGraphicsDriverConfig())) {
            container.setGraphicsDriverConfig(expectedDriverConfig);
            changed = true;
        }
        if (!expectedWrapper.equals(container.getDXWrapper())) {
            container.setDXWrapper(expectedWrapper);
            changed = true;
        }
        if (!expectedConfig.equals(container.getDXWrapperConfig())) {
            container.setDXWrapperConfig(expectedConfig);
            changed = true;
        }
        if (changed) container.saveData();
    }

    private static String replaceGraphicsDriverVersion(String config, String driverId) {
        String current = config == null || config.trim().isEmpty()
                ? Container.DEFAULT_GRAPHICS_DRIVER_CONFIG
                : config;
        String[] entries = current.split(";");
        StringBuilder result = new StringBuilder();
        boolean replaced = false;
        for (String entry : entries) {
            if (entry.isEmpty()) continue;
            if (result.length() > 0) result.append(';');
            if (entry.startsWith("version=")) {
                result.append("version=").append(driverId);
                replaced = true;
            }
            else result.append(entry);
        }
        if (!replaced) {
            if (result.length() > 0) result.append(';');
            result.append("version=").append(driverId);
        }
        return result.toString();
    }

    static String resolveRuntimeIdentifier(BoxSpec.Layer layer) {
        if (layer == null) return "proton-" + DefaultVersion.ARM64EC_PROTON + "-arm64ec";
        if (layer.runtimeIdentifier != null && !layer.runtimeIdentifier.isEmpty()) return layer.runtimeIdentifier;

        String source = layer.source != null ? layer.source : "";
        if (source.startsWith("asset://")) source = source.substring("asset://".length());
        String name = FileUtils.getName(source);
        if (name.endsWith(".tar.xz")) name = name.substring(0, name.length() - ".tar.xz".length());
        else if (name.endsWith(".txz")) name = name.substring(0, name.length() - ".txz".length());
        else if (name.endsWith(".tzst")) name = name.substring(0, name.length() - ".tzst".length());
        else if (name.endsWith(".wcp")) name = name.substring(0, name.length() - ".wcp".length());

        String[] arches = {"arm64ec", "x86_64", "x86"};
        for (String arch : arches) {
            String marker = "-" + arch;
            int index = name.indexOf(marker);
            if (index == -1) continue;
            int end = index + marker.length();
            return name.substring(0, end);
        }
        return "proton-" + DefaultVersion.ARM64EC_PROTON + "-arm64ec";
    }

    public String getWineRuntimeIdentifier() {
        return resolveWineVersionFromSpec(getSpec());
    }

    private String resolveWineVersionFromSpec(BoxSpec spec) {
        for (BoxSpec.Layer layer : spec.layers) {
            if (!"wine-runtime".equals(layer.type)) continue;
            return resolveRuntimeIdentifier(layer);
        }
        return "proton-" + DefaultVersion.ARM64EC_PROTON + "-arm64ec";
    }

    public String getGpuDriverId() {
        return resolveDriverIdFromSpec(getSpec());
    }

    private String resolveDriverIdFromSpec(BoxSpec spec) {
        for (BoxSpec.Layer layer : spec.layers) {
            if ("gpu-driver-stack".equals(layer.type)) {
                String name = layer.source;
                if (name.startsWith("asset://graphics_driver/adrenotools-")) {
                    name = name.substring("asset://graphics_driver/adrenotools-".length());
                    return FileUtils.getBasename(name);
                }
                if (name.startsWith("asset://graphics_driver/")) {
                    name = name.substring("asset://graphics_driver/".length());
                    return FileUtils.getBasename(name);
                }
            }
        }
        return "turnip" + DefaultVersion.ADRENOTOOLS_TURNIP;
    }

    public static class MountPoint {
        public final String drive;
        public final String hostPath;

        public MountPoint(String drive, String hostPath) {
            this.drive = drive;
            this.hostPath = hostPath;
        }
    }

    public static class EnvSnapshot {
        public final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        public final LinkedHashMap<String, String> sources = new LinkedHashMap<>();

        public void add(String source, String key, String value) {
            if (key == null || key.isEmpty()) return;
            values.put(key, value != null ? value : "");
            sources.put(key, source);
        }

        public void addAll(String source, Map<String, String> data) {
            for (Map.Entry<String, String> entry : data.entrySet()) add(source, entry.getKey(), entry.getValue());
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("value", entry.getValue());
                item.put("source", sources.get(entry.getKey()));
                json.put(entry.getKey(), item);
            }
            return json;
        }
    }
}
