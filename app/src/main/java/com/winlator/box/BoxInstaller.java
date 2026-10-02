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
import com.winlator.core.WineSessionProcessController;
import com.winlator.core.WineUtils;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.ImageFsInstaller;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class BoxInstaller {
    private static final String TAG = "BoxInstaller";
    private final Context context;
    private final BoxRuntime runtime;

    public BoxInstaller(Context context) {
        this.context = context.getApplicationContext();
        this.runtime = BoxRuntime.get(context);
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
                    repairPrefixBuiltinsFromRuntime(layer, runtime.getContainerRootDir());
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
                JSONObject state = runtime.getState();
                state.put("installed", true);
                state.put("installFinishedAt", System.currentTimeMillis());
                runtime.saveState(state);
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
        FileUtils.delete(rootDir);
        rootDir.mkdirs();
        String assetPath = stripAssetPrefix(layer.source);
        verifyAssetChecksum(assetPath, layer.checksum);
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
        File imeProbeArm64 = new File(containerDir, ".wine/drive_c/windows/ime-contract-probe-arm64.exe");
        File imeProbeAmd64 = new File(containerDir, ".wine/drive_c/windows/ime-contract-probe-amd64.exe");
        FileUtils.copy(context, "winhandler/ime-contract-probe-arm64.exe", imeProbeArm64);
        FileUtils.copy(context, "winhandler/ime-contract-probe-amd64.exe", imeProbeAmd64);
        FileUtils.chmod(imeProbeArm64, 0771);
        FileUtils.chmod(imeProbeAmd64, 0771);
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

        repairPrefixBuiltinsFromRuntime(layer, containerDir);
        File wineDir = new File(
                ImageFs.find(context).getInstalledWineDir(),
                BoxRuntime.resolveRuntimeIdentifier(layer));
        syncBundledWineFonts(wineDir);
        publish(listener, "prefix-template", overallProgress, 100, containerDir.getAbsolutePath());
    }

    private void repairPrefixBuiltinsFromRuntime(BoxSpec.Layer layer, File containerDir) throws Exception {
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
        if (hasWinePrefixDelta) {
            List<WinePrefixDeltaReconciler.Entry> entries =
                    loadWinePrefixDeltaEntries(layer, runtimeId);
            // Preflight and apply the guarded delta before any legacy
            // copy-if-missing repair. An unknown or missing manifest target
            // therefore fails without touching the existing prefix.
            requireIdleWineUpdate();
            deltaResult = WinePrefixDeltaReconciler.reconcile(wineDir, windowsDir, entries);
            copied += deltaResult.replaced;
        }

        if (isArm64EC) {
            if (!hasWinePrefixDelta) {
                copied += syncWineBuiltin(wineDir, "aarch64-windows", "ntdll.dll", system32Dir);
            }
            copied += copyWineBuiltinsIfMissing(wineDir, isArm64EC, "aarch64-windows", system32Dir);
            // ARM64EC's native ARM helper processes (for example rundll32) are
            // resolved through C:\windows\sysarm32.  The generated prefix pack
            // does not create that directory, so mirror the pure AArch64 PE
            // builtins there as part of the same immutable runtime install.
            copied += copyWineBuiltinsIfMissing(wineDir, false, "aarch64-windows", sysarm32Dir);
            if (!hasWinePrefixDelta) {
                copied += syncWineDrivers(wineDir, "aarch64-windows", system32DriversDir);
            }
        }
        else {
            copied += copyWineBuiltinsIfMissing(wineDir, isArm64EC, "x86_64-windows", system32Dir);
            if (!hasWinePrefixDelta) {
                copied += syncWineDrivers(wineDir, "x86_64-windows", system32DriversDir);
            }
        }
        copied += copyWineBuiltinsIfMissing(wineDir, isArm64EC, "i386-windows", syswow64Dir);

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
            entries.add(new WinePrefixDeltaReconciler.Entry(
                    entry.optString("prefixPath", ""),
                    entry.optString("runtimePath", ""),
                    entry.optString("baselineSha256", ""),
                    entry.optString("candidateSha256", "")));
        }
        return entries;
    }

    private int copyWineBuiltinsIfMissing(File wineDir, boolean isArm64EC, String srcName, File dstDir) {
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
     * The public common prefix supplies registered Source Han and DejaVu fonts.
     * Preserve user fonts; Wine's own fonts are synchronized separately.
     */
    private void syncCommonCjkFonts() {
        File fontsDir = new File(runtime.getContainerRootDir(), ".wine/drive_c/windows/Fonts");
        if (!fontsDir.isDirectory()) return;
        Log.i(TAG, "Open fonts: SourceHan=" +
                new File(fontsDir, "SourceHanSansCN-Regular.otf").isFile() +
                " DejaVu=" + new File(fontsDir, "DejaVuSans.ttf").isFile());
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
        ImageFs imageFs = ImageFs.find(context);
        String assetPath = stripAssetPrefix(layer.source);
        verifyAssetChecksum(assetPath, layer.checksum);
        File windowsDir = new File(imageFs.getRootDir(), stripLeadingSlash(layer.target));
        if (!windowsDir.isDirectory() && !windowsDir.mkdirs()) {
            throw new IllegalStateException("Failed to create DX wrapper target " + windowsDir);
        }
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, assetPath, windowsDir)) {
            throw new IllegalStateException("Failed to extract DX wrapper stack " + assetPath);
        }
        publish(listener, "dx-wrapper-stack", overallProgress, 100, windowsDir.getAbsolutePath());
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
            return new String[] {
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
                        record.optString("winePrefixDeltaChecksum", ""));
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
                        second.optString("winePrefixDeltaChecksum", ""));
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
