package com.winlator.box;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.contents.AdrenotoolsManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineBuiltinRestore;
import com.winlator.core.WineSessionProcessController;
import com.winlator.core.WineUtils;
import com.winlator.core.WineRegistryEditor;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.ImageFsInstaller;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;

public class BoxInstaller {
    private static final String TAG = "BoxInstaller";
    private static final String PUBLIC_WINEBOOT_OPTIONAL_MANIFEST =
            "asset://wine_prefix_delta/wowbox-v010-to-mainline.json";
    private static final String PUBLIC_WINEBOOT_OPTIONAL_MANIFEST_SHA256 =
            "01dbef7048c506116d4b4232063706f99dc139f99b3c6b967e92baa11c443fa6";
    private static final String RELEASE_STAGE_PREFIX = ".release-upgrade-stage-";
    private static final String RELEASE_STAGE_OWNER = "wowbox-v020-public-release-stage-v1\n";
    private final Context context;
    private final BoxRuntime runtime;

    public BoxInstaller(Context context) {
        this.context = context.getApplicationContext();
        this.runtime = BoxRuntime.get(context);
    }

    /** Report a bundled release change without modifying user data. */
    public boolean needsBundledReleaseUpgrade() {
        if (!runtime.isInstalled()) return false;
        BoxSpec active = runtime.getSpec();
        BoxSpec bundled = BoxSpec.fromJsonString(FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
        for (String type : new String[]{"wine-runtime", "prefix-template", "cpu-emu-stack"}) {
            BoxSpec.Layer current = findLayer(active, type);
            BoxSpec.Layer desired = findLayer(bundled, type);
            if (current == null || desired == null || !sameLayerExactly(current, desired)) return true;
        }
        return false;
    }

    /** Perform only the verified public v0.1 -> bundled v0.2 transition. */
    public void upgradeBundledRelease(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            File stage = null;
            try {
                runtime.recoverPendingReleaseBeforeLaunch();
                requireIdleWineUpdate();
                if (!runtime.isInstalled() || runtime.isPrefixProtected()) {
                    throw new IllegalStateException("release_upgrade_requires_unprotected_installed_prefix");
                }
                if (runtime.getRuntimeOverlay().length() != 0) {
                    throw new IllegalStateException("release_upgrade_runtime_overlay_active");
                }
                File appRoot = context.getFilesDir().getCanonicalFile();
                File boxDir = new File(appRoot, "box");
                File specFile = new File(boxDir, "box-spec.json");
                File stateFile = new File(boxDir, "box-state.json");
                if (!specFile.isFile() || !stateFile.isFile() || FileUtils.isSymlink(specFile) || FileUtils.isSymlink(stateFile)) {
                    throw new IllegalStateException("release_upgrade_metadata_missing_or_unsafe");
                }
                String originalSpec = AtomicFileSupport.readUtf8(specFile);
                String originalState = AtomicFileSupport.readUtf8(stateFile);
                String originalSpecHash = releaseStringSha256(originalSpec);
                String originalStateHash = releaseStringSha256(originalState);
                if (!originalSpecHash.equals(WinePrefixDeltaReconciler.sha256(specFile)) ||
                        !originalStateHash.equals(WinePrefixDeltaReconciler.sha256(stateFile))) {
                    throw new IllegalStateException("release_upgrade_metadata_changed_during_snapshot");
                }
                JSONObject originalStateJson = new JSONObject(originalState);
                BoxSpec active = BoxSpec.fromJson(new JSONObject(originalSpec));
                BoxSpec candidate = candidateFromReleaseSnapshot(originalSpec);
                boolean changed = false;
                for (String type : new String[]{"wine-runtime", "prefix-template", "cpu-emu-stack"}) {
                    BoxSpec.Layer current = findLayer(active, type);
                    BoxSpec.Layer desired = findLayer(candidate, type);
                    if (current == null || desired == null || !sameLayerExactly(current, desired)) changed = true;
                }
                if (!changed) {
                    if (!originalSpecHash.equals(WinePrefixDeltaReconciler.sha256(specFile)) ||
                            !originalStateHash.equals(WinePrefixDeltaReconciler.sha256(stateFile)) ||
                            !BoxDebugServer.get(context).verifyLayers().optBoolean("ok", false)) {
                        throw new IllegalStateException("release_upgrade_source_changed_during_preparation");
                    }
                    return;
                }
                validatePublicReleaseBaseline(active, candidate);
                validateSpec(candidate);
                if (!BoxDebugServer.get(context).verifyLayers().optBoolean("ok", false)) {
                    throw new IllegalStateException("release_upgrade_existing_layers_unverified");
                }
                BoxSpec.Layer oldWine = findLayer(active, "wine-runtime");
                BoxSpec.Layer newWine = findLayer(candidate, "wine-runtime");
                BoxSpec.Layer newPrefix = findLayer(candidate, "prefix-template");
                BoxSpec.Layer newFex = findLayer(candidate, "cpu-emu-stack");
                File imageRoot = new File(appRoot, "imagefs");
                if (FileUtils.isSymlink(imageRoot) || !imageRoot.isDirectory()) {
                    throw new IllegalStateException("release_upgrade_unsafe_imagefs");
                }
                File wineDir = new File(imageRoot, "opt/" + BoxRuntime.resolveRuntimeIdentifier(oldWine));
                verifyPublicWineBaseline(wineDir);
                String originalWineTreeHash = BoxReleaseTransaction.sha256(wineDir, true);
                File oldMarker = wineRuntimeMarker(wineDir);
                String originalMarkerHash = null;
                if (FileUtils.isSymlink(oldMarker)) throw new IllegalStateException("release_upgrade_unsafe_marker");
                if (oldMarker.exists()) {
                    if (!wineRuntimeMarkerMatches(wineDir, oldWine.checksum)) {
                        throw new IllegalStateException("release_upgrade_wine_marker_mismatch");
                    }
                    originalMarkerHash = WinePrefixDeltaReconciler.sha256(oldMarker);
                }
                // Preflight before extracting a potentially multi-gigabyte runtime.
                if (appRoot.getUsableSpace() < 3L * 1024 * 1024 * 1024) {
                    throw new IllegalStateException("release_upgrade_insufficient_free_space");
                }
                if (!boxDir.isDirectory()) throw new IllegalStateException("release_upgrade_box_dir_missing");
                stage = java.nio.file.Files.createTempDirectory(boxDir.toPath(), RELEASE_STAGE_PREFIX).toFile();
                writeReleaseStageFile(new File(stage, ".owner"), RELEASE_STAGE_OWNER.getBytes(StandardCharsets.US_ASCII));
                File wineCandidate = new File(stage, "wine");
                File fexCandidate = new File(stage, "fex");
                if (!wineCandidate.mkdir() || !fexCandidate.mkdir()) throw new IOException("Cannot create release stage");
                publish(listener, "release-prepare", 5, 0, "Verifying Wine and FEX assets");
                verifyAssetChecksum(stripAssetPrefix(newWine.source), newWine.checksum);
                verifyAssetChecksum(stripAssetPrefix(newFex.source), newFex.checksum);
                if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context,
                        stripAssetPrefix(newWine.source), wineCandidate)) {
                    throw new IOException("Cannot extract release Wine");
                }
                prepareWineRuntimeLayout(wineCandidate);
                verifyPreparedWineRuntime(wineCandidate);
                if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
                        stripAssetPrefix(newFex.source), fexCandidate)) {
                    throw new IOException("Cannot extract release FEX");
                }
                preserveVerifiedPublicInputBridge(wineDir, wineCandidate);
                ArrayList<BoxReleaseTransaction.Entry> changes = new ArrayList<>();
                changes.add(new BoxReleaseTransaction.Entry(relativeReleaseTarget(appRoot, wineDir), wineCandidate,
                        originalWineTreeHash,
                        BoxReleaseTransaction.sha256(wineCandidate, true), true));
                java.util.HashMap<String, File> finalFiles = new java.util.HashMap<>();
                java.util.HashMap<String, String> optionalPrefixFiles = new java.util.HashMap<>();
                List<WinePrefixDeltaReconciler.Entry> delta = loadWinePrefixDeltaEntries(newPrefix,
                        BoxRuntime.resolveRuntimeIdentifier(newPrefix));
                File prefixRoot = new File(imageRoot, "home/xuser-box/.wine/drive_c/windows");
                for (WinePrefixDeltaReconciler.Entry entry : delta) {
                    File source = new File(wineCandidate, entry.runtimePath);
                    File target = new File(prefixRoot, entry.prefixPath);
                    if (!source.isFile() || FileUtils.isSymlink(source) ||
                            !entry.candidateSha256.equals(WinePrefixDeltaReconciler.sha256(source)) ||
                            FileUtils.isSymlink(target)) {
                        throw new IllegalStateException("release_upgrade_prefix_file_missing_or_unsafe: " + entry.prefixPath);
                    }
                    String criticalPath = "/home/xuser-box/.wine/drive_c/windows/" + entry.prefixPath;
                    if (entry.winebootMayRemove) optionalPrefixFiles.put(criticalPath, entry.candidateSha256);
                    if (java.nio.file.Files.notExists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        if (entry.winebootMayRemove) continue;
                        throw new IllegalStateException("release_upgrade_prefix_file_missing_or_unsafe: " + entry.prefixPath);
                    }
                    if (!target.isFile()) throw new IllegalStateException("release_upgrade_prefix_file_missing_or_unsafe: " + entry.prefixPath);
                    String current = WinePrefixDeltaReconciler.sha256(target);
                    if (!entry.matchesBaseline(current) && !entry.candidateSha256.equals(current)) {
                        throw new IllegalStateException("release_upgrade_unknown_prefix_file: " + entry.prefixPath);
                    }
                    finalFiles.put(criticalPath, source);
                    if (!entry.candidateSha256.equals(current)) {
                        changes.add(new BoxReleaseTransaction.Entry(relativeReleaseTarget(appRoot, target), source,
                                current, entry.candidateSha256, false));
                    }
                }
                addPublicPrefixAdditions(newPrefix, wineCandidate, prefixRoot, appRoot, changes, finalFiles);
                for (String name : new String[]{"libwow64fex.dll", "libarm64ecfex.dll"}) {
                    File source = new File(fexCandidate, name);
                    File target = new File(imageRoot, name);
                    if (!source.isFile() || !target.isFile() || FileUtils.isSymlink(source) || FileUtils.isSymlink(target)) {
                        throw new IllegalStateException("release_upgrade_fex_file_missing: " + name);
                    }
                    String before = recordedReleaseCriticalSha(originalStateJson, "cpu-emu-stack", "/" + name);
                    if (!before.equals(WinePrefixDeltaReconciler.sha256(target))) {
                        throw new IllegalStateException("release_upgrade_fex_changed: " + name);
                    }
                    String after = WinePrefixDeltaReconciler.sha256(source);
                    changes.add(new BoxReleaseTransaction.Entry(relativeReleaseTarget(appRoot, target), source,
                            before, after, false));
                    finalFiles.put("/" + name, source);
                }
                File marker = wineRuntimeMarker(wineDir);
                if (FileUtils.isSymlink(marker)) throw new IllegalStateException("release_upgrade_unsafe_marker");
                if (originalMarkerHash == null ? marker.exists() :
                        !marker.isFile() || !originalMarkerHash.equals(WinePrefixDeltaReconciler.sha256(marker))) {
                    throw new IllegalStateException("release_upgrade_marker_changed");
                }
                File newMarker = new File(stage, "wine-marker");
                writeReleaseStageFile(newMarker, (newWine.checksum.toLowerCase(Locale.US) + "\n").getBytes(StandardCharsets.US_ASCII));
                changes.add(new BoxReleaseTransaction.Entry(relativeReleaseTarget(appRoot, marker), newMarker,
                        originalMarkerHash, WinePrefixDeltaReconciler.sha256(newMarker), false));
                JSONObject state = new JSONObject(originalState);
                // An interrupted post-commit check must never let a partial
                // or unverified release launch silently on the next start.
                state.put("releaseUpgradeVerificationPending", true);
                replaceReleaseLayerRecord(state, newWine, releaseCriticalFiles(newWine, imageRoot, wineCandidate, finalFiles, optionalPrefixFiles));
                replaceReleaseLayerRecord(state, newPrefix, releaseCriticalFiles(newPrefix, imageRoot, wineCandidate, finalFiles, optionalPrefixFiles));
                replaceReleaseLayerRecord(state, newFex, releaseCriticalFiles(newFex, imageRoot, wineCandidate, finalFiles, optionalPrefixFiles));
                File candidateSpec = new File(stage, "box-spec.json");
                File candidateState = new File(stage, "box-state.json");
                writeReleaseStageFile(candidateSpec,
                        releaseSpecPreservingUserFields(originalSpec, candidate).getBytes(StandardCharsets.UTF_8));
                writeReleaseStageFile(candidateState, state.toString().getBytes(StandardCharsets.UTF_8));
                changes.add(new BoxReleaseTransaction.Entry(relativeReleaseTarget(appRoot, specFile), candidateSpec,
                        originalSpecHash, WinePrefixDeltaReconciler.sha256(candidateSpec), false));
                changes.add(new BoxReleaseTransaction.Entry(relativeReleaseTarget(appRoot, stateFile), candidateState,
                        originalStateHash, WinePrefixDeltaReconciler.sha256(candidateState), false));
                publish(listener, "release-activate", 50, 0, "Updating verified runtime and prefix");
                requireIdleWineUpdate();
                if (runtime.getRuntimeOverlay().length() != 0 ||
                        !originalSpecHash.equals(WinePrefixDeltaReconciler.sha256(specFile)) ||
                        !originalStateHash.equals(WinePrefixDeltaReconciler.sha256(stateFile)) ||
                        !originalWineTreeHash.equals(BoxReleaseTransaction.sha256(wineDir, true))) {
                    throw new IllegalStateException("release_upgrade_source_changed_during_preparation");
                }
                verifyPublicWineBaseline(wineDir);
                BoxReleaseTransaction.apply(appRoot, new File(boxDir, "release-upgrade-transaction"), changes, () -> {
                    // The files are active but backups remain available. Avoid
                    // running unrelated startup migrations while verifying them.
                    runtime.setCachedSpecForReleaseValidation(candidate);
                    if (!BoxDebugServer.get(context).verifyLayers().optBoolean("ok", false)) {
                        throw new IllegalStateException("release_upgrade_post_verify_failed");
                    }
                }, BoxInstaller::sameReleaseFilesystem);
                runtime.invalidateCachedSpec();
                finishPendingReleaseValidation();
                publish(listener, "release-complete", 100, 100, "Runtime update complete");
            } catch (BoxReleaseTransaction.CommittedCleanupException committed) {
                runtime.invalidateCachedSpec();
                throw new IllegalStateException("release_upgrade_committed_cleanup_pending", committed);
            } catch (Exception e) {
                throw new IllegalStateException("release_upgrade_failed: " + e.getMessage(), e);
            } finally {
                // The validator may have loaded the candidate just before a
                // failure; always make the next reader reload disk state.
                runtime.invalidateCachedSpec();
                if (stage != null && stage.exists() && !FileUtils.delete(stage)) {
                    Log.w(TAG, "Unable to clean temporary release stage " + stage);
                }
            }
        }
    }

    private void validatePublicReleaseBaseline(BoxSpec active, BoxSpec candidate) {
        BoxSpec.Layer oldWine = findLayer(active, "wine-runtime");
        BoxSpec.Layer oldPrefix = findLayer(active, "prefix-template");
        BoxSpec.Layer oldFex = findLayer(active, "cpu-emu-stack");
        BoxSpec.Layer input = findLayer(active, "input-bridge-stack");
        BoxSpec.Layer newWine = findLayer(candidate, "wine-runtime");
        BoxSpec.Layer newPrefix = findLayer(candidate, "prefix-template");
        BoxSpec.Layer newFex = findLayer(candidate, "cpu-emu-stack");
        String runtimeId = "proton-11.0-1-arm64ec-wowbox-openfonts-9ac94fff";
        if (oldWine == null || oldPrefix == null || oldFex == null || input == null ||
                newWine == null || newPrefix == null || newFex == null ||
                !"9ac94fff85c5156e0179e1e11508b1cbb569d4f974bca8838df319327c6f291d".equalsIgnoreCase(oldWine.checksum) ||
                !"350c6f287ef2008e62439a00f67affc0ef650a7ec2c2b7e8d0bea89e47c2f05a".equalsIgnoreCase(oldPrefix.checksum) ||
                !"5f1dbc8db81adcbcc25e1e4119b8bb16d4abbeb8d37b06a95249764e21edeae3".equalsIgnoreCase(oldFex.checksum) ||
                !"437d68591aace9cd599e7e2653abe0333fa7726df1d65d20fc2ffa33a2beff4d".equalsIgnoreCase(input.checksum) ||
                !runtimeId.equals(oldWine.runtimeIdentifier) || !runtimeId.equals(oldPrefix.runtimeIdentifier) ||
                !runtimeId.equals(input.runtimeIdentifier) || !oldWine.target.equals(newWine.target) ||
                !runtimeId.equals(newWine.runtimeIdentifier) || !runtimeId.equals(newPrefix.runtimeIdentifier) ||
                !"086c3fe69c60493c2a43c9625a3b4d755bff2d6ebc3f44e02a0c85f49cc6efd6".equalsIgnoreCase(newWine.checksum) ||
                !"2beba31f8d0f32c192433f1cd0f8d73e53799e330203d0c7d0d11a883e98ed9d".equalsIgnoreCase(newFex.checksum) ||
                !sameLayerExceptWinePrefixDelta(oldPrefix, newPrefix) ||
                !isInPlaceWineCandidateLayer(oldWine, newWine)) {
            throw new IllegalStateException("release_upgrade_unsupported_old_or_new_runtime");
        }
        for (String type : new String[]{"wine-runtime", "prefix-template", "cpu-emu-stack", "input-bridge-stack"}) {
            BoxSpec.Layer layer = findLayer(active, type);
            JSONObject record = findInstalledLayerRecord(type);
            JSONArray baseline = record != null ? record.optJSONArray("criticalFiles") : null;
            JSONArray actual;
            try {
                actual = hashCriticalFiles(layer);
            } catch (JSONException error) {
                throw new IllegalStateException("release_upgrade_layer_verification_failed: " + type, error);
            }
            if (!sameLayerIdentity(layer, record) || !criticalFileRecordsExactlyMatch(baseline, actual)) {
                throw new IllegalStateException("release_upgrade_layer_identity_or_hash_mismatch: " + type);
            }
        }
    }

    private void verifyPublicWineBaseline(File wineDir) throws Exception {
        String asset = "wine_runtime_baseline/wowbox-v010-tree.json";
        verifyAssetChecksum(asset, "663594b4f47ef62696993751cc8512d084f449530cbdfd5a084d8862dee63311");
        JSONObject manifest = new JSONObject(FileUtils.readString(context, asset));
        if (manifest.optInt("schemaVersion", 0) != 1 ||
                !"9ac94fff85c5156e0179e1e11508b1cbb569d4f974bca8838df319327c6f291d".equals(manifest.optString("runtimeArchiveSha256")) ||
                !"437d68591aace9cd599e7e2653abe0333fa7726df1d65d20fc2ffa33a2beff4d".equals(manifest.optString("inputBridgeArchiveSha256"))) {
            throw new IllegalStateException("release_upgrade_invalid_wine_baseline");
        }
        JSONArray rows = manifest.getJSONArray("entries");
        ArrayList<BoxWineBaselineVerifier.Entry> entries = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            ArrayList<String> allowed = new ArrayList<>();
            JSONArray hashes = row.optJSONArray("allowedSha256");
            if (hashes != null) for (int j = 0; j < hashes.length(); j++) allowed.add(hashes.getString(j));
            entries.add(new BoxWineBaselineVerifier.Entry(row.getString("path"), row.getString("type"),
                    row.optString("sha256", null), allowed, row.optString("linkTarget", null)));
        }
        BoxWineBaselineVerifier.verify(wineDir, entries);
    }

    private void preserveVerifiedPublicInputBridge(File oldWine, File newWine) throws Exception {
        String[] names = {"xinput1_3.dll", "xinput1_4.dll"};
        String[] expected = {"05ce004a27c25ef13fa60182ae1cc7c993ecf8615891563c59b5e615ede46a45",
                "b390f99c043ddf0d724607946065dba0cd8dfc2b06744b8edf2714ff300854d0"};
        for (int i = 0; i < names.length; i++) {
            File old = new File(oldWine, "lib/wine/aarch64-windows/" + names[i]);
            File target = new File(newWine, "lib/wine/aarch64-windows/" + names[i]);
            if (!old.isFile() || FileUtils.isSymlink(old) || !target.isFile() || FileUtils.isSymlink(target) ||
                    !expected[i].equals(WinePrefixDeltaReconciler.sha256(old))) {
                throw new IllegalStateException("release_upgrade_input_bridge_mismatch: " + names[i]);
            }
            java.nio.file.Files.copy(old.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            if (!expected[i].equals(WinePrefixDeltaReconciler.sha256(target))) {
                throw new IOException("Cannot stage input bridge: " + names[i]);
            }
        }
    }

    private void addPublicPrefixAdditions(BoxSpec.Layer layer, File stagedWine, File prefix,
                                          File appRoot, ArrayList<BoxReleaseTransaction.Entry> changes,
                                          java.util.Map<String, File> finalFiles) throws Exception {
        String asset = stripAssetPrefix(layer.winePrefixDeltaManifest);
        verifyAssetChecksum(asset, layer.winePrefixDeltaChecksum);
        JSONObject manifest = new JSONObject(FileUtils.readString(context, asset));
        JSONArray additions = manifest.optJSONArray("additions");
        if (additions == null || additions.length() != 1) throw new IllegalStateException("Invalid release additions");
        JSONObject entry = additions.getJSONObject(0);
        String path = entry.getString("prefixPath");
        String sourcePath = entry.getString("runtimePath");
        String expected = entry.getString("candidateSha256").toLowerCase(Locale.US);
        if (!"system32/winedevice-x64.exe".equals(path) ||
                !"lib/wine/aarch64-windows/winedevice-x64.exe".equals(sourcePath) || !expected.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Invalid release prefix addition");
        }
        File source = new File(stagedWine, sourcePath);
        File target = new File(prefix, path);
        if (!source.isFile() || FileUtils.isSymlink(source) || !expected.equals(WinePrefixDeltaReconciler.sha256(source)) ||
                FileUtils.isSymlink(target)) throw new IllegalStateException("Invalid release prefix addition source/target");
        if (target.exists()) {
            if (!target.isFile() || !expected.equals(WinePrefixDeltaReconciler.sha256(target))) {
                throw new IllegalStateException("Unknown existing Wine prefix addition");
            }
        } else {
            changes.add(new BoxReleaseTransaction.Entry(relativeReleaseTarget(appRoot, target), source, null, expected, false));
        }
        finalFiles.put("/home/xuser-box/.wine/drive_c/windows/" + path, source);
    }

    private JSONArray releaseCriticalFiles(BoxSpec.Layer layer, File imageRoot, File stagedWine,
                                           java.util.Map<String, File> finalFiles,
                                           java.util.Map<String, String> optionalPrefixFiles) throws Exception {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>(java.util.Arrays.asList(criticalPathsForLayer(layer)));
        if ("prefix-template".equals(layer.type)) {
            paths.add("/home/xuser-box/.wine/drive_c/windows/system32/services.exe");
            paths.add("/home/xuser-box/.wine/drive_c/windows/system32/winedevice-x64.exe");
        }
        String winePrefix = "/opt/" + BoxRuntime.resolveRuntimeIdentifier(layer) + "/";
        JSONArray records = new JSONArray();
        for (String path : paths) {
            File value = finalFiles.get(path);
            if (value == null && "wine-runtime".equals(layer.type) && path.startsWith(winePrefix)) {
                value = new File(stagedWine, path.substring(winePrefix.length()));
            }
            if (value == null) value = new File(imageRoot, path.substring(1));
            JSONObject row = new JSONObject();
            row.put("path", path);
            if (optionalPrefixFiles.containsKey(path) &&
                    java.nio.file.Files.notExists(value.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                row.put("exists", false); row.put("size", 0); row.put("sha256", "");
            } else {
                if (!value.isFile() || (optionalPrefixFiles.containsKey(path) && FileUtils.isSymlink(value))) {
                    throw new IllegalStateException("Missing or unsafe release critical file: " + path);
                }
                String hash = WinePrefixDeltaReconciler.sha256(value);
                if (optionalPrefixFiles.containsKey(path) && !optionalPrefixFiles.get(path).equals(hash)) {
                    throw new IllegalStateException("Invalid optional release critical file: " + path);
                }
                row.put("exists", true); row.put("size", value.length()); row.put("sha256", hash);
            }
            records.put(row);
        }
        return records;
    }

    private void replaceReleaseLayerRecord(JSONObject state, BoxSpec.Layer layer, JSONArray critical) throws JSONException {
        JSONArray existing = state.optJSONArray("installedLayers");
        if (existing == null) throw new IllegalStateException("Release layer records are missing");
        JSONObject replacement = new JSONObject();
        replacement.put("type", layer.type); replacement.put("source", layer.source);
        replacement.put("runtimeIdentifier", layer.runtimeIdentifier); replacement.put("checksum", layer.checksum);
        replacement.put("target", layer.target); replacement.put("winePrefixDeltaManifest", layer.winePrefixDeltaManifest);
        replacement.put("winePrefixDeltaChecksum", layer.winePrefixDeltaChecksum);
        replacement.put("wineBuiltinCompilerSha256", layer.wineBuiltinCompilerSha256);
        replacement.put("installedAt", System.currentTimeMillis()); replacement.put("criticalFiles", critical);
        JSONArray updated = new JSONArray(); int replaced = 0;
        for (int i = 0; i < existing.length(); i++) {
            JSONObject row = existing.getJSONObject(i);
            if (layer.type.equals(row.optString("type"))) { updated.put(replacement); replaced++; }
            else updated.put(row);
        }
        if (replaced != 1) throw new IllegalStateException("Missing or duplicate release layer record: " + layer.type);
        state.put("installedLayers", updated);
    }

    private static String relativeReleaseTarget(File root, File target) throws IOException {
        java.nio.file.Path r = root.toPath().toAbsolutePath().normalize();
        java.nio.file.Path t = target.toPath().toAbsolutePath().normalize();
        if (!t.startsWith(r) || t.equals(r)) throw new IOException("Release target outside app data");
        return r.relativize(t).toString();
    }

    /** Replace only release-owned layers, retaining unrecognized user fields. */
    static String releaseSpecPreservingUserFields(String originalSpec, BoxSpec candidate) throws Exception {
        JSONObject original = new JSONObject(originalSpec);
        JSONArray layers = original.getJSONArray("layers");
        for (String type : new String[]{"wine-runtime", "prefix-template", "cpu-emu-stack"}) {
            BoxSpec.Layer replacement = findLayer(candidate, type);
            if (replacement == null) throw new IllegalStateException("Missing release layer: " + type);
            int found = 0;
            for (int i = 0; i < layers.length(); i++) {
                JSONObject current = layers.getJSONObject(i);
                if (type.equals(current.optString("type", ""))) {
                    // Keep unknown extension fields belonging to the user or
                    // a newer schema while updating only known layer fields.
                    JSONObject merged = new JSONObject(current.toString());
                    JSONObject desired = replacement.toJson();
                    java.util.Iterator<String> keys = desired.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        merged.put(key, desired.get(key));
                    }
                    layers.put(i, merged);
                    found++;
                }
            }
            if (found != 1) throw new IllegalStateException("Missing or duplicate release layer: " + type);
        }
        return original.toString();
    }

    private BoxSpec candidateFromReleaseSnapshot(String originalSpec) throws Exception {
        BoxSpec candidate = BoxSpec.fromJson(new JSONObject(originalSpec));
        BoxSpec bundled = BoxSpec.fromJson(new JSONObject(FileUtils.readString(context, BoxSpec.getBundledAssetPath())));
        if (!candidate.boxId.equals(bundled.boxId)) throw new IllegalStateException("bundled_box_id_mismatch");
        for (String type : new String[]{"wine-runtime", "prefix-template", "cpu-emu-stack"}) {
            BoxSpec.Layer desired = findLayer(bundled, type);
            if (desired == null) throw new IllegalStateException("bundled_layer_missing: " + type);
            int found = 0;
            for (int i = 0; i < candidate.layers.size(); i++) {
                if (type.equals(candidate.layers.get(i).type)) {
                    candidate.layers.set(i, desired); found++;
                }
            }
            if (found != 1) throw new IllegalStateException("Missing or duplicate old layer: " + type);
        }
        return candidate;
    }

    private static String releaseStringSha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64);
        for (byte valueByte : bytes) out.append(String.format(Locale.US, "%02x", valueByte & 0xff));
        return out.toString();
    }

    private static String recordedReleaseCriticalSha(JSONObject state, String type, String path) throws Exception {
        JSONArray layers = state.getJSONArray("installedLayers");
        JSONObject match = null;
        for (int i = 0; i < layers.length(); i++) {
            JSONObject item = layers.getJSONObject(i);
            if (type.equals(item.optString("type", ""))) {
                if (match != null) throw new IllegalStateException("Duplicate installed layer: " + type);
                match = item;
            }
        }
        if (match == null) throw new IllegalStateException("Missing installed layer: " + type);
        JSONArray files = match.getJSONArray("criticalFiles");
        String sha = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject row = files.getJSONObject(i);
            if (!path.equals(row.optString("path", ""))) continue;
            if (sha != null || !row.optBoolean("exists", false)) {
                throw new IllegalStateException("Invalid installed critical file: " + path);
            }
            sha = row.optString("sha256", "").toLowerCase(Locale.US);
        }
        if (sha == null || !sha.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Missing installed critical hash: " + path);
        }
        return sha;
    }

    private static void writeReleaseStageFile(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes); out.flush(); out.getFD().sync();
        }
    }

    /** Android libcore does not implement Files.getFileStore on all releases. */
    private static boolean sameReleaseFilesystem(File first, File second) throws IOException {
        try {
            return android.system.Os.stat(first.getAbsolutePath()).st_dev ==
                    android.system.Os.stat(second.getAbsolutePath()).st_dev;
        } catch (android.system.ErrnoException e) {
            throw new IOException("Cannot verify release target filesystem", e);
        }
    }

    /** Remove only our own abandoned preparation dirs, after journal recovery. */
    void cleanupAbandonedReleaseStages() throws IOException {
        File boxDir = new File(context.getFilesDir().getCanonicalFile(), "box");
        if (!boxDir.isDirectory() || FileUtils.isSymlink(boxDir)) return;
        File journal = new File(boxDir, "release-upgrade-transaction");
        if (journal.exists() || FileUtils.isSymlink(journal)) {
            throw new IOException("release_journal_requires_recovery");
        }
        File[] stages = boxDir.listFiles((dir, name) -> name.startsWith(RELEASE_STAGE_PREFIX));
        if (stages == null) throw new IOException("Unable to inspect release stages");
        for (File stage : stages) {
            if (!stage.isDirectory() || FileUtils.isSymlink(stage)) continue;
            File owner = new File(stage, ".owner");
            if (!owner.isFile() || FileUtils.isSymlink(owner)) continue;
            if (!RELEASE_STAGE_OWNER.equals(AtomicFileSupport.readUtf8(owner))) continue;
            if (!FileUtils.delete(stage)) throw new IOException("Unable to clean abandoned release stage");
        }
    }

    /** Finish a post-commit preflight after process interruption or normal activation. */
    public void finishPendingReleaseValidation() {
        synchronized (BoxInstaller.class) {
            JSONObject state = runtime.getState();
            if (!state.optBoolean("releaseUpgradeVerificationPending", false)) return;
            try {
                requireIdleWineUpdate();
                if (needsBundledReleaseUpgrade() || !BoxDebugServer.get(context).verifyLayers().optBoolean("ok", false)) {
                    throw new IllegalStateException("release_upgrade_post_verify_failed");
                }
                state.remove("releaseUpgradeVerificationPending");
                File appRoot = context.getFilesDir().getCanonicalFile();
                File stateFile = new File(appRoot, "box/box-state.json");
                File stage = java.nio.file.Files.createTempFile(new File(appRoot, "box").toPath(), ".release-state-", ".tmp").toFile();
                try {
                    writeReleaseStageFile(stage, state.toString().getBytes(StandardCharsets.UTF_8));
                    ArrayList<BoxReleaseTransaction.Entry> one = new ArrayList<>();
                    one.add(new BoxReleaseTransaction.Entry(relativeReleaseTarget(appRoot, stateFile), stage,
                            WinePrefixDeltaReconciler.sha256(stateFile), WinePrefixDeltaReconciler.sha256(stage), false));
                    BoxReleaseTransaction.apply(appRoot, new File(appRoot, "box/release-upgrade-transaction"),
                            one, BoxInstaller::sameReleaseFilesystem);
                } finally { if (stage.exists()) stage.delete(); }
            } catch (Exception e) {
                throw new IllegalStateException("release_upgrade_validation_pending", e);
            }
        }
    }
    public void installAsync(boolean dryRun, Callback<Boolean> callback, BoxInstallListener listener) {
        Executors.newSingleThreadExecutor().execute(() -> callback.call(install(dryRun, listener)));
    }

    public boolean install(boolean dryRun, BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            if (!dryRun && runtime.isInstalled()) {
                publish(listener, "already-installed", 100, 100, runtime.getSpec().displayName);
                return true;
            }
            return installLocked(dryRun, listener);
        }
    }

    /** Verify ownership before an Activity bypasses legacy DX re-extraction. */
    public void requireManagedDxInstalled(BoxSpec spec) {
        synchronized (BoxInstaller.class) {
            try {
                for (BoxSpec.Layer layer : ManagedDxProfile.ownedLayers(spec)) {
                    JSONObject installed = findInstalledLayerRecord(layer.type);
                    JSONArray baseline = installed != null ?
                            installed.optJSONArray("criticalFiles") : null;
                    JSONArray current = hashCriticalFiles(layer);
                    if (!sameLayerIdentity(layer, installed) ||
                            baseline == null || baseline.length() == 0 ||
                            !allCriticalFileRecordsPresent(baseline) ||
                            !allCriticalFileRecordsPresent(current) ||
                            !criticalFileRecordsExactlyMatch(baseline, current)) {
                        throw new IllegalStateException(
                                "Managed DX layer is not verified: " + layer.type);
                    }
                }
            }
            catch (JSONException error) {
                throw new IllegalStateException("Cannot verify managed DX layer", error);
            }
        }
    }

    /** The selected runtime must still be the installed, verified Wine layer. */
    public File requireActiveWineRuntime() {
        synchronized (BoxInstaller.class) {
            BoxSpec.Layer layer = findLayer(runtime.getSpec(), "wine-runtime");
            if (layer == null || layer.checksum == null ||
                    !layer.checksum.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalStateException("Wine runtime has no verified asset identity");
            }
            String id = BoxRuntime.resolveRuntimeIdentifier(layer);
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._+-]*") ||
                    !("/opt/" + id).equals(layer.target)) {
                throw new IllegalStateException("Unsafe Wine runtime identity");
            }
            File dir = new File(ImageFs.find(context).getInstalledWineDir(), id);
            try {
                JSONObject installed = findInstalledLayerRecord("wine-runtime");
                JSONArray baseline = installed != null ? installed.optJSONArray("criticalFiles") : null;
                JSONArray current = hashCriticalFiles(layer);
                BoxDebugServer debug = BoxDebugServer.get(context);
                JSONObject overlay = debug.verifyRuntimeOverlay();
                File marker = wineRuntimeMarker(dir);
                if (!dir.isDirectory() || FileUtils.isSymlink(dir) ||
                        !WineBuiltinRestore.runtimeMarkerAllowed(layer.replacePolicy, marker.exists(),
                                FileUtils.isSymlink(marker), wineRuntimeMarkerMatches(dir, layer.checksum)) ||
                        !sameLayerIdentity(layer, installed) ||
                        baseline == null || baseline.length() == 0 ||
                        !allCriticalFileRecordsPresent(baseline) ||
                        !allCriticalFileRecordsPresent(current) ||
                        !overlay.optBoolean("ok", false) ||
                        !debug.criticalFilesMatch(baseline, current,
                                debug.verifiedRuntimeOverlayHashes(overlay), new JSONArray())) {
                    throw new IllegalStateException("Selected Wine runtime is not verified: " + id);
                }
                return dir;
            } catch (JSONException error) {
                throw new IllegalStateException("Cannot verify selected Wine runtime", error);
            }
        }
    }

    public void prepareNativeDxEnvironment() {
        synchronized (BoxInstaller.class) {
            BoxSpec.Layer layer = findLayer(runtime.getSpec(), ManagedDxProfile.NATIVE_LAYER_TYPE);
            if (layer == null) return;
            try {
                requireManagedDxInstalled(runtime.getSpec());
                File registry = new File(runtime.getContainerRootDir(), ".wine/user.reg");
                String directory = NativeDxEnvironment.windowsDirectory(layer);
                if (XServerDisplayActivity.hasActiveSession() ||
                        WineSessionProcessController.getInstance().hasActiveWineProcesses()) {
                    // Warm commands inherit the already prepared Windows environment.
                    NativeDxEnvironment.requirePath(registry, directory);
                }
                else NativeDxEnvironment.reconcile(registry, directory);
            }
            catch (IOException error) {
                throw new IllegalStateException("Cannot prepare native ARM64 DX PATH", error);
            }
        }
    }

    public JSONObject nativeDxStatus() throws JSONException {
        BoxSpec.Layer layer = findLayer(runtime.getSpec(), ManagedDxProfile.NATIVE_LAYER_TYPE);
        JSONObject result = new JSONObject();
        result.put("enabled", layer != null);
        result.put("ready", layer == null);
        if (layer != null) {
            try {
                String directory = NativeDxEnvironment.windowsDirectory(layer);
                NativeDxEnvironment.requirePath(
                        new File(runtime.getContainerRootDir(), ".wine/user.reg"), directory);
                result.put("windowsDirectory", directory);
                result.put("ready", true);
            }
            catch (Exception error) {
                result.put("error", error.getMessage());
            }
        }
        return result;
    }

    public boolean installNativeDxWrapperStackUpdate(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            try {
                requireIdleWineUpdate();
                BoxSpec spec = runtime.getSpec();
                validateSpec(spec);
                BoxSpec.Layer layer = findLayer(spec, ManagedDxProfile.NATIVE_LAYER_TYPE);
                if (layer == null) throw new IllegalStateException("Native DX layer is missing");
                BoxSpec.Layer legacy = findLayer(spec, "dx-wrapper-stack");
                LinkedHashMap<String, String> remaining = null;
                if (NativeSystemDxProfile.enabled(spec)) {
                    JSONObject installed = legacy != null ?
                            findInstalledLayerRecord("dx-wrapper-stack") : null;
                    if (installed == null || !sameLayerIdentity(legacy, installed)) {
                        throw new IllegalStateException("Legacy DX identity is missing");
                    }
                    remaining = criticalHashMap(hashCriticalFiles(legacy));
                    NativeSystemDxProfile.requireUnchangedRemaining(
                            criticalHashMap(installed.optJSONArray("criticalFiles")), remaining);
                }
                installNativeDxWrapperStack(layer, listener, 100);
                if (remaining != null) {
                    NativeSystemDxProfile.requireUnchangedRemaining(remaining,
                            criticalHashMap(hashCriticalFiles(legacy)));
                    noteLayerInstalled(legacy, false);
                }
                noteLayerInstalled(layer, false);
                prepareNativeDxEnvironment();
                return true;
            }
            catch (Exception error) {
                Log.e(TAG, "Native DX update failed", error);
                BoxDebugEventBus.publish("native-dx-update-failed", payload("message", error.toString()));
                return false;
            }
        }
    }

    private LinkedHashMap<String, String> criticalHashMap(JSONArray files) {
        if (files == null) throw new IllegalStateException("Critical hash baseline is missing");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null || !file.optBoolean("exists", false)) {
                throw new IllegalStateException("Critical hash file is missing");
            }
            result.put(file.optString("path", ""), file.optString("sha256", ""));
        }
        return result;
    }

    private void synchronizeNativeSystemDx(File sourceDirectory) throws Exception {
        File system32 = new File(runtime.getContainerRootDir(), ".wine/drive_c/windows/system32");
        String[] names = {"d3d11.dll", "dxgi.dll"};
        File[] targets = new File[2], prepared = new File[2], backups = new File[2];
        String[] hashes = new String[2];
        boolean[] activated = new boolean[2], backedUp = new boolean[2];
        boolean[] preparedByThisCall = new boolean[2];
        try {
            for (int i = 0; i < names.length; i++) {
                File source = new File(sourceDirectory, names[i]);
                NativeDxPeImage.requirePlainArm64(source);
                hashes[i] = sha256(source);
                targets[i] = new File(system32, names[i]);
                prepared[i] = new File(targets[i].getAbsolutePath() + ".native-dx-new");
                backups[i] = new File(targets[i].getAbsolutePath() + ".native-dx-backup");
                if (prepared[i].exists() || backups[i].exists() ||
                        FileUtils.isSymlink(prepared[i]) || FileUtils.isSymlink(backups[i])) {
                    throw new IllegalStateException("Pending native System32 DX transaction");
                }
                preparedByThisCall[i] = true;
                if (hashes[i].isEmpty() || !FileUtils.copy(source, prepared[i]) ||
                        !hashes[i].equalsIgnoreCase(sha256(prepared[i]))) {
                    throw new IllegalStateException("Cannot prepare native System32 " + names[i]);
                }
            }
            for (int i = 0; i < names.length; i++) {
                if (targets[i].exists() || FileUtils.isSymlink(targets[i])) {
                    if (!targets[i].renameTo(backups[i])) {
                        throw new IllegalStateException("Cannot back up System32 " + names[i]);
                    }
                    backedUp[i] = true;
                }
                if (!prepared[i].renameTo(targets[i])) {
                    throw new IllegalStateException("Cannot activate System32 " + names[i]);
                }
                activated[i] = true;
                if (!hashes[i].equalsIgnoreCase(sha256(targets[i]))) {
                    throw new IllegalStateException("Native System32 DX activation hash mismatch");
                }
            }
        }
        catch (Exception error) {
            for (int i = names.length - 1; i >= 0; i--) {
                if (activated[i]) FileUtils.delete(targets[i]);
                if (backedUp[i] && !backups[i].renameTo(targets[i])) {
                    error.addSuppressed(new IllegalStateException("Cannot restore System32 " + names[i]));
                }
            }
            throw error;
        }
        finally {
            for (int i = 0; i < prepared.length; i++) {
                if (preparedByThisCall[i] && prepared[i].exists()) FileUtils.delete(prepared[i]);
            }
        }
        for (File file : backups) if (file.exists()) FileUtils.delete(file);
    }

    private void synchronizeNativeShaderCompiler(BoxSpec.Layer layer) throws Exception {
        if (!layer.hasCapability(NativeShaderCompilerProfile.CAPABILITY)) return;
        BoxSpec spec = runtime.getSpec();
        String runtimeId = NativeShaderCompilerProfile.runtimeIdentifier(spec, layer);
        BoxSpec.Layer wineLayer = findLayer(spec, "wine-runtime");
        requireInstalledWineRuntimeMatches(wineLayer);
        File wineDir = new File(ImageFs.find(context).getInstalledWineDir(), runtimeId);
        File source = new File(wineDir, "lib/wine/aarch64-windows/" + NativeShaderCompilerProfile.FILENAME);
        if (!layer.wineBuiltinCompilerSha256.equalsIgnoreCase(sha256(source))) {
            throw new IllegalStateException("Native shader compiler source hash does not match its manifest");
        }
        NativeDxPeImage.requireWineArm64xBuiltin(source);
        File system32 = new File(runtime.getContainerRootDir(), ".wine/drive_c/windows/system32");
        syncWineBuiltin(wineDir, "aarch64-windows", NativeShaderCompilerProfile.FILENAME, system32);
        File registry = new File(runtime.getContainerRootDir(), ".wine/user.reg");
        try (WineRegistryEditor editor = new WineRegistryEditor(registry)) {
            String key = "Software\\Wine\\DllOverrides";
            String value = editor.getStringValue(key, "d3dcompiler_47", "");
            if (NativeShaderCompilerProfile.isOwnedNativeOverride(value)) {
                editor.removeValue(key, "d3dcompiler_47");
            }
        }
    }

    /** Run after component reconciliation, before Wine starts loading helpers. */
    public void reconcileNativeShaderCompilerAfterComponents() {
        synchronized (BoxInstaller.class) {
            BoxSpec.Layer layer = NativeShaderCompilerProfile.find(runtime.getSpec());
            if (layer == null) return;
            if (WineSessionProcessController.getInstance().hasActiveWineProcesses()) {
                throw new IllegalStateException("Stop Wine before reconciling its shader compiler");
            }
            try {
                requireManagedDxInstalled(runtime.getSpec());
                synchronizeNativeShaderCompiler(layer);
                noteLayerInstalled(layer, false);
            }
            catch (Exception error) {
                throw new IllegalStateException("Cannot reconcile the native Wine shader compiler", error);
            }
        }
    }

    public boolean installInitial(boolean dryRun, BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            return installLocked(dryRun, listener);
        }
    }

    /**
     * Installs only the managed GPU driver referenced by the active Box spec.
     *
     * This is intentionally narrower than the initial installer: updating an immutable
     * Adrenotools asset must not reinstall the base imagefs or touch a persistent Wine prefix.
     */
    public boolean installGpuDriverUpdate(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            BoxSpec spec = runtime.getSpec();
            try {
                validateSpec(spec);
                for (BoxSpec.Layer layer : spec.layers) {
                    if (!"gpu-driver-stack".equals(layer.type)) continue;
                    installLayer(spec, layer, false, listener, 100);
                    noteLayerInstalled(layer, false);
                    BoxDebugEventBus.publish("gpu-driver-update-complete", payload("source", layer.source));
                    return true;
                }
                throw new IllegalStateException("gpu-driver-stack layer is missing");
            }
            catch (Exception e) {
                Log.e(TAG, "GPU driver update failed", e);
                BoxDebugEventBus.publish("gpu-driver-update-failed", payload("message", e.toString()));
                return false;
            }
        }
    }

    /**
     * Installs only the managed Bionic Vulkan wrapper referenced by the active
     * Box spec. The wrapper lives in imagefs and this targeted path deliberately
     * leaves the persistent Wine prefix and selected Turnip driver untouched.
     */
    public boolean installGraphicsWrapperStackUpdate(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            BoxSpec spec = runtime.getSpec();
            try {
                validateSpec(spec);
                for (BoxSpec.Layer layer : spec.layers) {
                    if (!"graphics-wrapper-stack".equals(layer.type)) continue;
                    installLayer(spec, layer, false, listener, 100);
                    noteLayerInstalled(layer, false);
                    BoxDebugEventBus.publish(
                            "graphics-wrapper-stack-update-complete",
                            payload("source", layer.source));
                    return true;
                }
                throw new IllegalStateException("graphics-wrapper-stack layer is missing");
            }
            catch (Exception e) {
                Log.e(TAG, "Graphics wrapper stack update failed", e);
                BoxDebugEventBus.publish(
                        "graphics-wrapper-stack-update-failed",
                        payload("message", e.toString()));
                return false;
            }
        }
    }

    /**
     * Installs only the managed CPU bridge referenced by the active Box spec.
     *
     * The bridge pair lives at the imagefs root and is copied into the active
     * prefix by GuestProgramLauncherComponent on the next container start. This
     * path deliberately leaves the protected prefix and every other layer alone.
     */
    public boolean installCpuEmuStackUpdate(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            BoxSpec spec = runtime.getSpec();
            try {
                validateSpec(spec);
                for (BoxSpec.Layer layer : spec.layers) {
                    if (!"cpu-emu-stack".equals(layer.type)) continue;
                    installLayer(spec, layer, false, listener, 100);
                    noteLayerInstalled(layer, false);
                    BoxDebugEventBus.publish("cpu-emu-stack-update-complete", payload("source", layer.source));
                    return true;
                }
                throw new IllegalStateException("cpu-emu-stack layer is missing");
            }
            catch (Exception e) {
                Log.e(TAG, "CPU emulation stack update failed", e);
                BoxDebugEventBus.publish("cpu-emu-stack-update-failed", payload("message", e.toString()));
                return false;
            }
        }
    }

    /**
     * Installs the process-filtered WinHandler XInput bridge in both places
     * involved in Wine builtin resolution: the prefix copy identifies the
     * requested builtin while the runtime copy is the implementation Wine
     * actually maps.  The bridge itself fails closed outside the explicitly
     * allowed WoW process names.
     */
    public boolean installInputBridgeStackUpdate(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            BoxSpec spec = runtime.getSpec();
            try {
                requireIdleWineUpdate();
                validateSpec(spec);
                for (BoxSpec.Layer layer : spec.layers) {
                    if (!"input-bridge-stack".equals(layer.type)) continue;
                    installLayer(spec, layer, false, listener, 100);
                    noteLayerInstalled(layer, false);
                    BoxDebugEventBus.publish("input-bridge-stack-update-complete",
                            payload("source", layer.source));
                    return true;
                }
                throw new IllegalStateException("input-bridge-stack layer is missing");
            }
            catch (Exception e) {
                Log.e(TAG, "Input bridge stack update failed", e);
                BoxDebugEventBus.publish("input-bridge-stack-update-failed",
                        payload("message", e.toString()));
                return false;
            }
        }
    }

    /** Installs only the optional LSFG Vulkan layer; never touches the Wine prefix. */
    public boolean installFrameGenerationStackUpdate(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            BoxSpec spec = runtime.getSpec();
            try {
                validateSpec(spec);
                for (BoxSpec.Layer layer : spec.layers) {
                    if (!FrameGenerationManager.LAYER_TYPE.equals(layer.type)) continue;
                    installLayer(spec, layer, false, listener, 100);
                    noteLayerInstalled(layer, false);
                    BoxDebugEventBus.publish(
                            "frame-generation-stack-update-complete",
                            payload("source", layer.source));
                    return true;
                }
                throw new IllegalStateException("frame-generation-stack layer is missing");
            }
            catch (Exception e) {
                Log.e(TAG, "Frame generation stack update failed", e);
                BoxDebugEventBus.publish(
                        "frame-generation-stack-update-failed",
                        payload("message", e.toString()));
                return false;
            }
        }
    }

    /** Installs only the selected immutable DXVK archive into the persistent prefix. */
    public boolean installDxWrapperStackUpdate(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            BoxSpec spec = runtime.getSpec();
            try {
                validateSpec(spec);
                for (BoxSpec.Layer layer : spec.layers) {
                    if (!"dx-wrapper-stack".equals(layer.type)) continue;
                    installLayer(spec, layer, false, listener, 100);
                    noteLayerInstalled(layer, false);
                    BoxDebugEventBus.publish("dx-wrapper-stack-update-complete", payload("source", layer.source));
                    return true;
                }
                throw new IllegalStateException("dx-wrapper-stack layer is missing");
            }
            catch (Exception e) {
                Log.e(TAG, "DX wrapper stack update failed", e);
                BoxDebugEventBus.publish("dx-wrapper-stack-update-failed", payload("message", e.toString()));
                return false;
            }
        }
    }

    /**
     * Installs only the immutable Wine runtime referenced by the active Box spec.
     *
     * This deliberately does not reinstall imagefs or the persistent prefix.  Prefix
     * migration is performed afterwards through the normal Wine wineboot path.
     */
    public boolean installWineRuntimeUpdate(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            BoxSpec spec = runtime.getSpec();
            try {
                requireIdleWineUpdate();
                if (runtime.isPrefixProtected()) {
                    throw new IllegalStateException("protected_prefix: Wine runtime update is disabled");
                }
                validateSpec(spec);
                for (BoxSpec.Layer layer : spec.layers) {
                    if (!"wine-runtime".equals(layer.type)) continue;
                    installLayer(spec, layer, false, listener, 100);
                    noteLayerInstalled(layer, false);
                    BoxDebugEventBus.publish("wine-runtime-update-complete", payload("source", layer.source));
                    return true;
                }
                throw new IllegalStateException("wine-runtime layer is missing");
            }
            catch (Exception e) {
                Log.e(TAG, "Wine runtime update failed", e);
                BoxDebugEventBus.publish("wine-runtime-update-failed", payload("message", e.toString()));
                return false;
            }
        }
    }

    boolean installWineRuntimeCandidate(BoxSpec candidate, BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            File candidateDir = null;
            try {
                requireIdleWineUpdate();
                if (runtime.isPrefixProtected()) {
                    throw new IllegalStateException(
                            "protected_prefix: Wine runtime update is disabled");
                }
                BoxSpec active = runtime.getSpec();
                validateSpec(active);
                validateSpec(candidate);
                BoxSpec.Layer activeLayer = findLayer(active, "wine-runtime");
                BoxSpec.Layer layer = findLayer(candidate, "wine-runtime");
                if (activeLayer == null || layer == null) {
                    throw new IllegalStateException("wine-runtime layer is missing");
                }
                requireInPlaceWineCandidate(active, candidate);
                if (runtime.getRuntimeOverlay().length() != 0) {
                    throw new IllegalStateException(
                            "runtime_overlay_active: clear the runtime overlay before updating Wine");
                }
                candidateDir = new File(
                        ImageFs.find(context).getInstalledWineDir(),
                        BoxRuntime.resolveRuntimeIdentifier(layer));
                recoverPendingWineRuntimeCandidate(candidateDir);
                active = runtime.getSpec();
                activeLayer = findLayer(active, "wine-runtime");
                if (activeLayer == null) {
                    throw new IllegalStateException("wine-runtime layer is missing");
                }
                requireInPlaceWineCandidate(active, candidate);
                if (sameLayerIdentity(activeLayer, layer)) {
                    requireInstalledWineRuntimeMatches(activeLayer);
                    return true;
                }
                recoverWineRuntimeTransaction(candidateDir, activeLayer.checksum);
                if (!candidateDir.isDirectory() || FileUtils.isSymlink(candidateDir)) {
                    throw new IllegalStateException(
                            "Active Wine runtime is missing or unsafe: " +
                                    candidateDir);
                }
                requireInstalledWineRuntimeMatches(activeLayer);
                writeWineRuntimeMarker(candidateDir, activeLayer.checksum);
                beginWineRuntimeCandidateTransaction(
                        candidateDir,
                        active,
                        layer);
                installWineRuntime(
                        layer,
                        listener,
                        100,
                        // Prefix deltas are activated as metadata here, but
                        // remain unapplied until the explicit reconcile call.
                        false,
                        true,
                        activeLayer.checksum);
                return true;
            }
            catch (Exception e) {
                if (candidateDir != null) {
                    try {
                        recoverPendingWineRuntimeCandidate(candidateDir);
                    }
                    catch (Exception recoveryFailure) {
                        e.addSuppressed(recoveryFailure);
                    }
                }
                Log.e(TAG, "Wine runtime candidate install failed", e);
                BoxDebugEventBus.publish(
                        "wine-runtime-update-failed",
                        payload("message", e.toString()));
                return false;
            }
        }
    }

    boolean recordWineRuntimeCandidate(BoxSpec candidate) {
        synchronized (BoxInstaller.class) {
            try {
                requireIdleWineUpdate();
                if (!wineRuntimeSpecMatches(candidate)) {
                    throw new IllegalStateException(
                            "Active Box spec does not select the Wine runtime candidate");
                }
                BoxSpec.Layer layer = findLayer(candidate, "wine-runtime");
                if (layer == null) {
                    throw new IllegalStateException("wine-runtime layer is missing");
                }
                File wineDir = new File(
                        ImageFs.find(context).getInstalledWineDir(),
                        BoxRuntime.resolveRuntimeIdentifier(layer));
                verifyPreparedWineRuntime(wineDir);
                if (!wineRuntimeMarkerMatches(wineDir, layer.checksum)) {
                    throw new IllegalStateException(
                            "Wine runtime candidate identity marker mismatch");
                }
                noteLayerInstalled(layer, false, true);
                BoxDebugEventBus.publish(
                        "wine-runtime-update-complete",
                        payload("source", layer.source));
                return true;
            }
            catch (Exception e) {
                Log.e(TAG, "Wine runtime candidate record failed", e);
                return false;
            }
        }
    }

    void finalizeWineRuntimeCandidate(BoxSpec candidate) throws Exception {
        synchronized (BoxInstaller.class) {
            requireIdleWineUpdate();
            if (!wineRuntimeSpecMatches(candidate)) {
                throw new IllegalStateException(
                        "Active Box spec does not select the Wine runtime candidate");
            }
            if (runtime.getRuntimeOverlay().length() != 0) {
                throw new IllegalStateException(
                        "runtime_overlay_active: clear the runtime overlay before updating Wine");
            }
            BoxSpec.Layer layer = findLayer(candidate, "wine-runtime");
            if (layer == null) {
                throw new IllegalStateException("wine-runtime layer is missing");
            }
            File wineDir = new File(
                    ImageFs.find(context).getInstalledWineDir(),
                    BoxRuntime.resolveRuntimeIdentifier(layer));
            verifyPreparedWineRuntime(wineDir);
            if (!wineRuntimeMarkerMatches(wineDir, layer.checksum)) {
                throw new IllegalStateException(
                        "Wine runtime candidate identity marker mismatch");
            }
            JSONObject journal = loadWineRuntimeCandidateJournal(wineDir);
            if (journal == null) return;
            requireJournalCandidateIdentity(journal, layer);
            JSONObject installed = findInstalledLayerRecord(layer.type);
            if (!sameLayerIdentity(layer, installed)) {
                throw new IllegalStateException(
                        "Wine runtime installed record was not committed");
            }
            if (!criticalFileRecordsExactlyMatch(
                    installed.optJSONArray("criticalFiles"),
                    hashCriticalFiles(layer))) {
                throw new IllegalStateException(
                        "Wine runtime installed critical hashes were not committed");
            }
            requireIdleWineUpdate();
            writeWineRuntimeCandidateCommit(wineDir, layer.checksum);
            File backup = wineRuntimeBackup(wineDir);
            if (backup.exists() && !FileUtils.delete(backup)) {
                throw new IllegalStateException(
                        "Unable to clean committed Wine runtime backup " + backup);
            }
            deleteWineRuntimeCandidateJournal(wineDir);
            deleteWineRuntimeCandidateCommit(wineDir);
        }
    }

    boolean rollbackWineRuntimeCandidate(BoxSpec baseline) {
        synchronized (BoxInstaller.class) {
            try {
                requireIdleWineUpdate();
                BoxSpec.Layer layer = findLayer(baseline, "wine-runtime");
                if (layer == null) {
                    throw new IllegalStateException("wine-runtime layer is missing");
                }
                File wineDir = new File(
                        ImageFs.find(context).getInstalledWineDir(),
                        BoxRuntime.resolveRuntimeIdentifier(layer));
                JSONObject journal = loadWineRuntimeCandidateJournal(wineDir);
                if (journal != null) restoreWineRuntimeCandidateTransaction(wineDir, journal);
                else {
                    rollbackWineRuntimeTransaction(wineDir, layer.checksum);
                    if (!wineRuntimeMarkerMatches(wineDir, layer.checksum)) {
                        throw new IllegalStateException(
                                "Wine runtime baseline backup is unavailable");
                    }
                    deleteWineRuntimeCandidateCommit(wineDir);
                }
                verifyPreparedWineRuntime(wineDir);
                return true;
            }
            catch (Exception error) {
                Log.e(TAG, "Unable to roll back Wine runtime candidate", error);
                return false;
            }
        }
    }

    /** Records the post-wineboot identity of an existing persistent prefix. */
    public boolean reconcileExistingPrefix(BoxInstallListener listener) {
        synchronized (BoxInstaller.class) {
            BoxSpec spec = runtime.getSpec();
            try {
                requireIdleWineUpdate();
                if (runtime.isPrefixProtected()) {
                    throw new IllegalStateException("protected_prefix: prefix reconcile is disabled");
                }
                validateSpec(spec);
                if (!runtime.getContainerRootDir().isDirectory()) {
                    throw new IllegalStateException("persistent prefix is missing");
                }
                for (BoxSpec.Layer layer : spec.layers) {
                    if (!"prefix-template".equals(layer.type)) continue;
                    repairPrefixBuiltinsFromRuntime(layer, runtime.getContainerRootDir(), false);
                    noteLayerInstalled(layer, false);
                    publish(listener, "prefix-template", 100, 100, "recorded post-wineboot prefix identity");
                    BoxDebugEventBus.publish("prefix-reconcile-complete", payload("source", layer.source));
                    return true;
                }
                throw new IllegalStateException("prefix-template layer is missing");
            }
            catch (Exception e) {
                Log.e(TAG, "Prefix reconcile failed", e);
                BoxDebugEventBus.publish("prefix-reconcile-failed", payload("message", e.toString()));
                return false;
            }
        }
    }

    private boolean installLocked(boolean dryRun, BoxInstallListener listener) {
        BoxSpec spec = runtime.getSpec();
        BoxDebugEventBus.publish("install-start", payload("dryRun", dryRun));
        if (listener != null) listener.onProgress("prepare", 1, 0, "Preparing box install");

        try {
            if (runtime.isPrefixProtected()) {
                throw new IllegalStateException("protected_prefix: Box reinstall is disabled");
            }
            validateSpec(spec);
            if (!dryRun) {
                requireIdleWineUpdate();
                BoxSpec bundled = BoxSpec.fromJsonString(FileUtils.readString(context, BoxSpec.getBundledAssetPath()));
                if (!isAllowedPublicInitialPayload(spec.payload, bundled.payload)) {
                    throw new IllegalStateException("initial_install_requires_public_directory_payload");
                }
                BoxSpec.Layer base = findLayer(spec, "base-imagefs");
                if (base == null || !"replace".equals(base.replacePolicy) || !"/".equals(base.target)) {
                    throw new IllegalStateException("initial_install_requires_replaceable_bundled_imagefs");
                }
                BoxInitialInstallGuard.beginOrResume(
                        ImageFs.find(context).getRootDir(),
                        new File(BoxPaths.getBoxDir(context), "initial-install-owner"),
                        BoxPaths.getSpecFile(context), runtime.isInstalled());
            }

            int totalSteps = spec.layers.size() + 2;
            int step = 0;
            BoxSpec.Layer deferredPrefixLayer = null;

            for (BoxSpec.Layer layer : spec.layers) {
                step++;
                publish(listener, layer.type, progress(step, totalSteps), 0, layer.source);
                if ("prefix-template".equals(layer.type)) {
                    deferredPrefixLayer = layer;
                }
                else {
                    installLayer(spec, layer, dryRun, listener, progress(step, totalSteps));
                    noteLayerInstalled(layer, dryRun);
                    if ("wine-runtime".equals(layer.type) && deferredPrefixLayer != null) {
                        installLayer(spec, deferredPrefixLayer, dryRun, listener, progress(step, totalSteps));
                        noteLayerInstalled(deferredPrefixLayer, dryRun);
                        deferredPrefixLayer = null;
                    }
                }
            }

            if (deferredPrefixLayer != null) {
                installLayer(spec, deferredPrefixLayer, dryRun, listener, progress(step, totalSteps));
                noteLayerInstalled(deferredPrefixLayer, dryRun);
            }

            step++;
            publish(listener, "payload", progress(step, totalSteps), 0, spec.payload.source);
            installPayload(spec, dryRun, listener, progress(step, totalSteps));

            if (!dryRun) {
                Container container = runtime.getOrCreateContainer();
                runtime.configureContainer(container);
                linkContainer();
                WineUtils.createDosdevicesSymlinks(container);
                prepareNativeDxEnvironment();
                JSONObject state = runtime.getState();
                state.put("installed", true);
                state.put("installFinishedAt", System.currentTimeMillis());
                runtime.saveState(state);
                try {
                    BoxInitialInstallGuard.complete(new File(BoxPaths.getBoxDir(context), "initial-install-owner"));
                } catch (IOException cleanupError) {
                    Log.w(TAG, "Unable to clean initial-install owner marker", cleanupError);
                }
            }

            publish(listener, dryRun ? "dry-run-complete" : "complete", 100, 100, spec.displayName);
            BoxDebugEventBus.publish("install-complete", payload("dryRun", dryRun));
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "Box install failed", e);
            recordFailure(e);
            BoxDebugEventBus.publish("install-failed", payload("message", e.toString()));
            return false;
        }
    }

    public JSONArray reconcile() {
        JSONArray result = new JSONArray();
        BoxSpec spec = runtime.getSpec();
        ImageFs imageFs = ImageFs.find(context);
        if (!imageFs.isValid()) result.put("imagefs missing or invalid");
        if (!runtime.getContainerRootDir().isDirectory()) result.put("container root missing");
        File payloadDir = BoxPaths.getPayloadDir(spec);
        if ("archive".equals(spec.payload.kind) && !payloadDir.isDirectory()) result.put("payload extract dir missing");
        return result;
    }

    private void installLayer(BoxSpec spec, BoxSpec.Layer layer, boolean dryRun, BoxInstallListener listener, int overallProgress) throws Exception {
        switch (layer.type) {
            case "base-imagefs":
                if (!dryRun) installBaseImageFs(layer, listener, overallProgress);
                break;
            case "prefix-template":
                if (!dryRun) installPrefixTemplate(spec, layer, listener, overallProgress);
                break;
            case "wine-runtime":
                if (!dryRun) installWineRuntime(
                        layer,
                        listener,
                        overallProgress,
                        true,
                        false,
                        "");
                break;
            case "input-bridge-stack":
                if (!dryRun) installInputBridgeStack(layer, listener, overallProgress);
                break;
            case "graphics-wrapper-stack":
                if (!dryRun) installGraphicsWrapperStack(layer, listener, overallProgress);
                break;
            case "frame-generation-stack":
                if (!dryRun) installFrameGenerationStack(layer, listener, overallProgress);
                break;
            case "dx-wrapper-stack":
                if (!dryRun) installDxWrapperStack(layer, listener, overallProgress);
                break;
            case ManagedDxProfile.NATIVE_LAYER_TYPE:
                if (!dryRun) installNativeDxWrapperStack(layer, listener, overallProgress);
                break;
            case "gpu-driver-stack":
                if (!dryRun) installGpuDriverStack(spec, layer, listener, overallProgress);
                break;
            case "cpu-emu-stack":
                if (!dryRun) installCpuEmuStack(layer, listener, overallProgress);
                break;
            default:
                throw new IllegalArgumentException("Unsupported layer type: " + layer.type);
        }
    }

    private void installBaseImageFs(BoxSpec.Layer layer, BoxInstallListener listener, int overallProgress) throws Exception {
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        if ("preserve".equals(layer.replacePolicy) && rootDir.isDirectory()) {
            publish(listener, "base-imagefs", overallProgress, 100, "preserved existing imagefs");
            return;
        }
        String assetPath = stripAssetPrefix(layer.source);
        verifyAssetChecksum(assetPath, layer.checksum);
        if ((rootDir.exists() || FileUtils.isSymlink(rootDir)) && !FileUtils.delete(rootDir)) {
            throw new IllegalStateException("Failed to remove owned partial imagefs");
        }
        if (!rootDir.mkdirs() && !rootDir.isDirectory()) {
            throw new IllegalStateException("Failed to create imagefs");
        }
        publish(listener, "base-imagefs", overallProgress, 20, rootDir.getAbsolutePath());
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, assetPath, rootDir)) {
            throw new IllegalStateException("Failed to extract base imagefs");
        }
        ImageFs.ensureRootfsSymlink(context);
        File etcLink = new File(rootDir, "etc");
        if (!etcLink.exists()) FileUtils.symlink("usr/etc", etcLink.getPath());
        File libLink = new File(rootDir, "lib");
        if (!libLink.exists()) FileUtils.symlink("usr/lib", libLink.getPath());
        File lib64Link = new File(rootDir, "lib64");
        if (!lib64Link.exists()) FileUtils.symlink("usr/lib", lib64Link.getPath());
        File varLink = new File(rootDir, "var");
        if (!varLink.exists()) FileUtils.symlink("usr/var", varLink.getPath());
        File runDir = new File(rootDir, "run");
        if (!runDir.exists()) runDir.mkdirs();
        imageFs.createImgVersionFile(ImageFsInstaller.LATEST_VERSION);
        publish(listener, "base-imagefs", overallProgress, 100, "imagefs ready");
    }

    private void installPrefixTemplate(BoxSpec spec, BoxSpec.Layer layer, BoxInstallListener listener, int overallProgress) throws Exception {
        File containerDir = runtime.getContainerRootDir();
        if ("preserve".equals(layer.replacePolicy) && containerDir.isDirectory()) {
            publish(listener, "prefix-template", overallProgress, 100, "preserved existing prefix");
            return;
        }
        FileUtils.delete(containerDir);
        if (!containerDir.mkdirs()) throw new IllegalStateException("Failed to create container root");
        Container container = runtime.getOrCreateContainer();
        runtime.configureContainer(container);
        String assetPath = stripAssetPrefix(layer.source);
        verifyAssetChecksum(assetPath, layer.checksum);
        ContainerManager manager = new ContainerManager(context);
        boolean ok = manager.extractContainerPatternFile(assetPath, container.getWineVersion(), containerDir, null);
        if (!ok) throw new IllegalStateException("Failed to extract prefix template");

        // Common prefix files shared across wine versions (winhandler.exe, wfm.exe, Fonts, icons).
        // The box launcher invokes winhandler-lite.exe to run the desktop shell, so without these the
        // desktop/explorer never starts (failed to open explorer.exe / sysarm32 rundll32 c0000135).
        // Mirrors XServerDisplayActivity.applyGeneralPatches()/ensureWinHandlerLiteInstalled(), but done
        // at install time so the files exist before the first launch (the launch-time path runs on a
        // background thread after wine has already started).
        //
        // container_pattern_common.tzst is rooted at home/xuser/.wine/...; the box prefix lives at
        // containerDir (home/xuser-box) and home/xuser is only symlinked -> xuser-box at launch time
        // (not yet during install). So remap each entry's leading "home/xuser/" to extract directly
        // into containerDir (.wine/...), rather than creating a stray real home/xuser dir that would
        // block the launch-time symlink and split the prefix.
        final File commonDest = containerDir;
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "container_pattern_common.tzst", containerDir, (file, size) -> {
            String rel = file.getAbsolutePath().substring(commonDest.getAbsolutePath().length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            final String prefix = "home/xuser/";
            File target;
            if (rel.equals("home") || rel.equals("home/") || rel.equals("home/xuser")) return null;
            else if (rel.startsWith(prefix)) target = new File(commonDest, rel.substring(prefix.length()));
            else if (rel.startsWith("home/")) return null; // skip other home/* roots we don't want duplicated
            else target = file;
            // The proton prefix-template lays down some of these top-level windows/*.exe as DANGLING
            // builtin symlinks (e.g. notepad.exe). Writing a regular file through a dangling symlink
            // fails and aborts the whole extract (dropping wfm.exe/winhandler.exe which come after).
            // Delete any pre-existing symlink or regular file at the target so it overwrites cleanly;
            // never touch real directories (size 0 includes dir entries).
            if (FileUtils.isSymlink(target) || (target.isFile())) target.delete();
            return target;
        });

        File winhandlerLite = new File(containerDir, ".wine/drive_c/windows/winhandler-lite.exe");
        FileUtils.copy(context, "winhandler/winhandler-lite.exe", winhandlerLite);
        FileUtils.chmod(winhandlerLite, 0771);
        syncCommonCjkFonts();

        // The template may have been initialized against a different Wine data
        // directory before the common font payload was overlaid.  Force the
        // first Wine startup to reconcile it with the selected runtime's
        // wine.inf (GuestProgramLauncherComponent supplies WINEDATADIR).
        // This installs the matching Fonts registry entries instead of leaving
        // CEF with a system UI font name that DWrite cannot enumerate.
        File updateTimestamp = new File(containerDir, ".wine/.update-timestamp");
        if (updateTimestamp.exists() && !updateTimestamp.delete()) {
            Log.w(TAG, "Unable to invalidate prefix update timestamp: " + updateTimestamp.getAbsolutePath());
        }

        // The prefix-template ships top-level windows builtins (explorer.exe, hh.exe, regedit.exe,
        // winhlp32.exe) as relative symlinks "../../../../lib/wine/<arch>/<name>", which resolve to
        // home/xuser-box/.wine/lib/wine/... — a path that does NOT exist in this box layout (the wine
        // builtins live in opt/<runtimeId>/lib/wine/...). So wine's launch of C:\windows\explorer.exe
        // follows a dangling symlink -> "failed to open explorer.exe" and the desktop never starts.
        // Repoint any dangling "*/lib/wine/..." builtin symlink at the real opt/<runtimeId> dir. The
        // target need not exist yet (wine-runtime layer installs after this one); symlinks are fine.
        String runtimeId = BoxRuntime.resolveRuntimeIdentifier(layer);
        File windowsDir = new File(containerDir, ".wine/drive_c/windows");
        File[] winFiles = windowsDir.listFiles();
        if (winFiles != null) {
            for (File f : winFiles) {
                if (!FileUtils.isSymlink(f)) continue;
                String link = FileUtils.readSymlink(f);
                if (link == null) continue;
                int idx = link.indexOf("/lib/wine/");
                if (idx == -1 || f.exists()) continue; // only repair dangling lib/wine builtin links
                String archAndName = link.substring(idx + "/lib/wine/".length()); // e.g. aarch64-windows/explorer.exe
                String newTarget = "../../../../../opt/" + runtimeId + "/lib/wine/" + archAndName;
                f.delete();
                FileUtils.symlink(newTarget, f.getAbsolutePath());
            }
        }

        repairPrefixBuiltinsFromRuntime(layer, containerDir, true);
        File wineDir = new File(
                ImageFs.find(context).getInstalledWineDir(),
                BoxRuntime.resolveRuntimeIdentifier(layer));
        syncBundledWineFonts(wineDir);
        publish(listener, "prefix-template", overallProgress, 100, containerDir.getAbsolutePath());
    }

    private void repairPrefixBuiltinsFromRuntime(BoxSpec.Layer layer, File containerDir, boolean freshPrefix) throws Exception {
        String runtimeId = BoxRuntime.resolveRuntimeIdentifier(layer);
        File wineDir = new File(ImageFs.find(context).getInstalledWineDir(), runtimeId);
        boolean isArm64EC = runtimeId != null && runtimeId.contains("arm64ec");
        if (!wineDir.isDirectory()) {
            Log.w(TAG, "repairPrefixBuiltinsFromRuntime: missing wineDir runtime=" + runtimeId +
                    " wineDir=" + wineDir.getAbsolutePath());
            return;
        }

        File windowsDir = new File(containerDir, ".wine/drive_c/windows");
        File system32Dir = new File(windowsDir, "system32");
        File sysarm32Dir = new File(windowsDir, "sysarm32");
        File syswow64Dir = new File(windowsDir, "syswow64");
        File system32DriversDir = new File(system32Dir, "drivers");
        boolean hasWinePrefixDelta = layer.winePrefixDeltaManifest != null &&
                !layer.winePrefixDeltaManifest.isEmpty();

        int copied = 0;
        WinePrefixDeltaReconciler.Result deltaResult = null;
        java.util.Set<String> winebootOptionalPaths = new java.util.HashSet<>();
        if (hasWinePrefixDelta) {
            List<WinePrefixDeltaReconciler.Entry> entries =
                    loadWinePrefixDeltaEntries(layer, runtimeId);
            for (WinePrefixDeltaReconciler.Entry entry : entries) {
                if (entry.winebootMayRemove) winebootOptionalPaths.add(entry.prefixPath);
            }
            requireIdleWineUpdate();
            if (freshPrefix) {
                WinePrefixDeltaReconciler.seedFreshMissing(wineDir, windowsDir, entries);
            }
            // Preflight and apply the guarded delta before any legacy
            // copy-if-missing repair. An unknown or missing manifest target
            // therefore fails without touching the existing prefix.
            deltaResult = WinePrefixDeltaReconciler.reconcile(wineDir, windowsDir, entries);
            copied += deltaResult.replaced;
        }

        if (isArm64EC) {
            if (!hasWinePrefixDelta) {
                copied += syncWineBuiltin(wineDir, "aarch64-windows", "ntdll.dll", system32Dir);
            }
            copied += copyWineBuiltinsIfMissing(wineDir, isArm64EC, "aarch64-windows", system32Dir, winebootOptionalPaths);
            // ARM64EC's native ARM helper processes (for example rundll32) are
            // resolved through C:\windows\sysarm32.  The generated prefix pack
            // does not create that directory, so mirror the pure AArch64 PE
            // builtins there as part of the same immutable runtime install.
            copied += copyWineBuiltinsIfMissing(wineDir, false, "aarch64-windows", sysarm32Dir, winebootOptionalPaths);
            if (!hasWinePrefixDelta) {
                copied += syncWineDrivers(wineDir, "aarch64-windows", system32DriversDir);
            }
        }
        else {
            copied += copyWineBuiltinsIfMissing(wineDir, isArm64EC, "x86_64-windows", system32Dir, winebootOptionalPaths);
            if (!hasWinePrefixDelta) {
                copied += syncWineDrivers(wineDir, "x86_64-windows", system32DriversDir);
            }
        }
        copied += copyWineBuiltinsIfMissing(wineDir, isArm64EC, "i386-windows", syswow64Dir, winebootOptionalPaths);

        Log.i(TAG, "repairPrefixBuiltinsFromRuntime: copied=" + copied +
                " delta_replaced=" + (deltaResult != null ? deltaResult.replaced : 0) +
                " delta_current=" + (deltaResult != null ? deltaResult.alreadyCurrent : 0) +
                " runtime=" + runtimeId +
                " wineDir=" + wineDir.getAbsolutePath() +
                " system32_kernel32=" + new File(system32Dir, "kernel32.dll").isFile() +
                " system32_ntdll=" + new File(system32Dir, "ntdll.dll").isFile() +
                " sysarm32_rundll32=" + new File(sysarm32Dir, "rundll32.exe").isFile() +
                " syswow64_kernel32=" + new File(syswow64Dir, "kernel32.dll").isFile() +
                " mountmgr=" + new File(system32DriversDir, "mountmgr.sys").isFile());
    }

    List<WinePrefixDeltaReconciler.Entry> loadWinePrefixDeltaEntries(
            BoxSpec.Layer layer,
            String runtimeId) throws Exception {
        String manifestAsset = stripAssetPrefix(layer.winePrefixDeltaManifest);
        if (!isSafeWinePrefixDeltaManifestAsset(manifestAsset)) {
            throw new IllegalArgumentException(
                    "Unsafe Wine prefix delta manifest asset: " + layer.winePrefixDeltaManifest);
        }
        if (layer.winePrefixDeltaChecksum == null ||
                !layer.winePrefixDeltaChecksum.matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Wine prefix delta manifest checksum is missing");
        }
        verifyAssetChecksum(manifestAsset, layer.winePrefixDeltaChecksum);

        JSONObject manifest = new JSONObject(FileUtils.readString(context, manifestAsset));
        if (manifest.optInt("schemaVersion", 0) != 1) {
            throw new IllegalArgumentException("Unsupported Wine prefix delta manifest schema");
        }
        String manifestRuntimeId = manifest.optString("runtimeIdentifier", "");
        if (runtimeId == null || runtimeId.isEmpty() || !runtimeId.equals(manifestRuntimeId)) {
            throw new IllegalArgumentException(
                    "Wine prefix delta runtime mismatch: expected=" + runtimeId +
                            " manifest=" + manifestRuntimeId);
        }

        JSONArray manifestEntries = manifest.optJSONArray("entries");
        if (manifestEntries == null || manifestEntries.length() == 0) {
            throw new IllegalArgumentException("Wine prefix delta manifest has no entries");
        }
        ArrayList<WinePrefixDeltaReconciler.Entry> entries = new ArrayList<>();
        for (int i = 0; i < manifestEntries.length(); i++) {
            JSONObject entry = manifestEntries.optJSONObject(i);
            if (entry == null) {
                throw new IllegalArgumentException("Invalid Wine prefix delta entry at index " + i);
            }
            ArrayList<String> alternates = new ArrayList<>();
            if (entry.has("baselineSha256Alternates")) {
                JSONArray values = entry.optJSONArray("baselineSha256Alternates");
                if (values == null) throw new IllegalArgumentException("Invalid Wine prefix baseline alternatives");
                for (int j = 0; j < values.length(); j++) {
                    if (!(values.get(j) instanceof String)) {
                        throw new IllegalArgumentException("Invalid Wine prefix baseline alternative");
                    }
                    alternates.add(values.getString(j));
                }
            }
            boolean optional = false;
            if (entry.has("winebootMayRemove")) {
                Object value = entry.get("winebootMayRemove");
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("Invalid Wineboot optional flag");
                optional = (Boolean) value;
            }
            if (optional && !isTrustedWinebootOptionalManifest(layer.winePrefixDeltaManifest,
                    layer.winePrefixDeltaChecksum, runtimeId)) {
                throw new IllegalArgumentException("Untrusted Wineboot optional manifest");
            }
            entries.add(new WinePrefixDeltaReconciler.Entry(
                    entry.optString("prefixPath", ""),
                    entry.optString("runtimePath", ""),
                    entry.optString("baselineSha256", ""),
                    entry.optString("candidateSha256", ""), alternates, optional));
        }
        return entries;
    }

    static boolean isTrustedWinebootOptionalManifest(String path, String checksum, String runtimeId) {
        return PUBLIC_WINEBOOT_OPTIONAL_MANIFEST.equals(path) &&
                PUBLIC_WINEBOOT_OPTIONAL_MANIFEST_SHA256.equalsIgnoreCase(checksum) &&
                "proton-11.0-1-arm64ec-wowbox-openfonts-9ac94fff".equals(runtimeId);
    }

    private int copyWineBuiltinsIfMissing(File wineDir, boolean isArm64EC, String srcName, File dstDir,
                                         java.util.Set<String> winebootOptionalPaths) {
        File srcDir = new File(wineDir, "lib/wine/" + srcName);
        File[] srcFiles = srcDir.listFiles(file -> file != null && file.isFile());
        if (srcFiles == null || srcFiles.length == 0) {
            Log.w(TAG, "repairPrefixBuiltinsFromRuntime: missing src dir " + srcDir.getAbsolutePath());
            return 0;
        }

        if (!dstDir.isDirectory() && !dstDir.mkdirs()) {
            Log.w(TAG, "repairPrefixBuiltinsFromRuntime: failed to create dst dir " + dstDir.getAbsolutePath());
            return 0;
        }

        int copied = 0;
        for (File file : srcFiles) {
            String name = file.getName();
            if (winebootOptionalPaths.contains(dstDir.getName() + "/" + name)) continue;
            File srcFile = file;

            if ("iexplore.exe".equals(name) && isArm64EC && "aarch64-windows".equals(srcName)) {
                File fallback = new File(wineDir, "lib/wine/i386-windows/iexplore.exe");
                if (fallback.isFile()) srcFile = fallback;
            }
            if ("winedevice-x64.exe".equals(name) && !isArm64EC &&
                    "aarch64-windows".equals(srcName)) continue;
            if ("tabtip.exe".equals(name) || "icu.dll".equals(name)) continue;

            File dstFile = new File(dstDir, name);
            if (dstFile.exists()) continue;
            if (FileUtils.copy(srcFile, dstFile)) copied++;
        }
        return copied;
    }

    private int syncWineBuiltin(File wineDir, String srcName, String filename, File dstDir) {
        File srcFile = new File(wineDir, "lib/wine/" + srcName + "/" + filename);
        if (!srcFile.isFile()) throw new IllegalStateException("Missing Wine builtin " + srcFile);
        if (!dstDir.isDirectory() && !dstDir.mkdirs()) {
            throw new IllegalStateException("Unable to create Wine builtin directory " + dstDir);
        }

        File dstFile = new File(dstDir, filename);
        String expectedSha256 = sha256(srcFile);
        if (expectedSha256.isEmpty()) throw new IllegalStateException("Unable to hash Wine builtin " + srcFile);
        if (dstFile.isFile() && expectedSha256.equalsIgnoreCase(sha256(dstFile))) return 0;

        File prepared = new File(dstFile.getAbsolutePath() + ".wine-builtin-new");
        File backup = new File(dstFile.getAbsolutePath() + ".wine-builtin-backup");
        FileUtils.delete(prepared);
        FileUtils.delete(backup);

        boolean activated = false;
        try {
            if (!FileUtils.copy(srcFile, prepared) ||
                    !expectedSha256.equalsIgnoreCase(sha256(prepared))) {
                throw new IllegalStateException("Unable to prepare Wine builtin " + dstFile);
            }
            if (dstFile.exists() && !dstFile.renameTo(backup)) {
                throw new IllegalStateException("Unable to back up Wine builtin " + dstFile);
            }
            if (!prepared.renameTo(dstFile)) {
                if (backup.exists()) backup.renameTo(dstFile);
                throw new IllegalStateException("Unable to activate Wine builtin " + dstFile);
            }
            activated = true;
            if (!expectedSha256.equalsIgnoreCase(sha256(dstFile))) {
                throw new IllegalStateException("Activated Wine builtin hash mismatch " + dstFile);
            }
            FileUtils.delete(backup);
            return 1;
        }
        catch (RuntimeException e) {
            FileUtils.delete(prepared);
            if (activated) FileUtils.delete(dstFile);
            if (backup.exists() && !backup.renameTo(dstFile)) {
                Log.e(TAG, "Unable to restore Wine builtin backup " + dstFile);
            }
            throw e;
        }
    }

    private int syncWineDrivers(File wineDir, String srcName, File dstDriversDir) {
        File srcDir = new File(wineDir, "lib/wine/" + srcName);
        File[] srcFiles = srcDir.listFiles(file -> file != null && file.isFile() && file.getName().endsWith(".sys"));
        if (srcFiles == null || srcFiles.length == 0) return 0;

        if (!dstDriversDir.isDirectory() && !dstDriversDir.mkdirs()) {
            throw new IllegalStateException("Unable to create Wine driver directory " + dstDriversDir);
        }

        int copied = 0;
        for (File srcFile : srcFiles) {
            File dstFile = new File(dstDriversDir, srcFile.getName());
            String expectedSha256 = sha256(srcFile);
            if (expectedSha256.isEmpty()) {
                throw new IllegalStateException("Unable to hash Wine driver " + srcFile);
            }
            if (dstFile.isFile() && expectedSha256.equalsIgnoreCase(sha256(dstFile))) continue;

            File prepared = new File(dstFile.getAbsolutePath() + ".wine-driver-new");
            File backup = new File(dstFile.getAbsolutePath() + ".wine-driver-backup");
            FileUtils.delete(prepared);
            FileUtils.delete(backup);

            boolean activated = false;
            try {
                if (!FileUtils.copy(srcFile, prepared) ||
                        !expectedSha256.equalsIgnoreCase(sha256(prepared))) {
                    throw new IllegalStateException("Unable to prepare Wine driver " + dstFile);
                }
                if (dstFile.exists() && !dstFile.renameTo(backup)) {
                    throw new IllegalStateException("Unable to back up Wine driver " + dstFile);
                }
                if (!prepared.renameTo(dstFile)) {
                    if (backup.exists()) backup.renameTo(dstFile);
                    throw new IllegalStateException("Unable to activate Wine driver " + dstFile);
                }
                activated = true;
                if (!expectedSha256.equalsIgnoreCase(sha256(dstFile))) {
                    throw new IllegalStateException("Activated Wine driver hash mismatch " + dstFile);
                }
                copied++;
                FileUtils.delete(backup);
            }
            catch (RuntimeException e) {
                FileUtils.delete(prepared);
                if (activated) FileUtils.delete(dstFile);
                if (backup.exists() && !backup.renameTo(dstFile)) {
                    Log.e(TAG, "Unable to restore Wine driver backup " + dstFile);
                }
                throw e;
            }
            finally {
                FileUtils.delete(prepared);
            }
        }
        return copied;
    }

    private void installWineRuntime(
            BoxSpec.Layer layer,
            BoxInstallListener listener,
            int overallProgress,
            boolean syncPrefixFonts,
            boolean keepBackup,
            String rollbackChecksum) throws Exception {
        ImageFs imageFs = ImageFs.find(context);
        String assetPath = stripAssetPrefix(layer.source);
        Log.i(TAG, "installWineRuntime: assetPath=" + assetPath + " checksum=" + layer.checksum);
        verifyAssetChecksum(assetPath, layer.checksum);
        Log.i(TAG, "installWineRuntime: checksum OK");
        String wineId = BoxRuntime.resolveRuntimeIdentifier(layer);
        File wineDir = new File(imageFs.getInstalledWineDir(), wineId);
        if (FileUtils.isSymlink(wineDir)) {
            throw new IllegalStateException("Wine runtime destination is a symlink: " + wineDir);
        }
        File candidateJournal = wineRuntimeCandidateJournal(wineDir);
        if (!keepBackup &&
                (candidateJournal.exists() ||
                        FileUtils.isSymlink(candidateJournal) ||
                        new File(candidateJournal.getAbsolutePath() + ".pending").exists() ||
                        FileUtils.isSymlink(
                                new File(candidateJournal.getAbsolutePath() + ".pending")) ||
                        wineRuntimeCandidateCommit(wineDir).exists() ||
                        FileUtils.isSymlink(wineRuntimeCandidateCommit(wineDir)))) {
            throw new IllegalStateException(
                    "pending_wine_runtime_candidate: retry the targeted Wine update");
        }
        recoverWineRuntimeTransaction(
                wineDir,
                rollbackChecksum != null && !rollbackChecksum.isEmpty()
                        ? rollbackChecksum
                        : layer.checksum);
        if ("preserve".equals(layer.replacePolicy) && wineDir.isDirectory()) {
            File marker = wineRuntimeMarker(wineDir);
            if (FileUtils.isSymlink(marker)) {
                throw new IllegalStateException(
                        "Wine runtime marker is a symlink: " + marker);
            }
            if (marker.isFile() &&
                    !wineRuntimeMarkerMatches(wineDir, layer.checksum)) {
                throw new IllegalStateException(
                        "Preserved Wine runtime identity mismatch: " + wineDir);
            }
            verifyPreparedWineRuntime(wineDir);
            if (syncPrefixFonts) syncBundledWineFonts(wineDir);
            publish(listener, "wine-runtime", overallProgress, 100, "preserved existing runtime");
            return;
        }
        Log.i(TAG, "installWineRuntime: wineDir=" + wineDir.getAbsolutePath());
        File prepared = new File(wineDir.getAbsolutePath() + ".wine-runtime-new");
        File backup = new File(wineDir.getAbsolutePath() + ".wine-runtime-backup");
        if (prepared.exists() || backup.exists() ||
                FileUtils.isSymlink(prepared) || FileUtils.isSymlink(backup)) {
            throw new IllegalStateException(
                    "Stale Wine runtime transaction for " + wineId);
        }
        boolean mkOk = prepared.mkdirs();
        Log.i(TAG, "installWineRuntime: mkdirs=" + mkOk + " exists=" + wineDir.isDirectory() + " parent=" + wineDir.getParentFile().isDirectory());
        Log.i(TAG, "installWineRuntime: starting extract");
        boolean activated = false;
        try {
            if (!mkOk ||
                    !TarCompressorUtils.extract(
                            TarCompressorUtils.Type.XZ,
                            context,
                            assetPath,
                            prepared)) {
                throw new IllegalStateException("Failed to extract wine runtime");
            }
            prepareWineRuntimeLayout(prepared);
            verifyPreparedWineRuntime(prepared);

            requireIdleWineUpdate();
            if (wineDir.exists() && !wineDir.renameTo(backup)) {
                throw new IllegalStateException("Unable to back up Wine runtime " + wineDir);
            }
            if (!prepared.renameTo(wineDir)) {
                if (backup.exists()) backup.renameTo(wineDir);
                throw new IllegalStateException("Unable to activate Wine runtime " + wineDir);
            }
            activated = true;
            verifyPreparedWineRuntime(wineDir);
            writeWineRuntimeMarker(wineDir, layer.checksum);
            if (!keepBackup && backup.exists() && !FileUtils.delete(backup)) {
                // The candidate tree and its external identity marker are
                // committed. Leave the backup for the next recovery pass
                // instead of rolling back a verified candidate.
                Log.w(TAG, "Unable to clean Wine runtime backup " + backup);
            }

            Log.i(TAG, "installWineRuntime: extract complete wineDir=" +
                    wineDir.getAbsolutePath());
            if (syncPrefixFonts) syncBundledWineFonts(wineDir);
            publish(listener, "wine-runtime", overallProgress, 100, wineDir.getAbsolutePath());
        }
        catch (Exception e) {
            Log.e(TAG, "installWineRuntime: transaction failed assetPath=" +
                    assetPath + " wineDir=" + wineDir, e);
            if (activated && wineDir.exists() && !FileUtils.delete(wineDir)) {
                e.addSuppressed(new IllegalStateException(
                        "Unable to remove failed Wine runtime " + wineDir));
            }
            boolean restored = !activated && wineDir.isDirectory();
            if (backup.exists()) {
                if (!backup.renameTo(wineDir)) {
                    e.addSuppressed(new IllegalStateException(
                            "Unable to restore Wine runtime backup " + backup));
                }
                else restored = true;
            }
            if (restored && rollbackChecksum != null && !rollbackChecksum.isEmpty()) {
                try {
                    writeWineRuntimeMarker(wineDir, rollbackChecksum);
                }
                catch (Exception markerFailure) {
                    e.addSuppressed(markerFailure);
                }
            }
            throw e;
        }
        finally {
            if (prepared.exists() && !FileUtils.delete(prepared)) {
                Log.w(TAG, "Unable to remove prepared Wine runtime " + prepared);
            }
        }
    }

    private void requireIdleWineUpdate() {
        if (XServerDisplayActivity.hasActiveSession() ||
                WineSessionProcessController.getInstance().hasActiveWineProcesses()) {
            throw new IllegalStateException(
                    "active_wine_processes: stop the Box session before updating Wine");
        }
    }

    private static File wineRuntimeMarker(File wineDir) {
        File parent = wineDir.getParentFile();
        return new File(
                parent,
                "." + wineDir.getName() + ".winlator-runtime-asset.sha256");
    }

    private static boolean wineRuntimeMarkerMatches(File wineDir, String checksum) {
        String value = FileUtils.readString(wineRuntimeMarker(wineDir));
        return value != null && checksum != null && checksum.equalsIgnoreCase(value.trim());
    }

    static void writeWineRuntimeMarker(File wineDir, String checksum) throws Exception {
        File marker = wineRuntimeMarker(wineDir);
        if (FileUtils.isSymlink(marker)) {
            throw new IllegalStateException("Wine runtime marker is a symlink: " + marker);
        }
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write((checksum.toLowerCase(Locale.US) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        }
    }

    private static void prepareWineRuntimeLayout(File wineDir) {
        // The ARM64EC runtime archive omits bin/wine and bin/wine64; all bin/*
        // are symlinks to wine, but the target is missing. The actual binary
        // lives at lib/wine/aarch64-unix/wine.
        File binWine = new File(wineDir, "bin/wine");
        if (!binWine.exists()) {
            FileUtils.symlink("../lib/wine/aarch64-unix/wine", binWine.getAbsolutePath());
        }
        File binWine64 = new File(wineDir, "bin/wine64");
        if (!binWine64.exists()) FileUtils.symlink("wine", binWine64.getAbsolutePath());
    }

    private static void verifyPreparedWineRuntime(File wineDir) {
        String[] required = {
                "bin/wine",
                "bin/wineserver",
                "lib/wine/aarch64-unix/ntdll.so"
        };
        for (String path : required) {
            if (!new File(wineDir, path).exists()) {
                throw new IllegalStateException(
                        "Wine runtime critical file is missing: " + path);
            }
        }
    }

    static void recoverWineRuntimeTransaction(File wineDir, String checksum) {
        File prepared = wineRuntimePrepared(wineDir);
        File backup = wineRuntimeBackup(wineDir);
        File marker = wineRuntimeMarker(wineDir);
        if (FileUtils.isSymlink(prepared) ||
                FileUtils.isSymlink(backup) ||
                FileUtils.isSymlink(marker)) {
            throw new IllegalStateException("Unsafe Wine runtime transaction path");
        }

        if (backup.exists()) {
            boolean candidateCommitted = wineDir.isDirectory() &&
                    wineRuntimeMarker(wineDir).isFile() &&
                    wineRuntimeMarkerMatches(wineDir, checksum);
            if (candidateCommitted) {
                try {
                    verifyPreparedWineRuntime(wineDir);
                }
                catch (RuntimeException ignored) {
                    candidateCommitted = false;
                }
            }
            if (candidateCommitted) {
                if (!FileUtils.delete(backup)) {
                    throw new IllegalStateException(
                            "Unable to finalize Wine runtime transaction " + backup);
                }
            }
            else {
                if (wineDir.exists() && !FileUtils.delete(wineDir)) {
                    throw new IllegalStateException(
                            "Unable to remove incomplete Wine runtime " + wineDir);
                }
                if (!backup.renameTo(wineDir)) {
                    throw new IllegalStateException(
                            "Unable to restore Wine runtime transaction " + backup);
                }
                try {
                    writeWineRuntimeMarker(wineDir, checksum);
                }
                catch (Exception error) {
                    throw new IllegalStateException(
                            "Unable to restore Wine runtime identity marker", error);
                }
            }
        }
        if (prepared.exists() && !FileUtils.delete(prepared)) {
            throw new IllegalStateException(
                    "Unable to clean incomplete Wine runtime " + prepared);
        }
    }

    private static File wineRuntimePrepared(File wineDir) {
        return new File(wineDir.getAbsolutePath() + ".wine-runtime-new");
    }

    private static File wineRuntimeBackup(File wineDir) {
        return new File(wineDir.getAbsolutePath() + ".wine-runtime-backup");
    }

    private static File wineRuntimeCandidateJournal(File wineDir) {
        File parent = wineDir.getParentFile();
        return new File(
                parent,
                "." + wineDir.getName() + ".winlator-runtime-candidate.json");
    }

    private static File wineRuntimeCandidateCommit(File wineDir) {
        return new File(
                wineRuntimeCandidateJournal(wineDir).getAbsolutePath() + ".commit");
    }

    private void beginWineRuntimeCandidateTransaction(
            File wineDir,
            BoxSpec activeSpec,
            BoxSpec.Layer candidateLayer) throws Exception {
        JSONObject activeState = runtime.getState();
        JSONObject installedRecord =
                findInstalledLayerRecord(activeState, "wine-runtime");
        if (installedRecord == null) {
            throw new IllegalStateException("Installed Wine runtime record is missing");
        }
        File journalFile = wineRuntimeCandidateJournal(wineDir);
        File journalPending = new File(journalFile.getAbsolutePath() + ".pending");
        if (journalFile.exists() || FileUtils.isSymlink(journalFile)) {
            throw new IllegalStateException(
                    "Stale Wine runtime candidate journal " + journalFile);
        }
        if (FileUtils.isSymlink(journalPending) ||
                wineRuntimeCandidateCommit(wineDir).exists() ||
                FileUtils.isSymlink(wineRuntimeCandidateCommit(wineDir))) {
            throw new IllegalStateException(
                    "Unsafe Wine runtime candidate transaction path");
        }
        JSONObject journal = new JSONObject();
        journal.put("schemaVersion", 1);
        journal.put("phase", "prepared");
        journal.put("activeSpec", activeSpec.toJson());
        journal.put("installedRecord", new JSONObject(installedRecord.toString()));
        journal.put("candidateLayer", candidateLayer.toJson());
        AtomicFileSupport.writeUtf8(journalFile, journal.toString());
    }

    private JSONObject loadWineRuntimeCandidateJournal(File wineDir) throws Exception {
        File journalFile = wineRuntimeCandidateJournal(wineDir);
        if (!journalFile.exists()) return null;
        if (!journalFile.isFile() || FileUtils.isSymlink(journalFile)) {
            throw new IllegalStateException(
                    "Unsafe Wine runtime candidate journal " + journalFile);
        }
        JSONObject journal = new JSONObject(AtomicFileSupport.readUtf8(journalFile));
        if (journal.optInt("schemaVersion", 0) != 1 ||
                journal.optJSONObject("activeSpec") == null ||
                journal.optJSONObject("installedRecord") == null ||
                journal.optJSONObject("candidateLayer") == null) {
            throw new IllegalStateException(
                    "Invalid Wine runtime candidate journal " + journalFile);
        }
        return journal;
    }

    private static void requireJournalCandidateIdentity(
            JSONObject journal,
            BoxSpec.Layer candidateLayer) {
        JSONObject candidate = journal.optJSONObject("candidateLayer");
        if (!sameLayerIdentity(candidateLayer, candidate)) {
            throw new IllegalStateException(
                    "Wine runtime candidate journal identity mismatch");
        }
    }

    private void recoverPendingWineRuntimeCandidate(File wineDir) throws Exception {
        JSONObject journal = loadWineRuntimeCandidateJournal(wineDir);
        if (journal == null) {
            recoverOrphanedWineRuntimeCandidateCommit(wineDir);
            return;
        }
        if (wineRuntimeCandidateIsCommitted(wineDir, journal)) {
            File backup = wineRuntimeBackup(wineDir);
            if (backup.exists() && !FileUtils.delete(backup)) {
                throw new IllegalStateException(
                        "Unable to finalize recovered Wine runtime candidate " + backup);
            }
            deleteWineRuntimeCandidateJournal(wineDir);
            deleteWineRuntimeCandidateCommit(wineDir);
            return;
        }
        restoreWineRuntimeCandidateTransaction(wineDir, journal);
    }

    private void restoreWineRuntimeCandidateTransaction(
            File wineDir,
            JSONObject journal) throws Exception {
        JSONObject installedRecord = journal.getJSONObject("installedRecord");
        BoxSpec activeSpec = BoxSpec.fromJson(journal.getJSONObject("activeSpec"));
        validateSpec(activeSpec);
        BoxSpec.Layer baselineLayer = findLayer(activeSpec, "wine-runtime");
        if (baselineLayer == null || !sameLayerIdentity(baselineLayer, installedRecord)) {
            throw new IllegalStateException(
                    "Wine runtime candidate journal baseline mismatch");
        }
        String expectedId = BoxRuntime.resolveRuntimeIdentifier(baselineLayer);
        if (!expectedId.equals(wineDir.getName())) {
            throw new IllegalStateException(
                    "Wine runtime candidate journal target mismatch");
        }

        File backup = wineRuntimeBackup(wineDir);
        if (backup.exists()) {
            rollbackWineRuntimeTransaction(wineDir, baselineLayer.checksum);
        }
        else {
            verifyPreparedWineRuntime(wineDir);
            JSONArray currentCriticalFiles = hashCriticalFiles(baselineLayer);
            if (!wineRuntimeMarkerMatches(wineDir, baselineLayer.checksum) ||
                    !allCriticalFileRecordsPresent(currentCriticalFiles) ||
                    !criticalFileRecordsMatch(
                            installedRecord.optJSONArray("criticalFiles"),
                            currentCriticalFiles)) {
                throw new IllegalStateException(
                        "Wine runtime candidate backup is missing and baseline is not active");
            }
        }
        File failed = wineRuntimeFailed(wineDir);
        if (failed.exists() && !FileUtils.delete(failed)) {
            throw new IllegalStateException(
                    "Unable to clean failed Wine runtime " + failed);
        }
        restoreInstalledLayerRecord(installedRecord);
        JSONObject restoredRecord = findInstalledLayerRecord("wine-runtime");
        if (!sameLayerIdentity(baselineLayer, restoredRecord)) {
            throw new IllegalStateException(
                    "Unable to restore Wine runtime installed record");
        }
        runtime.activateSpecForLayerUpdate(activeSpec);
        deleteWineRuntimeCandidateJournal(wineDir);
    }

    private boolean wineRuntimeCandidateIsCommitted(
            File wineDir,
            JSONObject journal) throws Exception {
        BoxSpec.Layer candidate =
                BoxSpec.Layer.fromJson(journal.getJSONObject("candidateLayer"));
        if (!wineRuntimeCandidateCommitMatches(wineDir, candidate.checksum)) {
            return false;
        }
        if (!wineDir.getName().equals(BoxRuntime.resolveRuntimeIdentifier(candidate)) ||
                !wineRuntimeMarkerMatches(wineDir, candidate.checksum)) {
            return false;
        }
        BoxSpec.Layer active = findLayer(runtime.getSpec(), "wine-runtime");
        JSONObject installed = findInstalledLayerRecord("wine-runtime");
        return sameLayerIdentity(candidate, active) &&
                sameLayerIdentity(candidate, installed) &&
                criticalFileRecordsExactlyMatch(
                        installed.optJSONArray("criticalFiles"),
                        hashCriticalFiles(candidate));
    }

    private void recoverOrphanedWineRuntimeCandidateCommit(File wineDir)
            throws Exception {
        File commit = wineRuntimeCandidateCommit(wineDir);
        if (!commit.exists()) return;
        if (!commit.isFile() || FileUtils.isSymlink(commit)) {
            throw new IllegalStateException(
                    "Unsafe Wine runtime candidate commit " + commit);
        }
        BoxSpec.Layer active = findLayer(runtime.getSpec(), "wine-runtime");
        JSONObject installed = findInstalledLayerRecord("wine-runtime");
        if (active == null ||
                !wineRuntimeCandidateCommitMatches(wineDir, active.checksum) ||
                !wineRuntimeMarkerMatches(wineDir, active.checksum) ||
                !sameLayerIdentity(active, installed) ||
                !criticalFileRecordsExactlyMatch(
                        installed.optJSONArray("criticalFiles"),
                        hashCriticalFiles(active))) {
            throw new IllegalStateException(
                    "Orphaned Wine runtime candidate commit does not match active state");
        }
        deleteWineRuntimeCandidateCommit(wineDir);
    }

    private static void writeWineRuntimeCandidateCommit(
            File wineDir,
            String checksum) throws Exception {
        File commit = wineRuntimeCandidateCommit(wineDir);
        if (commit.exists() || FileUtils.isSymlink(commit)) {
            throw new IllegalStateException(
                    "Stale Wine runtime candidate commit " + commit);
        }
        try (FileOutputStream output = new FileOutputStream(commit)) {
            output.write((checksum.toLowerCase(Locale.US) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        }
    }

    private static boolean wineRuntimeCandidateCommitMatches(
            File wineDir,
            String checksum) {
        File commit = wineRuntimeCandidateCommit(wineDir);
        if (!commit.isFile() || FileUtils.isSymlink(commit)) return false;
        String value = FileUtils.readString(commit);
        return value != null && checksum != null &&
                checksum.equalsIgnoreCase(value.trim());
    }

    private static void deleteWineRuntimeCandidateCommit(File wineDir) {
        File commit = wineRuntimeCandidateCommit(wineDir);
        if (commit.exists() && !commit.delete()) {
            throw new IllegalStateException(
                    "Unable to clean Wine runtime candidate commit " + commit);
        }
    }

    private static void deleteWineRuntimeCandidateJournal(File wineDir) {
        File journal = wineRuntimeCandidateJournal(wineDir);
        if (journal.exists() && !journal.delete()) {
            throw new IllegalStateException(
                    "Unable to clean Wine runtime candidate journal " + journal);
        }
        File pending = new File(journal.getAbsolutePath() + ".pending");
        if (FileUtils.isSymlink(pending)) {
            throw new IllegalStateException(
                    "Unsafe Wine runtime candidate journal staging " + pending);
        }
        if (pending.exists() && !pending.delete()) {
            throw new IllegalStateException(
                    "Unable to clean Wine runtime candidate journal staging " + pending);
        }
        deleteWineRuntimeCandidateCommit(wineDir);
    }

    private static File wineRuntimeFailed(File wineDir) {
        return new File(wineDir.getAbsolutePath() + ".wine-runtime-failed");
    }

    static void rollbackWineRuntimeTransaction(File wineDir, String checksum)
            throws Exception {
        File backup = wineRuntimeBackup(wineDir);
        if (!backup.exists()) {
            recoverWineRuntimeTransaction(wineDir, checksum);
            return;
        }
        if (!backup.isDirectory() || FileUtils.isSymlink(backup)) {
            throw new IllegalStateException(
                    "Unsafe Wine runtime rollback backup " + backup);
        }
        File failed = wineRuntimeFailed(wineDir);
        if (failed.exists() || FileUtils.isSymlink(failed)) {
            throw new IllegalStateException(
                    "Stale Wine runtime rollback path " + failed);
        }
        if (wineDir.exists() && !wineDir.renameTo(failed)) {
            throw new IllegalStateException(
                    "Unable to quarantine candidate Wine runtime " + wineDir);
        }
        if (!backup.renameTo(wineDir)) {
            if (failed.exists()) failed.renameTo(wineDir);
            throw new IllegalStateException(
                    "Unable to restore Wine runtime backup " + backup);
        }
        verifyPreparedWineRuntime(wineDir);
        writeWineRuntimeMarker(wineDir, checksum);
        if (failed.exists() && !FileUtils.delete(failed)) {
            throw new IllegalStateException(
                    "Unable to clean candidate Wine runtime " + failed);
        }
    }

    /**
     * Wine registers its bundled fonts while updating a prefix, but DirectWrite
     * resolves those registry entries through C:\windows\Fonts.  A managed Box
     * installs the Wine runtime and prefix as independent layers, so merely
     * setting WINEDATADIR can leave entries such as Tahoma registered without
     * the corresponding file in the prefix.  CEF then enters an invalid font
     * fallback path.
     *
     * Keep the prefix's Wine-owned fonts synchronized with the selected runtime
     * and invalidate Wine's update timestamp whenever the files change.  User
     * fonts and the common CJK font payload are left untouched.
     */
    private void syncBundledWineFonts(File wineDir) {
        syncCommonCjkFonts();
        File sourceDir = new File(wineDir, "share/wine/fonts");
        File[] sourceFonts = sourceDir.listFiles(file -> {
            if (file == null || !file.isFile()) return false;
            String name = file.getName().toLowerCase(Locale.ROOT);
            return name.endsWith(".fon") ||
                    name.endsWith(".ttf") ||
                    name.endsWith(".ttc") ||
                    name.endsWith(".otf");
        });
        if (sourceFonts == null || sourceFonts.length == 0) {
            Log.w(TAG, "syncBundledWineFonts: missing runtime fonts " + sourceDir.getAbsolutePath());
            return;
        }

        File prefixDir = runtime.getContainerRootDir();
        File windowsDir = new File(prefixDir, ".wine/drive_c/windows");
        if (!windowsDir.isDirectory()) {
            Log.i(TAG, "syncBundledWineFonts: prefix is not installed yet");
            return;
        }
        File fontsDir = new File(windowsDir, "Fonts");
        if (!fontsDir.isDirectory() && !fontsDir.mkdirs()) {
            Log.w(TAG, "syncBundledWineFonts: failed to create " + fontsDir.getAbsolutePath());
            return;
        }

        int copied = 0;
        for (File sourceFont : sourceFonts) {
            File targetFont = new File(fontsDir, sourceFont.getName());
            if (targetFont.isFile() && FileUtils.contentEquals(sourceFont, targetFont)) continue;
            if (FileUtils.copy(sourceFont, targetFont)) copied++;
            else Log.w(TAG, "syncBundledWineFonts: failed to copy " + sourceFont.getName());
        }

        if (copied > 0) {
            File updateTimestamp = new File(prefixDir, ".wine/.update-timestamp");
            if (updateTimestamp.exists() && !updateTimestamp.delete()) {
                Log.w(TAG, "syncBundledWineFonts: unable to invalidate " +
                        updateTimestamp.getAbsolutePath());
            }
        }
        Log.i(TAG, "syncBundledWineFonts: copied=" + copied +
                " source=" + sourceDir.getAbsolutePath() +
                " target=" + fontsDir.getAbsolutePath() +
                " tahoma=" + new File(fontsDir, "tahoma.ttf").isFile() +
                " tahomabd=" + new File(fontsDir, "tahomabd.ttf").isFile());
    }

    /**
     * The common prefix payload contains Microsoft YaHei as msyh.ttc, while
     * Wine's Fonts registry names the local file msyh.ttf.  It also registers
     * SimSun as a local simsun.ttc although the base imagefs keeps that font in
     * /usr/share/fonts.  DirectWrite ignores both families until the exact
     * registered filenames exist in C:\windows\Fonts.
     */
    private void syncCommonCjkFonts() {
        File prefixDir = runtime.getContainerRootDir();
        File windowsDir = new File(prefixDir, ".wine/drive_c/windows");
        if (!windowsDir.isDirectory()) {
            Log.i(TAG, "syncCommonCjkFonts: prefix is not installed yet");
            return;
        }
        File fontsDir = new File(windowsDir, "Fonts");
        if (!fontsDir.isDirectory() && !fontsDir.mkdirs()) {
            Log.w(TAG, "syncCommonCjkFonts: failed to create " + fontsDir.getAbsolutePath());
            return;
        }

        int copied = 0;
        File yaheiCollection = new File(fontsDir, "msyh.ttc");
        File yaheiRegisteredName = new File(fontsDir, "msyh.ttf");
        if (!yaheiRegisteredName.exists() && yaheiCollection.isFile() &&
                FileUtils.copy(yaheiCollection, yaheiRegisteredName)) {
            copied++;
        }

        File simsunSource = new File(
                ImageFs.find(context).getRootDir(),
                "usr/share/fonts/truetype/ms/simsun.ttc");
        File simsunTarget = new File(fontsDir, "simsun.ttc");
        if (!simsunTarget.exists() && simsunSource.isFile() &&
                FileUtils.copy(simsunSource, simsunTarget)) {
            copied++;
        }

        if (copied > 0) {
            File updateTimestamp = new File(prefixDir, ".wine/.update-timestamp");
            if (updateTimestamp.exists() && !updateTimestamp.delete()) {
                Log.w(TAG, "syncCommonCjkFonts: unable to invalidate " +
                        updateTimestamp.getAbsolutePath());
            }
        }
        Log.i(TAG, "syncCommonCjkFonts: copied=" + copied +
                " yahei=" + yaheiRegisteredName.isFile() +
                " simsun=" + simsunTarget.isFile());
    }

    private void installGraphicsWrapperStack(BoxSpec.Layer layer, BoxInstallListener listener, int overallProgress) throws Exception {
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        String assetPath = stripAssetPrefix(layer.source);
        verifyAssetChecksum(assetPath, layer.checksum);
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, assetPath, rootDir)) {
            throw new IllegalStateException("Failed to extract graphics wrapper stack " + assetPath);
        }
        publish(listener, "graphics-wrapper-stack", overallProgress, 100, rootDir.getAbsolutePath());
    }

    private void installInputBridgeStack(BoxSpec.Layer layer, BoxInstallListener listener,
                                         int overallProgress) throws Exception {
        if (XServerDisplayActivity.hasActiveSession()) {
            throw new IllegalStateException("active_box_session: stop the Box before updating XInput");
        }
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        String assetPath = stripAssetPrefix(layer.source);
        verifyAssetChecksum(assetPath, layer.checksum);

        File stagingDir = new File(rootDir, ".winlator/input-bridge-staging");
        if (stagingDir.exists() && !FileUtils.delete(stagingDir)) {
            throw new IllegalStateException("Failed to clear input bridge staging " + stagingDir);
        }
        if (!stagingDir.mkdirs()) {
            throw new IllegalStateException("Failed to create input bridge staging " + stagingDir);
        }

        boolean extracted = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                assetPath,
                stagingDir,
                (file, size) -> {
                    String name = file.getName().toLowerCase(Locale.US);
                    return name.startsWith("xinput") && name.endsWith(".dll") ? file : null;
                });
        if (!extracted) {
            throw new IllegalStateException("Failed to extract input bridge stack " + assetPath);
        }

        File xinput13 = new File(stagingDir, "xinput1_3.dll");
        File xinput14 = new File(stagingDir, "xinput1_4.dll");
        if (!xinput13.isFile() || !xinput14.isFile()) {
            FileUtils.delete(stagingDir);
            throw new IllegalStateException("Input bridge archive is missing xinput1_3.dll or xinput1_4.dll");
        }

        String runtimeId = BoxRuntime.resolveRuntimeIdentifier(layer);
        File prefixDir = new File(rootDir, stripLeadingSlash(layer.target));
        File runtimeDir = new File(rootDir, "opt/" + runtimeId + "/lib/wine/aarch64-windows");
        if (!prefixDir.isDirectory() || !runtimeDir.isDirectory()) {
            FileUtils.delete(stagingDir);
            throw new IllegalStateException("Input bridge destinations are unavailable prefix=" +
                    prefixDir + " runtime=" + runtimeDir);
        }

        File[] sources = {xinput13, xinput14, xinput13, xinput14};
        File[] destinations = {
                new File(prefixDir, "xinput1_3.dll"),
                new File(prefixDir, "xinput1_4.dll"),
                new File(runtimeDir, "xinput1_3.dll"),
                new File(runtimeDir, "xinput1_4.dll")
        };
        File[] prepared = new File[destinations.length];
        File[] backups = new File[destinations.length];
        boolean[] activated = new boolean[destinations.length];

        try {
            for (int i = 0; i < destinations.length; i++) {
                prepared[i] = new File(destinations[i].getAbsolutePath() + ".input-bridge-new");
                backups[i] = new File(destinations[i].getAbsolutePath() + ".input-bridge-backup");
                FileUtils.delete(prepared[i]);
                FileUtils.delete(backups[i]);
                if (!FileUtils.copy(sources[i], prepared[i]) ||
                        !sha256(sources[i]).equalsIgnoreCase(sha256(prepared[i]))) {
                    throw new IllegalStateException("Failed to prepare input bridge " + destinations[i]);
                }
            }

            for (int i = 0; i < destinations.length; i++) {
                if (destinations[i].exists() && !destinations[i].renameTo(backups[i])) {
                    throw new IllegalStateException("Failed to back up input bridge " + destinations[i]);
                }
                if (!prepared[i].renameTo(destinations[i])) {
                    if (backups[i].exists()) backups[i].renameTo(destinations[i]);
                    throw new IllegalStateException("Failed to activate input bridge " + destinations[i]);
                }
                activated[i] = true;
            }

            for (int i = 0; i < destinations.length; i++) {
                String expected = sha256(sources[i]);
                String actual = sha256(destinations[i]);
                if (expected.isEmpty() || !expected.equalsIgnoreCase(actual)) {
                    throw new IllegalStateException("Activated input bridge hash mismatch " +
                            destinations[i]);
                }
            }
        }
        catch (Exception e) {
            for (int i = destinations.length - 1; i >= 0; i--) {
                FileUtils.delete(prepared[i]);
                if (activated[i]) FileUtils.delete(destinations[i]);
                if (backups[i] == null || !backups[i].exists()) continue;
                if (!backups[i].renameTo(destinations[i])) {
                    Log.e(TAG, "Failed to restore input bridge backup " + destinations[i]);
                }
            }
            FileUtils.delete(stagingDir);
            throw e;
        }

        for (File backup : backups) FileUtils.delete(backup);
        FileUtils.delete(stagingDir);
        publish(listener, "input-bridge-stack", overallProgress, 100,
                prefixDir.getAbsolutePath() + " + " + runtimeDir.getAbsolutePath());
    }

    private void installDxWrapperStack(BoxSpec.Layer layer, BoxInstallListener listener, int overallProgress) throws Exception {
        if (NativeSystemDxProfile.enabled(runtime.getSpec())) requireIdleWineUpdate();
        ImageFs imageFs = ImageFs.find(context);
        String assetPath = stripAssetPrefix(layer.source);
        verifyAssetChecksum(assetPath, layer.checksum);
        File windowsDir = new File(imageFs.getRootDir(), stripLeadingSlash(layer.target));
        if (!windowsDir.isDirectory() && !windowsDir.mkdirs()) {
            throw new IllegalStateException("Failed to create DX wrapper target " + windowsDir);
        }
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, assetPath, windowsDir,
                (file, size) -> {
                    String relative = windowsDir.toPath().relativize(file.toPath()).toString();
                    return NativeSystemDxProfile.nativeOwnsLegacyEntry(runtime.getSpec(), relative)
                            ? null : file;
                })) {
            throw new IllegalStateException("Failed to extract DX wrapper stack " + assetPath);
        }
        publish(listener, "dx-wrapper-stack", overallProgress, 100, windowsDir.getAbsolutePath());
    }

    private void installNativeDxWrapperStack(BoxSpec.Layer layer,
            BoxInstallListener listener, int overallProgress) throws Exception {
        NativeDxEnvironment.windowsDirectory(layer); // validate the owned destination
        String source = stripAssetPrefix(layer.source);
        verifyAssetChecksum(source, layer.checksum);
        requireIdleWineUpdate();
        File root = ImageFs.find(context).getRootDir();
        File destination = new File(root, stripLeadingSlash(layer.target));
        if (!destination.getCanonicalPath().equals(
                new File(root.getCanonicalFile(), stripLeadingSlash(layer.target)).getAbsolutePath())) {
            throw new IllegalStateException("Native DX destination has a redirected parent");
        }
        File stage = new File(context.getCacheDir(),
                "native-dx-" + layer.checksum.substring(0, 12));
        if (FileUtils.isSymlink(stage) || FileUtils.isSymlink(destination)) {
            throw new IllegalStateException("Native DX destination is a symlink");
        }
        if (stage.exists()) FileUtils.delete(stage);
        if (!stage.mkdirs()) throw new IllegalStateException("Cannot stage native DX");
        try {
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
                    source, stage, (file, size) -> {
                        String relative = stage.toPath().relativize(file.toPath())
                                .toString().replace(File.separatorChar, '/');
                        if (relative.startsWith("./")) relative = relative.substring(2);
                        if ("system32/d3d11.dll".equals(relative)) return new File(stage, "d3d11.dll");
                        if ("system32/dxgi.dll".equals(relative)) return new File(stage, "dxgi.dll");
                        return null;
                    })) {
                throw new IllegalStateException("Cannot extract native DX");
            }
            for (String name : new String[] {"d3d11.dll", "dxgi.dll"}) {
                NativeDxPeImage.requirePlainArm64(new File(stage, name));
            }
            if (destination.exists()) {
                for (String name : new String[] {"d3d11.dll", "dxgi.dll"}) {
                    File existing = new File(destination, name);
                    if (FileUtils.isSymlink(existing) || !existing.isFile() ||
                            !sha256(existing).equalsIgnoreCase(sha256(new File(stage, name)))) {
                        throw new IllegalStateException("Preserved native DX identity mismatch");
                    }
                }
            }
            else {
                File parent = destination.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IllegalStateException("Cannot create native DX parent");
                }
                if (!stage.renameTo(destination)) {
                    throw new IllegalStateException("Cannot activate native DX directory");
                }
            }
            if (layer.hasCapability(NativeSystemDxProfile.CAPABILITY)) {
                synchronizeNativeSystemDx(destination);
            }
            synchronizeNativeShaderCompiler(layer);
            publish(listener, layer.type, overallProgress, 100, destination.getAbsolutePath());
        }
        finally {
            if (stage.exists()) FileUtils.delete(stage);
        }
    }

    private void installFrameGenerationStack(
            BoxSpec.Layer layer,
            BoxInstallListener listener,
            int overallProgress) throws Exception {
        ImageFs imageFs = ImageFs.find(context);
        String source = stripAssetPrefix(layer.source);
        verifyAssetChecksum(source, layer.checksum);

        File stagingDir = new File(context.getCacheDir(), "frame-generation-stack-stage");
        FileUtils.delete(stagingDir);
        if (!stagingDir.mkdirs()) {
            throw new IllegalStateException("Failed to create frame generation staging directory");
        }
        if (!TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context, source, stagingDir)) {
            FileUtils.delete(stagingDir);
            throw new IllegalStateException("Failed to extract frame generation stack");
        }

        File rootDir = imageFs.getRootDir();
        File[] sources = {
                new File(stagingDir, "usr/lib/liblsfg-vk-layer.so"),
                new File(
                        stagingDir,
                        "usr/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json"),
                new File(stagingDir, "usr/share/licenses/lsfg-vk-android/LICENSE.md")
        };
        File[] destinations = {
                new File(rootDir, "usr/lib/liblsfg-vk-layer.so"),
                new File(
                        rootDir,
                        "usr/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json"),
                new File(rootDir, "usr/share/licenses/lsfg-vk-android/LICENSE.md")
        };
        File[] prepared = new File[destinations.length];
        File[] backups = new File[destinations.length];
        boolean[] activated = new boolean[destinations.length];

        try {
            for (int i = 0; i < destinations.length; i++) {
                if (!sources[i].isFile()) {
                    throw new IllegalStateException(
                            "Frame generation archive entry is missing: " + sources[i]);
                }
                File parent = destinations[i].getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IllegalStateException(
                            "Failed to create frame generation destination: " + parent);
                }
                prepared[i] = new File(
                        destinations[i].getAbsolutePath() + ".frame-generation-new");
                backups[i] = new File(
                        destinations[i].getAbsolutePath() + ".frame-generation-backup");
                FileUtils.delete(prepared[i]);
                FileUtils.delete(backups[i]);
                if (!FileUtils.copy(sources[i], prepared[i]) ||
                        !sha256(sources[i]).equalsIgnoreCase(sha256(prepared[i]))) {
                    throw new IllegalStateException(
                            "Failed to prepare frame generation file " + destinations[i]);
                }
            }

            for (int i = 0; i < destinations.length; i++) {
                if (destinations[i].exists() && !destinations[i].renameTo(backups[i])) {
                    throw new IllegalStateException(
                            "Failed to back up frame generation file " + destinations[i]);
                }
                if (!prepared[i].renameTo(destinations[i])) {
                    if (backups[i].exists()) backups[i].renameTo(destinations[i]);
                    throw new IllegalStateException(
                            "Failed to activate frame generation file " + destinations[i]);
                }
                activated[i] = true;
            }

            FileUtils.chmod(destinations[0], 0755);
            FileUtils.chmod(destinations[1], 0644);
            FileUtils.chmod(destinations[2], 0644);
            for (int i = 0; i < destinations.length; i++) {
                String expected = sha256(sources[i]);
                String actual = sha256(destinations[i]);
                if (expected.isEmpty() || !expected.equalsIgnoreCase(actual)) {
                    throw new IllegalStateException(
                            "Activated frame generation hash mismatch " + destinations[i]);
                }
            }
        }
        catch (Exception e) {
            for (int i = destinations.length - 1; i >= 0; i--) {
                if (prepared[i] != null) FileUtils.delete(prepared[i]);
                if (activated[i]) FileUtils.delete(destinations[i]);
                if (backups[i] == null || !backups[i].exists()) continue;
                if (!backups[i].renameTo(destinations[i])) {
                    Log.e(TAG, "Failed to restore frame generation backup " + destinations[i]);
                }
            }
            FileUtils.delete(stagingDir);
            throw e;
        }

        for (File backup : backups) FileUtils.delete(backup);
        FileUtils.delete(stagingDir);
        publish(
                listener,
                FrameGenerationManager.LAYER_TYPE,
                overallProgress,
                100,
                source);
    }

    private void installGpuDriverStack(BoxSpec spec, BoxSpec.Layer layer, BoxInstallListener listener, int overallProgress) throws Exception {
        String source = stripAssetPrefix(layer.source);
        verifyAssetChecksum(source, layer.checksum);
        if (source.startsWith("graphics_driver/adrenotools-")) {
            String driverId = FileUtils.getBasename(source.substring("graphics_driver/adrenotools-".length()));
            boolean replace = "replace".equals(layer.replacePolicy);
            File marker = new File(BoxPaths.getBoxDir(context), "initial-install-owner");
            if (!replace && BoxInitialInstallGuard.mayResume(marker, BoxPaths.getSpecFile(context), runtime.isInstalled())) {
                installVerifiedFreshGpuDriver(driverId, source);
                publish(listener, "gpu-driver-stack", overallProgress, 100, driverId);
                return;
            }
            if (!new AdrenotoolsManager(context).extractDriverFromResources(driverId, replace)) {
                throw new IllegalStateException("Failed to install GPU driver " + driverId);
            }
            publish(listener, "gpu-driver-stack", overallProgress, 100, driverId);
            return;
        }

        File outDir = new File(BoxPaths.getExternalRoot(spec), "gpu-driver");
        FileUtils.delete(outDir);
        outDir.mkdirs();
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, source, outDir)) {
            throw new IllegalStateException("Failed to extract GPU driver stack");
        }
        publish(listener, "gpu-driver-stack", overallProgress, 100, outDir.getAbsolutePath());
    }

    /** Fresh install never accepts or leaves a partially extracted GPU driver. */
    private void installVerifiedFreshGpuDriver(String driverId, String assetPath) throws Exception {
        if (!driverId.matches("[A-Za-z0-9][A-Za-z0-9._+-]*")) throw new IllegalStateException("Unsafe GPU driver ID");
        AdrenotoolsManager manager = new AdrenotoolsManager(context);
        File destination = manager.getDriverDir(driverId);
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory() || FileUtils.isSymlink(parent) || FileUtils.isSymlink(destination)) {
            throw new IllegalStateException("Unsafe GPU driver directory");
        }
        File[] abandoned = parent.listFiles((dir, name) -> name.startsWith(".wowbox-initial-gpu-"));
        if (abandoned == null) throw new IOException("Unable to inspect staged GPU drivers");
        for (File previous : abandoned) {
            if (!previous.isDirectory() || FileUtils.isSymlink(previous)) continue;
            File owner = new File(previous, ".wowbox-owner");
            if (owner.isFile() && !FileUtils.isSymlink(owner) &&
                    "wowbox-initial-gpu-v1\n".equals(AtomicFileSupport.readUtf8(owner)) &&
                    !FileUtils.delete(previous)) {
                throw new IOException("Cannot clean abandoned GPU driver stage");
            }
        }
        File staged = java.nio.file.Files.createTempDirectory(parent.toPath(), ".wowbox-initial-gpu-").toFile();
        try {
            writeReleaseStageFile(new File(staged, ".wowbox-owner"),
                    "wowbox-initial-gpu-v1\n".getBytes(StandardCharsets.US_ASCII));
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, assetPath, staged)) {
                throw new IllegalStateException("Cannot extract staged GPU driver");
            }
            File stagedMeta = new File(staged, "meta.json");
            if (!stagedMeta.isFile() || FileUtils.isSymlink(stagedMeta)) throw new IllegalStateException("Missing GPU metadata");
            JSONObject meta = new JSONObject(FileUtils.readString(stagedMeta));
            String libName = meta.optString("libraryName", "");
            if (!libName.matches("[A-Za-z0-9][A-Za-z0-9._+-]*")) throw new IllegalStateException("Unsafe GPU library name");
            File stagedLib = new File(staged, libName);
            if (!stagedLib.isFile() || FileUtils.isSymlink(stagedLib)) throw new IllegalStateException("Missing staged GPU library");
            if (destination.exists()) {
                File oldMeta = new File(destination, "meta.json");
                File oldLib = new File(destination, libName);
                if (!destination.isDirectory() || !oldMeta.isFile() || !oldLib.isFile() ||
                        FileUtils.isSymlink(oldMeta) || FileUtils.isSymlink(oldLib) ||
                        !WinePrefixDeltaReconciler.sha256(stagedMeta).equals(WinePrefixDeltaReconciler.sha256(oldMeta)) ||
                        !WinePrefixDeltaReconciler.sha256(stagedLib).equals(WinePrefixDeltaReconciler.sha256(oldLib))) {
                    throw new IllegalStateException("Existing GPU driver is incomplete or does not match release");
                }
            } else if (!staged.renameTo(destination)) {
                throw new IllegalStateException("Cannot activate verified GPU driver");
            }
        } finally {
            if (staged.exists() && !FileUtils.delete(staged)) Log.w(TAG, "Unable to remove staged GPU driver");
        }
    }

    private void installCpuEmuStack(BoxSpec.Layer layer, BoxInstallListener listener, int overallProgress) throws Exception {
        ImageFs imageFs = ImageFs.find(context);
        String source = stripAssetPrefix(layer.source);
        verifyAssetChecksum(source, layer.checksum);
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, source, imageFs.getRootDir())) {
            throw new IllegalStateException("Failed to extract CPU emu stack");
        }
        File wowbox64Dir = new File(imageFs.getRootDir(), "opt/wowbox64-" + DefaultVersion.FEXCORE);
        if (!wowbox64Dir.isDirectory()) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "wowbox64/wowbox64-0.3.7.tzst", imageFs.getRootDir());
        }
        publish(listener, "cpu-emu-stack", overallProgress, 100, source);
    }

    private void installPayload(BoxSpec spec, boolean dryRun, BoxInstallListener listener, int overallProgress) throws Exception {
        if (dryRun) return;
        if ("directory".equalsIgnoreCase(spec.payload.kind)) {
            File payloadDir = BoxPaths.getPayloadDir(spec);
            if (!payloadDir.isDirectory() && !payloadDir.mkdirs()) {
                throw new IllegalStateException("Failed to create payload directory " + payloadDir);
            }
            publish(listener, "payload-directory", overallProgress, 100, payloadDir.getAbsolutePath());
            return;
        }

        String scheme = Uri.parse(spec.payload.source).getScheme();
        if (scheme == null || scheme.isEmpty()) return;

        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            File downloadsDir = BoxPaths.getDownloadsDir(spec);
            downloadsDir.mkdirs();
            File destination = new File(downloadsDir, FileUtils.getName(Uri.parse(spec.payload.source).getPath()));
            publish(listener, "payload-download", overallProgress, 10, destination.getAbsolutePath());
            downloadBlocking(spec.payload.source, destination, listener, overallProgress);
            if ("archive".equals(spec.payload.kind)) {
                extractPayloadArchive(spec, destination, listener, overallProgress);
            }
            return;
        }

        if ("file".equalsIgnoreCase(scheme) && "archive".equals(spec.payload.kind)) {
            extractPayloadArchive(spec, new File(Uri.parse(spec.payload.source).getPath()), listener, overallProgress);
        }
    }

    /** Initial install may create the public payload directory but never replace it. */
    static boolean isAllowedPublicInitialPayload(BoxSpec.Payload requested, BoxSpec.Payload bundled) {
        return requested != null && bundled != null &&
                BoxPublicPayloadPath.isDefault(requested.kind, requested.source, requested.extractDir, requested.mountDrive) &&
                BoxPublicPayloadPath.isDefault(bundled.kind, bundled.source, bundled.extractDir, bundled.mountDrive);
    }

    private void extractPayloadArchive(BoxSpec spec, File archive, BoxInstallListener listener, int overallProgress) throws Exception {
        File extractDir = BoxPaths.getPayloadDir(spec);
        FileUtils.delete(extractDir);
        extractDir.mkdirs();
        String name = archive.getName().toLowerCase(Locale.US);
        boolean ok;
        if (name.endsWith(".tzst")) {
            ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, extractDir);
        } else if (name.endsWith(".txz") || name.endsWith(".tar.xz") || name.endsWith(".wcp.xz") || name.endsWith(".xz")) {
            ok = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, archive, extractDir);
        } else {
            throw new IllegalStateException("Unsupported payload archive: " + archive.getName());
        }
        if (!ok) throw new IllegalStateException("Failed to extract payload archive");
        publish(listener, "payload-extract", overallProgress, 100, extractDir.getAbsolutePath());
    }

    private void downloadBlocking(String url, File destination, BoxInstallListener listener, int overallProgress) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) (new java.net.URL(url)).openConnection();
        if (connection.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("Download failed with status " + connection.getResponseCode());
        }
        destination.getParentFile().mkdirs();
        try (java.io.InputStream in = connection.getInputStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int contentLength = connection.getContentLength();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
                int pct = contentLength > 0 ? (int) ((total * 100.0f) / contentLength) : 0;
                publish(listener, "payload-download", overallProgress, pct, destination.getAbsolutePath());
            }
        }
        publish(listener, "payload-download", overallProgress, 100, destination.getAbsolutePath());
    }

    private void validateSpec(BoxSpec spec) {
        if (spec.layers.isEmpty()) throw new IllegalArgumentException("Box spec has no layers");
        java.util.HashSet<String> layerTypes = new java.util.HashSet<>();
        BoxSpec.Layer wineRuntimeLayer = null;
        BoxSpec.Layer prefixLayer = null;
        BoxSpec.Layer inputBridgeLayer = null;
        for (BoxSpec.Layer layer : spec.layers) {
            if (layer.type == null || layer.type.isEmpty()) throw new IllegalArgumentException("Layer type missing");
            if (!layerTypes.add(layer.type)) {
                throw new IllegalArgumentException("Duplicate layer type: " + layer.type);
            }
            if (layer.source == null || layer.source.isEmpty()) throw new IllegalArgumentException("Layer source missing for " + layer.type);
            if (layer.checksum == null ||
                    !layer.checksum.matches("(?i)^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException(
                        "Layer checksum must be SHA-256 for " + layer.type);
            }
            if (!"replace".equals(layer.replacePolicy) && !"preserve".equals(layer.replacePolicy)) {
                throw new IllegalArgumentException("Unsupported replacePolicy for " + layer.type + ": " + layer.replacePolicy);
            }
            if (layer.runtimeIdentifier != null &&
                    !layer.runtimeIdentifier.isEmpty() &&
                    !layer.runtimeIdentifier.matches("^[A-Za-z0-9][A-Za-z0-9._+-]*$")) {
                throw new IllegalArgumentException(
                        "Unsafe runtimeIdentifier for " + layer.type);
            }
            if ("wine-runtime".equals(layer.type)) {
                wineRuntimeLayer = layer;
                String runtimeIdentifier = BoxRuntime.resolveRuntimeIdentifier(layer);
                if (!("/opt/" + runtimeIdentifier).equals(layer.target)) {
                    throw new IllegalArgumentException(
                            "Wine runtime target does not match runtimeIdentifier");
                }
            }
            else if ("prefix-template".equals(layer.type)) {
                prefixLayer = layer;
            }
            else if ("input-bridge-stack".equals(layer.type)) {
                inputBridgeLayer = layer;
            }
            else if (ManagedDxProfile.NATIVE_LAYER_TYPE.equals(layer.type)) {
                NativeDxEnvironment.windowsDirectory(layer);
                if (!layer.hasCapability(ManagedDxProfile.CAPABILITY) ||
                        !layer.hasCapability("windows-path-arm64-dx") ||
                        !"tzst".equals(layer.archive)) {
                    throw new IllegalArgumentException("Invalid managed native DX capabilities");
                }
                if (layer.hasCapability(NativeShaderCompilerProfile.CAPABILITY)) {
                    NativeShaderCompilerProfile.runtimeIdentifier(spec, layer);
                }
            }
            boolean hasWinePrefixDelta = layer.winePrefixDeltaManifest != null &&
                    !layer.winePrefixDeltaManifest.isEmpty();
            boolean hasWinePrefixDeltaChecksum = layer.winePrefixDeltaChecksum != null &&
                    !layer.winePrefixDeltaChecksum.isEmpty();
            if (hasWinePrefixDelta != hasWinePrefixDeltaChecksum) {
                throw new IllegalArgumentException(
                        "Wine prefix delta manifest and checksum must be specified together");
            }
            if (hasWinePrefixDelta) {
                if (!"prefix-template".equals(layer.type)) {
                    throw new IllegalArgumentException(
                            "Wine prefix delta is only valid on prefix-template layers");
                }
                if (!isSafeWinePrefixDeltaManifestAsset(
                        stripAssetPrefix(layer.winePrefixDeltaManifest))) {
                    throw new IllegalArgumentException(
                            "Unsafe Wine prefix delta manifest asset: " +
                                    layer.winePrefixDeltaManifest);
                }
                if (!layer.winePrefixDeltaChecksum.matches("(?i)^[0-9a-f]{64}$")) {
                    throw new IllegalArgumentException(
                            "Invalid Wine prefix delta manifest checksum");
                }
            }
        }
        if (wineRuntimeLayer == null) {
            throw new IllegalArgumentException("wine-runtime layer is missing");
        }
        String wineRuntimeIdentifier =
                BoxRuntime.resolveRuntimeIdentifier(wineRuntimeLayer);
        requireMatchingWineRuntimeIdentifier(
                prefixLayer,
                wineRuntimeIdentifier,
                "prefix-template");
        requireMatchingWineRuntimeIdentifier(
                inputBridgeLayer,
                wineRuntimeIdentifier,
                "input-bridge-stack");
        java.util.HashSet<String> targetIds = new java.util.HashSet<>();
        for (BoxSpec.Target target : spec.launch.targets) {
            if (target.id == null || target.id.isEmpty() || !targetIds.add(target.id)) {
                throw new IllegalArgumentException("Missing or duplicate launch target id: " + target.id);
            }
            BoxRuntime.validateLaunchTarget(target);
        }
        for (BoxSpec.Target target : spec.launch.targets) {
            if (!target.requiresTargetId.isEmpty() && !targetIds.contains(target.requiresTargetId)) {
                throw new IllegalArgumentException("Unknown required launch target: " + target.requiresTargetId);
            }
            if (!target.hiddenWhenTargetIdExists.isEmpty() &&
                    !targetIds.contains(target.hiddenWhenTargetIdExists)) {
                throw new IllegalArgumentException("Unknown hide-condition launch target: " +
                        target.hiddenWhenTargetIdExists);
            }
        }
        if (!spec.launch.defaultTargetId.isEmpty() && spec.launch.findTarget(spec.launch.defaultTargetId) == null) {
            throw new IllegalArgumentException("Unknown default launch target: " + spec.launch.defaultTargetId);
        }
    }

    static void requireMatchingWineRuntimeIdentifier(
            BoxSpec.Layer layer,
            String wineRuntimeIdentifier,
            String layerType) {
        if (layer == null) {
            throw new IllegalArgumentException(layerType + " layer is missing");
        }
        if (wineRuntimeIdentifier == null || wineRuntimeIdentifier.isEmpty() ||
                !wineRuntimeIdentifier.equals(layer.runtimeIdentifier)) {
            throw new IllegalArgumentException(
                    layerType + " runtimeIdentifier must match wine-runtime");
        }
    }

    static boolean managedWineRuntimeBindingsMatch(BoxSpec spec) {
        BoxSpec.Layer wine = findLayer(spec, "wine-runtime");
        BoxSpec.Layer prefix = findLayer(spec, "prefix-template");
        BoxSpec.Layer input = findLayer(spec, "input-bridge-stack");
        if (wine == null || prefix == null || input == null) return false;
        String runtimeIdentifier = wine.runtimeIdentifier;
        return runtimeIdentifier != null && !runtimeIdentifier.isEmpty() &&
                runtimeIdentifier.equals(prefix.runtimeIdentifier) &&
                runtimeIdentifier.equals(input.runtimeIdentifier);
    }

    private void verifyAssetChecksum(String assetPath, String checksum) throws Exception {
        if (checksum == null || checksum.isEmpty()) return;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getAssets().open(assetPath)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format(Locale.US, "%02x", b));
        String actual = sb.toString();
        if (!checksum.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Checksum mismatch for " + assetPath + ": expected=" + checksum + " actual=" + actual);
        }
    }

    private void noteLayerInstalled(BoxSpec.Layer layer, boolean dryRun) throws JSONException {
        noteLayerInstalled(layer, dryRun, false);
    }

    private void noteLayerInstalled(
            BoxSpec.Layer layer,
            boolean dryRun,
            boolean requirePersistedIdentity) throws JSONException {
        if (dryRun) return;
        JSONObject state = runtime.getState();
        JSONArray installed = state.optJSONArray("installedLayers");
        if (installed == null) installed = new JSONArray();
        JSONObject json = new JSONObject();
        json.put("type", layer.type);
        json.put("source", layer.source);
        json.put("runtimeIdentifier", layer.runtimeIdentifier);
        json.put("checksum", layer.checksum);
        json.put("target", layer.target);
        json.put("winePrefixDeltaManifest", layer.winePrefixDeltaManifest);
        json.put("winePrefixDeltaChecksum", layer.winePrefixDeltaChecksum);
        json.put("wineBuiltinCompilerSha256", layer.wineBuiltinCompilerSha256);
        json.put("installedAt", System.currentTimeMillis());
        json.put("criticalFiles", hashCriticalFiles(layer));

        JSONArray updated = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < installed.length(); i++) {
            JSONObject item = installed.optJSONObject(i);
            if (item == null) continue;
            if (layer.type.equals(item.optString("type", ""))) {
                if (!replaced) updated.put(json);
                replaced = true;
            }
            else {
                updated.put(item);
            }
        }
        if (!replaced) updated.put(json);
        state.put("installedLayers", updated);
        runtime.saveState(state);
        if (requirePersistedIdentity) {
            JSONObject persisted = findInstalledLayerRecord(layer.type);
            if (!sameLayerIdentity(layer, persisted)) {
                throw new IllegalStateException(
                        "Unable to persist installed layer identity for " + layer.type);
            }
        }
        if ("input-bridge-stack".equals(layer.type)) {
            runtime.markWowInputBridgeMigrationComplete(layer);
        }
    }

    private JSONArray hashCriticalFiles(BoxSpec.Layer layer) throws JSONException {
        JSONArray files = new JSONArray();
        File rootDir = ImageFs.find(context).getRootDir();
        for (String path : criticalPathsForLayer(layer)) {
            File file = resolveCriticalFile(rootDir, path);
            JSONObject json = new JSONObject();
            json.put("path", path);
            json.put("exists", file.isFile());
            json.put("size", file.isFile() ? file.length() : 0);
            json.put("sha256", file.isFile() ? sha256(file) : "");
            files.put(json);
        }
        return files;
    }

    public String[] criticalPathsForLayer(BoxSpec.Layer layer) {
        if ("wine-runtime".equals(layer.type)) {
            String runtimeIdentifier = BoxRuntime.resolveRuntimeIdentifier(layer);
            String target = "/opt/" + runtimeIdentifier;
            ArrayList<String> paths = new ArrayList<>();
            paths.add(target + "/bin/wine");
            paths.add(target + "/bin/wineserver");
            paths.add(target + "/lib/wine/aarch64-unix/ntdll.so");
            paths.add(target + "/lib/wine/aarch64-unix/winex11.so");
            paths.add(target + "/lib/wine/aarch64-windows/ntdll.dll");
            paths.add(target + "/lib/wine/aarch64-windows/explorer.exe");
            paths.add(target + "/lib/wine/aarch64-windows/rundll32.exe");
            paths.add(target + "/lib/wine/i386-windows/ntdll.dll");
            if (layer.hasCapability("amd64-driver-host")) {
                paths.add(target + "/lib/wine/aarch64-windows/services.exe");
                paths.add(target + "/lib/wine/aarch64-windows/winedevice-x64.exe");
                paths.add(target + "/lib/wine/aarch64-windows/ntoskrnl.exe");
            }
            return paths.toArray(new String[0]);
        }
        if ("prefix-template".equals(layer.type)) {
            ArrayList<String> paths = new ArrayList<>();
            paths.add("/home/xuser-box/.wine/drive_c/windows/system32/kernel32.dll");
            paths.add("/home/xuser-box/.wine/drive_c/windows/system32/ntdll.dll");
            paths.add("/home/xuser-box/.wine/drive_c/windows/system32/drivers/mountmgr.sys");
            if (activeSpecHasLayerCapability("wine-runtime", "amd64-driver-host")) {
                paths.add("/home/xuser-box/.wine/drive_c/windows/system32/services.exe");
                paths.add("/home/xuser-box/.wine/drive_c/windows/system32/winedevice-x64.exe");
            }
            if (layer.winePrefixDeltaManifest != null &&
                    !layer.winePrefixDeltaManifest.isEmpty()) {
                try {
                    for (WinePrefixDeltaReconciler.Entry entry :
                            loadWinePrefixDeltaEntries(
                                    layer,
                                    BoxRuntime.resolveRuntimeIdentifier(layer))) {
                        String path = "/home/xuser-box/.wine/drive_c/windows/" + entry.prefixPath;
                        if (!paths.contains(path)) paths.add(path);
                    }
                }
                catch (Exception e) {
                    throw new IllegalStateException(
                            "Unable to load Wine prefix delta critical paths", e);
                }
            }
            return paths.toArray(new String[0]);
        }
        if ("input-bridge-stack".equals(layer.type)) {
            String target = layer.target == null ? "" : layer.target;
            String runtimeIdentifier = BoxRuntime.resolveRuntimeIdentifier(layer);
            String runtimeTarget = "/opt/" + runtimeIdentifier + "/lib/wine/aarch64-windows";
            return new String[] {
                    target + "/xinput1_3.dll",
                    target + "/xinput1_4.dll",
                    runtimeTarget + "/xinput1_3.dll",
                    runtimeTarget + "/xinput1_4.dll"
            };
        }
        if ("cpu-emu-stack".equals(layer.type)) {
            return new String[] {
                    "/libwow64fex.dll",
                    "/libarm64ecfex.dll"
            };
        }
        if ("dx-wrapper-stack".equals(layer.type)) {
            String[] paths = {
                    "/home/xuser-box/.wine/drive_c/windows/system32/d3d10core.dll",
                    "/home/xuser-box/.wine/drive_c/windows/system32/d3d11.dll",
                    "/home/xuser-box/.wine/drive_c/windows/system32/d3d12.dll",
                    "/home/xuser-box/.wine/drive_c/windows/system32/d3d12core.dll",
                    "/home/xuser-box/.wine/drive_c/windows/system32/d3d9.dll",
                    "/home/xuser-box/.wine/drive_c/windows/system32/dxgi.dll",
                    "/home/xuser-box/.wine/drive_c/windows/syswow64/d3d10core.dll",
                    "/home/xuser-box/.wine/drive_c/windows/syswow64/d3d11.dll",
                    "/home/xuser-box/.wine/drive_c/windows/syswow64/d3d12.dll",
                    "/home/xuser-box/.wine/drive_c/windows/syswow64/d3d12core.dll",
                    "/home/xuser-box/.wine/drive_c/windows/syswow64/d3d9.dll",
                    "/home/xuser-box/.wine/drive_c/windows/syswow64/dxgi.dll"
            };
            ArrayList<String> owned = new ArrayList<>();
            String prefix = "/home/xuser-box/.wine/drive_c/windows/";
            for (String path : paths) {
                if (!NativeSystemDxProfile.nativeOwnsLegacyEntry(runtime.getSpec(),
                        path.substring(prefix.length()))) owned.add(path);
            }
            return owned.toArray(new String[0]);
        }
        if (ManagedDxProfile.NATIVE_LAYER_TYPE.equals(layer.type)) {
            NativeDxEnvironment.windowsDirectory(layer);
            ArrayList<String> paths = new ArrayList<>();
            paths.add(layer.target + "/d3d11.dll");
            paths.add(layer.target + "/dxgi.dll");
            if (layer.hasCapability(NativeSystemDxProfile.CAPABILITY)) {
                paths.add("/home/xuser-box/.wine/drive_c/windows/system32/d3d11.dll");
                paths.add("/home/xuser-box/.wine/drive_c/windows/system32/dxgi.dll");
            }
            if (layer.hasCapability(NativeShaderCompilerProfile.CAPABILITY)) {
                String runtimeId = NativeShaderCompilerProfile.runtimeIdentifier(runtime.getSpec(), layer);
                paths.add("/home/xuser-box/.wine/drive_c/windows/system32/" + NativeShaderCompilerProfile.FILENAME);
                paths.add("/opt/" + runtimeId + "/lib/wine/aarch64-windows/" + NativeShaderCompilerProfile.FILENAME);
            }
            return paths.toArray(new String[0]);
        }
        if ("graphics-wrapper-stack".equals(layer.type)) {
            if (layer.hasCapability("bionic-cxx-runtime")) {
                return new String[] {
                        "/usr/lib/libvulkan_wrapper.so",
                        "/usr/lib/libvulkan_freedreno.so",
                        "/usr/lib/libc++_shared.so",
                        "/usr/lib/libz.so.1",
                        "/usr/lib/libzstd.so.1",
                        "/usr/lib/libX11.so.6",
                        "/usr/lib/libXext.so.6",
                        "/usr/lib/libXau.so.6",
                        "/usr/lib/libXdmcp.so.6",
                        "/usr/lib/libxcb.so.1"
                };
            }
            return new String[] {
                    "/usr/lib/libvulkan_wrapper.so",
                    "/usr/lib/libvulkan_freedreno.so",
                    "/usr/lib/libgcc_s.so.1",
                    "/usr/lib/libstdc++.so.6",
                    "/usr/lib/libz.so.1",
                    "/usr/lib/libzstd.so.1",
                    "/usr/lib/libX11.so.6",
                    "/usr/lib/libXext.so.6",
                    "/usr/lib/libXau.so.6",
                    "/usr/lib/libXdmcp.so.6"
            };
        }
        if (FrameGenerationManager.LAYER_TYPE.equals(layer.type)) {
            return new String[] {
                    "/usr/lib/liblsfg-vk-layer.so",
                    "/usr/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json"
            };
        }
        if ("gpu-driver-stack".equals(layer.type)) {
            String source = stripAssetPrefix(layer.source);
            if (source.startsWith("graphics_driver/adrenotools-")) {
                String driverId = FileUtils.getBasename(source.substring("graphics_driver/adrenotools-".length()));
                AdrenotoolsManager manager = new AdrenotoolsManager(context);
                File library = manager.getDriverLibraryFile(driverId);
                String prefix = "@app/contents/adrenotools/" + driverId + "/";
                return library != null
                        ? new String[] { prefix + "meta.json", prefix + library.getName() }
                        : new String[] { prefix + "meta.json" };
            }
            return new String[] { "/usr/lib/libvulkan_freedreno.so" };
        }
        return new String[0];
    }

    private boolean activeSpecHasLayerCapability(String type, String capability) {
        BoxSpec spec = runtime.getSpec();
        if (spec == null) return false;
        for (BoxSpec.Layer candidate : spec.layers) {
            if (type.equals(candidate.type) && candidate.hasCapability(capability)) return true;
        }
        return false;
    }

    boolean hasPendingWineRuntimeCandidate(BoxSpec spec) {
        BoxSpec.Layer layer = findLayer(spec, "wine-runtime");
        if (layer == null) return false;
        File wineDir = new File(
                ImageFs.find(context).getInstalledWineDir(),
                BoxRuntime.resolveRuntimeIdentifier(layer));
        File journal = wineRuntimeCandidateJournal(wineDir);
        File pending = new File(journal.getAbsolutePath() + ".pending");
        return journal.exists() || FileUtils.isSymlink(journal) ||
                pending.exists() || FileUtils.isSymlink(pending) ||
                wineRuntimeCandidateCommit(wineDir).exists() ||
                FileUtils.isSymlink(wineRuntimeCandidateCommit(wineDir));
    }

    private File resolveCriticalFile(File rootDir, String path) {
        final String appPrefix = "@app/";
        if (path != null && path.startsWith(appPrefix)) {
            return new File(context.getFilesDir(), path.substring(appPrefix.length()));
        }
        return new File(rootDir, stripLeadingSlash(path));
    }

    public JSONObject glibcLinkState() throws JSONException {
        JSONObject json = new JSONObject();
        File filesDir = context.getFilesDir();
        File imagefsDir = new File(filesDir, "imagefs");
        File link = new File(filesDir, "i");
        File ld = new File(imagefsDir, "usr/lib/ld-linux-aarch64.so.1");
        File libc = new File(imagefsDir, "usr/lib/libc.so.6");
        json.put("path", link.getAbsolutePath());
        json.put("exists", link.exists());
        json.put("isSymlink", FileUtils.isSymlink(link));
        json.put("target", "imagefs");
        json.put("imagefsExists", imagefsDir.isDirectory());
        json.put("ldLinux", fileHashJson(ld));
        json.put("libc", fileHashJson(libc));
        return json;
    }

    private JSONObject fileHashJson(File file) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("path", file.getAbsolutePath());
        json.put("exists", file.isFile());
        json.put("size", file.isFile() ? file.length() : 0);
        json.put("sha256", file.isFile() ? sha256(file) : "");
        return json;
    }

    private static String stripLeadingSlash(String path) {
        if (path == null) return "";
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }

    private String sha256(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        }
        catch (Exception e) {
            return "";
        }
    }

    private void linkContainer() {
        File link = runtime.getContainerSymlink();
        FileUtils.symlink("./xuser-box", link.getAbsolutePath());
    }

    private void publish(BoxInstallListener listener, String stage, int overallProgress, int currentProgress, String detail) {
        if (listener != null) listener.onProgress(stage, overallProgress, currentProgress, detail);
        JSONObject payload = new JSONObject();
        try {
            payload.put("stage", stage);
            payload.put("overallProgress", overallProgress);
            payload.put("currentProgress", currentProgress);
            payload.put("detail", detail);
        }
        catch (JSONException ignored) {}
        BoxDebugEventBus.publish("install-progress", payload);
    }

    private int progress(int step, int totalSteps) {
        return Math.min(99, (int) ((step * 100.0f) / totalSteps));
    }

    private static String stripAssetPrefix(String source) {
        return source.startsWith("asset://") ? source.substring("asset://".length()) : source;
    }

    private static BoxSpec.Layer findLayer(BoxSpec spec, String type) {
        if (spec == null || type == null) return null;
        for (BoxSpec.Layer layer : spec.layers) {
            if (type.equals(layer.type)) return layer;
        }
        return null;
    }

    private void requireInPlaceWineCandidate(BoxSpec active, BoxSpec candidate)
            throws Exception {
        BoxSpec.Layer activeWine = findLayer(active, "wine-runtime");
        BoxSpec.Layer candidateWine = findLayer(candidate, "wine-runtime");
        if (activeWine == null || candidateWine == null) {
            throw new IllegalStateException("wine-runtime layer is missing");
        }
        if (!isInPlaceWineCandidateLayer(activeWine, candidateWine)) {
            throw new IllegalStateException(
                    "Wine runtime candidate must replace the active runtime in place");
        }
        if (!sameSpecExceptWineRuntime(active, candidate)) {
            throw new IllegalStateException(
                    "Wine runtime candidate changes unrelated Box configuration");
        }
    }

    boolean wineRuntimeSpecMatches(BoxSpec expected) {
        if (expected == null) return false;
        try {
            return runtime.getSpec().toJson().toString().equals(
                    expected.toJson().toString());
        }
        catch (JSONException error) {
            return false;
        }
    }

    static boolean isInPlaceWineCandidateLayer(
            BoxSpec.Layer active,
            BoxSpec.Layer candidate) {
        return active != null && candidate != null &&
                active.runtimeIdentifier != null &&
                !active.runtimeIdentifier.isEmpty() &&
                active.runtimeIdentifier.equals(candidate.runtimeIdentifier) &&
                active.target.equals(candidate.target) &&
                "replace".equals(candidate.replacePolicy);
    }

    static boolean sameSpecExceptWineRuntime(BoxSpec active, BoxSpec candidate)
            throws JSONException {
        if (active == null || candidate == null) return false;
        if (!sameCandidateLayersExceptWineRuntimeAndPrefixDelta(active, candidate)) {
            return false;
        }
        JSONObject activeJson = active.toJson();
        JSONObject candidateJson = candidate.toJson();
        JSONArray activeLayers = activeJson.getJSONArray("layers");
        JSONArray candidateLayers = candidateJson.getJSONArray("layers");
        if (activeLayers.length() != candidateLayers.length()) return false;

        int activeWineIndex = uniqueLayerIndex(activeLayers, "wine-runtime");
        int candidateWineIndex = uniqueLayerIndex(candidateLayers, "wine-runtime");
        if (activeWineIndex < 0 || candidateWineIndex < 0) return false;
        candidateLayers.put(
                candidateWineIndex,
                new JSONObject(activeLayers.getJSONObject(activeWineIndex).toString()));

        int activePrefixIndex = uniqueLayerIndex(activeLayers, "prefix-template");
        int candidatePrefixIndex = uniqueLayerIndex(candidateLayers, "prefix-template");
        if (activePrefixIndex == -2 || candidatePrefixIndex == -2) return false;
        if (activePrefixIndex != candidatePrefixIndex) return false;
        if (activePrefixIndex >= 0) {
            JSONObject activePrefix = activeLayers.getJSONObject(activePrefixIndex);
            JSONObject candidatePrefix = candidateLayers.getJSONObject(candidatePrefixIndex);
            if (!isAllowedWinePrefixDeltaTransition(activePrefix, candidatePrefix)) {
                return false;
            }
            JSONObject normalizedPrefix = new JSONObject(candidatePrefix.toString());
            normalizedPrefix.put(
                    "winePrefixDeltaManifest",
                    activePrefix.optString("winePrefixDeltaManifest", ""));
            normalizedPrefix.put(
                    "winePrefixDeltaChecksum",
                    activePrefix.optString("winePrefixDeltaChecksum", ""));
            candidateLayers.put(candidatePrefixIndex, normalizedPrefix);
        }
        return activeJson.toString().equals(candidateJson.toString());
    }

    static boolean sameCandidateLayersExceptWineRuntimeAndPrefixDelta(
            BoxSpec active,
            BoxSpec candidate) {
        if (active == null || candidate == null ||
                active.layers.size() != candidate.layers.size()) {
            return false;
        }
        boolean foundWine = false;
        boolean foundPrefix = false;
        for (int i = 0; i < active.layers.size(); i++) {
            BoxSpec.Layer activeLayer = active.layers.get(i);
            BoxSpec.Layer candidateLayer = candidate.layers.get(i);
            if (activeLayer == null || candidateLayer == null ||
                    !activeLayer.type.equals(candidateLayer.type)) {
                return false;
            }
            if ("wine-runtime".equals(activeLayer.type)) {
                if (foundWine) return false;
                foundWine = true;
                continue;
            }
            if ("prefix-template".equals(activeLayer.type)) {
                if (foundPrefix ||
                        !isAllowedWinePrefixDeltaTransition(activeLayer, candidateLayer) ||
                        !sameLayerExceptWinePrefixDelta(activeLayer, candidateLayer)) {
                    return false;
                }
                foundPrefix = true;
                continue;
            }
            if (!sameLayerExactly(activeLayer, candidateLayer)) return false;
        }
        return foundWine;
    }

    private static boolean sameLayerExactly(
            BoxSpec.Layer active,
            BoxSpec.Layer candidate) {
        return sameLayerExceptWinePrefixDelta(active, candidate) &&
                active.winePrefixDeltaManifest.equals(
                        candidate.winePrefixDeltaManifest) &&
                active.winePrefixDeltaChecksum.equals(
                        candidate.winePrefixDeltaChecksum);
    }

    private static boolean sameLayerExceptWinePrefixDelta(
            BoxSpec.Layer active,
            BoxSpec.Layer candidate) {
        return active.type.equals(candidate.type) &&
                active.source.equals(candidate.source) &&
                active.runtimeIdentifier.equals(candidate.runtimeIdentifier) &&
                active.archive.equals(candidate.archive) &&
                active.checksum.equals(candidate.checksum) &&
                active.target.equals(candidate.target) &&
                active.replacePolicy.equals(candidate.replacePolicy) &&
                active.capabilities.equals(candidate.capabilities);
    }

    private static boolean isAllowedWinePrefixDeltaTransition(
            BoxSpec.Layer active,
            BoxSpec.Layer candidate) {
        if (active.winePrefixDeltaManifest.equals(candidate.winePrefixDeltaManifest) &&
                active.winePrefixDeltaChecksum.equals(candidate.winePrefixDeltaChecksum)) {
            return true;
        }
        return !candidate.winePrefixDeltaManifest.isEmpty() &&
                !candidate.winePrefixDeltaChecksum.isEmpty();
    }

    private static int uniqueLayerIndex(JSONArray layers, String type) {
        int match = -1;
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.optJSONObject(i);
            if (layer == null || !type.equals(layer.optString("type", ""))) continue;
            if (match >= 0) return -2;
            match = i;
        }
        return match;
    }

    private static boolean isAllowedWinePrefixDeltaTransition(
            JSONObject active,
            JSONObject candidate) {
        String activeManifest = active.optString("winePrefixDeltaManifest", "");
        String activeChecksum = active.optString("winePrefixDeltaChecksum", "");
        String candidateManifest = candidate.optString("winePrefixDeltaManifest", "");
        String candidateChecksum = candidate.optString("winePrefixDeltaChecksum", "");
        if (activeManifest.equals(candidateManifest) &&
                activeChecksum.equals(candidateChecksum)) {
            return true;
        }
        return !candidateManifest.isEmpty() && !candidateChecksum.isEmpty();
    }

    private void requireInstalledWineRuntimeMatches(BoxSpec.Layer activeLayer)
            throws Exception {
        JSONObject installed = findInstalledLayerRecord(activeLayer.type);
        if (installed == null || !sameLayerIdentity(activeLayer, installed)) {
            throw new IllegalStateException(
                    "Installed Wine runtime identity does not match the active spec");
        }
        JSONArray baseline = installed.optJSONArray("criticalFiles");
        if (baseline == null || baseline.length() == 0) {
            throw new IllegalStateException(
                    "Installed Wine runtime critical hash baseline is missing");
        }
        JSONArray current = hashCriticalFiles(activeLayer);
        if (!allCriticalFileRecordsPresent(baseline) ||
                !hasRequiredLegacyWineRuntimeBaseline(activeLayer, baseline) ||
                !allCriticalFileRecordsPresent(current) ||
                !criticalFileRecordsMatch(baseline, current)) {
            throw new IllegalStateException(
                    "Installed Wine runtime critical files do not match their baseline");
        }
    }

    private JSONObject findInstalledLayerRecord(String type) {
        return findInstalledLayerRecord(runtime.getState(), type);
    }

    private static JSONObject findInstalledLayerRecord(JSONObject state, String type) {
        JSONArray installed = state != null ? state.optJSONArray("installedLayers") : null;
        if (installed == null) return null;
        for (int i = 0; i < installed.length(); i++) {
            JSONObject record = installed.optJSONObject(i);
            if (record != null && type.equals(record.optString("type", ""))) {
                return record;
            }
        }
        return null;
    }

    private void restoreInstalledLayerRecord(JSONObject record) throws JSONException {
        String type = record.optString("type", "");
        if (type.isEmpty()) {
            throw new IllegalStateException(
                    "Installed layer rollback record has no type");
        }
        JSONObject state = runtime.getState();
        JSONArray installed = state.optJSONArray("installedLayers");
        if (installed == null) installed = new JSONArray();
        JSONArray updated = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < installed.length(); i++) {
            JSONObject item = installed.optJSONObject(i);
            if (item == null) continue;
            if (type.equals(item.optString("type", ""))) {
                if (!replaced) updated.put(new JSONObject(record.toString()));
                replaced = true;
            }
            else {
                updated.put(item);
            }
        }
        if (!replaced) updated.put(new JSONObject(record.toString()));
        state.put("installedLayers", updated);
        runtime.saveState(state);
    }

    static boolean criticalFileRecordsMatch(JSONArray expected, JSONArray current) {
        if (expected == null || current == null || expected.length() == 0) {
            return false;
        }
        java.util.HashSet<String> expectedPaths = new java.util.HashSet<>();
        for (int i = 0; i < expected.length(); i++) {
            JSONObject baseline = expected.optJSONObject(i);
            if (baseline == null) return false;
            String path = baseline.optString("path", "");
            if (path.isEmpty() || !expectedPaths.add(path)) return false;
            JSONObject actual = null;
            for (int j = 0; j < current.length(); j++) {
                JSONObject item = current.optJSONObject(j);
                if (item != null && path.equals(item.optString("path", ""))) {
                    actual = item;
                    break;
                }
            }
            if (actual == null ||
                    baseline.optBoolean("exists", false) !=
                            actual.optBoolean("exists", false) ||
                    baseline.optLong("size", -1) != actual.optLong("size", -2) ||
                    !baseline.optString("sha256", "").equalsIgnoreCase(
                            actual.optString("sha256", ""))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasRequiredLegacyWineRuntimeBaseline(
            BoxSpec.Layer layer,
            JSONArray records) {
        String target = "/opt/" + BoxRuntime.resolveRuntimeIdentifier(layer);
        String[] required = {
                target + "/bin/wine",
                target + "/bin/wineserver",
                target + "/lib/wine/aarch64-unix/winex11.so",
                target + "/lib/wine/aarch64-windows/explorer.exe",
                target + "/lib/wine/aarch64-windows/rundll32.exe"
        };
        for (String path : required) {
            boolean found = false;
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.optJSONObject(i);
                if (record != null && path.equals(record.optString("path", ""))) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean criticalFileRecordsExactlyMatch(
            JSONArray expected,
            JSONArray current) {
        return expected != null && current != null &&
                expected.length() == current.length() &&
                allCriticalFileRecordsPresent(current) &&
                criticalFileRecordsMatch(expected, current);
    }

    private static boolean allCriticalFileRecordsPresent(JSONArray records) {
        if (records == null || records.length() == 0) return false;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null || !record.optBoolean("exists", false) ||
                    record.optString("sha256", "").isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameLayerIdentity(BoxSpec.Layer layer, JSONObject record) {
        return layer != null && record != null &&
                layer.type.equals(record.optString("type", "")) &&
                layer.source.equals(record.optString("source", "")) &&
                layer.runtimeIdentifier.equals(record.optString("runtimeIdentifier", "")) &&
                layer.checksum.equalsIgnoreCase(record.optString("checksum", "")) &&
                layer.target.equals(record.optString("target", "")) &&
                layer.winePrefixDeltaManifest.equals(
                        record.optString("winePrefixDeltaManifest", "")) &&
                layer.winePrefixDeltaChecksum.equalsIgnoreCase(
                        record.optString("winePrefixDeltaChecksum", "")) &&
                layer.wineBuiltinCompilerSha256.equalsIgnoreCase(
                        record.optString("wineBuiltinCompilerSha256", ""));
    }

    private static boolean sameLayerIdentity(BoxSpec.Layer first, BoxSpec.Layer second) {
        if (first == null || second == null) return false;
        try {
            return sameLayerIdentity(first, second.toJson());
        }
        catch (JSONException error) {
            return false;
        }
    }

    private static boolean sameLayerIdentity(JSONObject first, JSONObject second) {
        return first != null && second != null &&
                first.optString("type", "").equals(second.optString("type", "")) &&
                first.optString("source", "").equals(second.optString("source", "")) &&
                first.optString("runtimeIdentifier", "").equals(
                        second.optString("runtimeIdentifier", "")) &&
                first.optString("checksum", "").equalsIgnoreCase(
                        second.optString("checksum", "")) &&
                first.optString("target", "").equals(second.optString("target", "")) &&
                first.optString("winePrefixDeltaManifest", "").equals(
                        second.optString("winePrefixDeltaManifest", "")) &&
                first.optString("winePrefixDeltaChecksum", "").equalsIgnoreCase(
                        second.optString("winePrefixDeltaChecksum", "")) &&
                first.optString("wineBuiltinCompilerSha256", "").equalsIgnoreCase(
                        second.optString("wineBuiltinCompilerSha256", ""));
    }

    private static boolean isSafeAssetRelativePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
            return false;
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        }
        return true;
    }

    private static boolean isSafeWinePrefixDeltaManifestAsset(String path) {
        return isSafeAssetRelativePath(path) &&
                path.startsWith("wine_prefix_delta/") &&
                path.endsWith(".json");
    }

    private void recordFailure(Exception error) {
        JSONObject state = runtime.getState();
        try {
            String message = error.getMessage() != null ? error.getMessage() : error.toString();
            state.put("lastFailure", message);
            state.put("lastFailureClass", error.getClass().getName());
            state.put("lastFailureStack", Log.getStackTraceString(error));
            state.put("lastFailureAt", System.currentTimeMillis());
        }
        catch (JSONException ignored) {}
        runtime.saveState(state);
    }

    private static JSONObject payload(String key, Object value) {
        JSONObject json = new JSONObject();
        try {
            json.put(key, value);
        }
        catch (JSONException ignored) {}
        return json;
    }
}
