package com.winlator.box;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.winlator.XServerDisplayActivity;
import com.winlator.core.Callback;
import com.winlator.core.FileUtils;
import com.winlator.core.WineSessionProcessController;
import com.winlator.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BoxDebugServer {
    private static final String TAG = "BoxDebugServer";
    private static BoxDebugServer instance;

    private final Context context;
    private final BoxRuntime runtime;
    private final BoxSessionManager sessionManager;
    private final ArrayList<Socket> websocketClients = new ArrayList<>();
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;

    private final Callback<String> eventForwarder = this::enqueueWebSocketBroadcast;

    private BoxDebugServer(Context context) {
        this.context = context.getApplicationContext();
        this.runtime = BoxRuntime.get(context);
        this.sessionManager = runtime.getSessionManager();
    }

    public static synchronized BoxDebugServer get(Context context) {
        if (instance == null) instance = new BoxDebugServer(context);
        return instance;
    }

    public synchronized void start() {
        BoxSpec spec = runtime.getSpec();
        if (running || !spec.debugServer.enabled) return;
        runtime.getDebugToken();
        running = true;
        executor = Executors.newCachedThreadPool();
        BoxDebugEventBus.subscribe(eventForwarder);
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(spec.debugServer.port);
                while (running) {
                    Socket socket = serverSocket.accept();
                    executor.execute(() -> handle(socket));
                }
            }
            catch (IOException e) {
                Log.e(TAG, "Failed to start debug server on port " + spec.debugServer.port, e);
                running = false;
            }
            catch (Throwable t) {
                Log.e(TAG, "Unexpected debug server failure", t);
                running = false;
            }
        });
    }

    public synchronized void stop() {
        running = false;
        BoxDebugEventBus.unsubscribe(eventForwarder);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            }
            catch (IOException ignored) {}
            serverSocket = null;
        }
        synchronized (websocketClients) {
            for (Socket client : websocketClients) {
                try {
                    client.close();
                }
                catch (IOException ignored) {}
            }
            websocketClients.clear();
        }
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream output = s.getOutputStream()) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int index = line.indexOf(':');
                if (index > 0) {
                    headers.put(line.substring(0, index).trim().toLowerCase(), line.substring(index + 1).trim());
                }
            }

            if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && "/stream".equals(path)) {
                handleWebSocket(s, output, headers);
                return;
            }

            int length = 0;
            if (headers.containsKey("content-length")) {
                try {
                    length = Integer.parseInt(headers.get("content-length"));
                }
                catch (NumberFormatException ignored) {}
            }
            char[] bodyChars = new char[length];
            if (length > 0) reader.read(bodyChars, 0, length);
            String body = length > 0 ? new String(bodyChars) : "";

            if (!isAuthorized(headers)) {
                writeJson(output, 401, error("unauthorized"));
                return;
            }

            JSONObject response = dispatch(method, path, body);
            writeJson(output, 200, response);
        }
        catch (Exception ignored) {}
    }

    private JSONObject dispatch(String method, String path, String body) throws JSONException {
        String route = path;
        int queryIndex = route.indexOf('?');
        if (queryIndex != -1) route = route.substring(0, queryIndex);
        switch (route) {
            case "/health":
                return health();
            case "/spec":
                return runtime.getSpec().toJson();
            case "/layers":
                return wrapArray("layers", runtime.buildInstalledLayersJson());
            case "/env":
                if ("PUT".equals(method)) {
                    JSONObject json = new JSONObject(body);
                    LinkedHashMap<String, String> overrides = runtime.getRuntimeOverrides();
                    JSONArray names = json.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String name = names.getString(i);
                            overrides.put(name, json.optString(name, ""));
                        }
                    }
                    runtime.saveRuntimeOverrides(overrides);
                    BoxDebugEventBus.publish("env-changed", new JSONObject(overrides));
                }
                return runtime.buildEnvSnapshot().toJson();
            case "/executables":
                return executables();
            case "/sessions":
                return wrapArray("sessions", sessionManager.listSessions());
            case "/sessions/current":
                return sessionManager.getCurrentSessionMeta();
            case "/sessions/current/processes":
                return XServerDisplayActivity.requestCurrentProcessSnapshot(1000);
            case "/automation/preflight":
                return preflight();
            case "/automation/permissions":
                return permissions();
            case "/automation/foreground":
                return BoxAutomationState.toForegroundJson();
            case "/automation/layers/verify":
                return verifyLayers();
            case "/automation/install":
                if (!"POST".equals(method)) return error("method not allowed");
                return installBox(body);
            case "/automation/layers/gpu-driver/install":
                if (!"POST".equals(method)) return error("method not allowed");
                return installGpuDriverLayer();
            case "/automation/layers/graphics-wrapper-stack/install":
                if (!"POST".equals(method)) return error("method not allowed");
                return installGraphicsWrapperStackLayer();
            case "/automation/layers/gpu-driver/select":
                if (!"POST".equals(method)) return error("method not allowed");
                return selectGpuDriver(body);
            case "/automation/layers/cpu-emu-stack/install":
                if (!"POST".equals(method)) return error("method not allowed");
                return installCpuEmuStackLayer();
            case "/automation/layers/input-bridge-stack/install":
                if (!"POST".equals(method)) return error("method not allowed");
                return installInputBridgeStackLayer();
            case "/automation/layers/frame-generation-stack/install":
                if (!"POST".equals(method)) return error("method not allowed");
                return installFrameGenerationStackLayer();
            case "/automation/layers/dx-wrapper-stack/install":
                if (!"POST".equals(method)) return error("method not allowed");
                return installDxWrapperStackLayer();
            case "/automation/layers/dx-wrapper-stack/select":
                if (!"POST".equals(method)) return error("method not allowed");
                return selectDxWrapperStack(body);
            case "/automation/layers/wine-runtime/install":
                if (!"POST".equals(method)) return error("method not allowed");
                return installWineRuntimeLayer();
            case "/automation/prefix/reconcile":
                if (!"POST".equals(method)) return error("method not allowed");
                return reconcilePrefix();
            case "/automation/frame-generation":
                if ("POST".equals(method)) return updateFrameGeneration(body);
                if (!"GET".equals(method)) return error("method not allowed");
                return new FrameGenerationManager(context).buildStatus();
            case "/automation/launch-target":
                if (!"PUT".equals(method)) return error("method not allowed");
                return setLaunchTarget(body);
            case "/automation/prefix/protect":
                if (!"POST".equals(method)) return error("method not allowed");
                return protectPrefix(body);
            case "/automation/runtime-overlay":
                if ("POST".equals(method) || "PUT".equals(method)) return registerRuntimeOverlay(body);
                return verifyRuntimeOverlay();
            case "/container/stop":
                return stopContainer();
            case "/container/restart":
                XServerDisplayActivity.requestDebugRestart();
                runtime.launchDesktop();
                BoxDebugEventBus.publish("container-restart", new JSONObject());
                return okWithSession("restart requested", sessionManager.getCurrentSessionId());
            case "/launch":
                JSONObject launch = new JSONObject(body);
                String mode = launch.optString("mode", "executable");
                BoxDebugConfig debugConfig = BoxDebugConfig.fromLaunchRequest(launch);
                try {
                    String sessionId;
                    if ("desktop".equals(mode)) sessionId = runtime.launchDesktop(debugConfig);
                    else if (!launch.optString("targetId", "").isEmpty()) {
                        sessionId = runtime.launchTarget(launch.optString("targetId", ""), debugConfig);
                    }
                    else sessionId = runtime.launchExecutable(launch.optString("path", ""), debugConfig);
                    return okWithSession("launch requested", sessionId);
                }
                catch (RuntimeException e) {
                    JSONObject json = error(e.getMessage());
                    if ("wow_input_bridge_migration_required".equals(e.getMessage())) {
                        json.put("errorCode", "wow_input_bridge_migration_required");
                    }
                    return json;
                }
            case "/debug/snapshot":
                return runtime.buildDebugSnapshot();
            case "/reconcile":
                return wrapArray("issues", new BoxInstaller(context).reconcile());
            case "/mounts":
                return wrapArray("mounts", runtime.buildMountsJson());
            case "/logs":
                return logs(path, body);
            default:
                if (path.startsWith("/logs")) return logs(path, body);
                return error("not found");
        }
    }

    private JSONObject health() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("boxId", runtime.getSpec().boxId);
        json.put("displayName", runtime.getSpec().displayName);
        json.put("installed", runtime.isInstalled());
        json.put("debugPort", runtime.getSpec().debugServer.port);
        json.put("token", runtime.getDebugToken());
        json.put("state", runtime.getState());
        json.put("payloadPath", BoxPaths.getPayloadDir(runtime.getSpec()).getAbsolutePath());
        json.put("currentSessionId", sessionManager.getCurrentSessionId());
        return json;
    }

    private JSONObject preflight() throws JSONException {
        JSONObject permissions = permissions();
        JSONObject foreground = BoxAutomationState.toForegroundJson();
        JSONObject runtimeOverlay = verifyRuntimeOverlay();
        JSONObject layers = verifyLayers(runtimeOverlay);
        JSONObject inputBridgeMigration = runtime.buildWowInputBridgeMigrationStatus();
        JSONObject frameGeneration = new FrameGenerationManager(context).buildStatus();
        JSONObject processes = XServerDisplayActivity.requestCurrentProcessSnapshot(250);
        JSONArray blockingReasons = new JSONArray();

        if (!permissions.optBoolean("ok", false)) blockingReasons.put("missing_permission");
        if (!foreground.optBoolean("appForeground", false)) blockingReasons.put("not_foreground");
        if (!runtime.isInstalled()) blockingReasons.put("box_not_installed");
        if (!layers.optBoolean("ok", false)) blockingReasons.put("layer_mismatch");
        if (!runtimeOverlay.optBoolean("ok", false)) blockingReasons.put("runtime_overlay_mismatch");
        if (!inputBridgeMigration.optBoolean("complete", false)) {
            blockingReasons.put("wow_input_bridge_migration_required");
        }
        if (frameGeneration.optBoolean("enabled", false) &&
                !frameGeneration.optBoolean("ready", false)) {
            blockingReasons.put("frame_generation_not_ready");
        }
        JSONObject rootRuntimeOverlays = reconcileRootRuntimeOverlays(
                runtime.buildRootRuntimeOverlaysStatus(), runtimeOverlay);
        if (!rootRuntimeOverlays.optBoolean("ok", false)) blockingReasons.put("root_runtime_overlay_mismatch");

        JSONObject json = new JSONObject();
        json.put("ready", blockingReasons.length() == 0);
        json.put("blockingReasons", blockingReasons);
        json.put("debugServerReady", running);
        json.put("permissions", permissions);
        json.put("foreground", foreground);
        json.put("installed", runtime.isInstalled());
        json.put("state", runtime.getState());
        json.put("currentSession", sessionManager.getCurrentSessionMeta());
        json.put("layers", layers);
        json.put("runtimeOverlay", runtimeOverlay);
        json.put("wowInputBridgeMigration", inputBridgeMigration);
        json.put("frameGeneration", frameGeneration);
        json.put("rootRuntimeOverlays", rootRuntimeOverlays);
        json.put("prefixProtected", runtime.isPrefixProtected());
        json.put("launchTargets", launchTargetsStatus());
        json.put("processes", processes);
        return json;
    }

    private JSONObject installBox(String body) throws JSONException {
        JSONObject request = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        String mode = request.optString("mode", "");
        if (!"initial".equals(mode)) {
            JSONObject error = error("explicit mode=initial is required");
            error.put("errorCode", "initial_mode_required");
            return error;
        }
        if (runtime.isPrefixProtected()) {
            JSONObject error = error("protected_prefix");
            error.put("errorCode", "protected_prefix");
            return error;
        }
        long startedAt = System.currentTimeMillis();
        try {
            runtime.refreshSpecFromBundledAssetForInitialInstall();
        }
        catch (RuntimeException e) {
            JSONObject error = error(e.getMessage());
            error.put("errorCode", "bundled_spec_refresh_failed");
            return error;
        }
        boolean success = new BoxInstaller(context).installInitial(false, null);
        long finishedAt = System.currentTimeMillis();
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("durationMs", finishedAt - startedAt);
        json.put("installed", runtime.isInstalled());
        json.put("layers", verifyLayers());
        if (!success) json.put("errorCode", "box_install_failed");
        return json;
    }

    private JSONObject installGpuDriverLayer() throws JSONException {
        long startedAt = System.currentTimeMillis();
        try {
            runtime.refreshSpecFromBundledAssetForLayerUpdate();
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "bundled_spec_refresh_failed");
            return json;
        }
        boolean success = new BoxInstaller(context).installGpuDriverUpdate(null);
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("durationMs", System.currentTimeMillis() - startedAt);
        json.put("layers", verifyLayers());
        if (!success) json.put("errorCode", "gpu_driver_install_failed");
        return json;
    }

    private JSONObject installGraphicsWrapperStackLayer() throws JSONException {
        long startedAt = System.currentTimeMillis();
        if (XServerDisplayActivity.hasActiveSession()) {
            JSONObject json = error("Stop the active Box session before updating graphics wrapper");
            json.put("errorCode", "active_box_session");
            return json;
        }
        try {
            runtime.refreshLayerFromBundledAssetForUpdate("graphics-wrapper-stack");
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "bundled_graphics_wrapper_refresh_failed");
            return json;
        }
        boolean success = new BoxInstaller(context).installGraphicsWrapperStackUpdate(null);
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("durationMs", System.currentTimeMillis() - startedAt);
        json.put("layers", verifyLayers());
        if (!success) json.put("errorCode", "graphics_wrapper_stack_install_failed");
        return json;
    }

    private JSONObject selectGpuDriver(String body) throws JSONException {
        long startedAt = System.currentTimeMillis();
        JSONObject request = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        String version = request.optString("version", "");
        try {
            JSONObject selected = runtime.selectTurnipCandidate(version);
            boolean success = new BoxInstaller(context).installGpuDriverUpdate(null);
            JSONObject json = new JSONObject();
            json.put("ok", success);
            json.put("durationMs", System.currentTimeMillis() - startedAt);
            json.put("selected", selected);
            json.put("layers", verifyLayers());
            if (!success) json.put("errorCode", "gpu_driver_install_failed");
            return json;
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "invalid_turnip_candidate");
            return json;
        }
    }

    private JSONObject installCpuEmuStackLayer() throws JSONException {
        long startedAt = System.currentTimeMillis();
        try {
            runtime.refreshLayerFromBundledAssetForUpdate("cpu-emu-stack");
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "bundled_cpu_emu_refresh_failed");
            return json;
        }
        boolean success = new BoxInstaller(context).installCpuEmuStackUpdate(null);
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("durationMs", System.currentTimeMillis() - startedAt);
        json.put("layers", verifyLayers());
        if (!success) json.put("errorCode", "cpu_emu_stack_install_failed");
        return json;
    }

    private JSONObject installInputBridgeStackLayer() throws JSONException {
        long startedAt = System.currentTimeMillis();
        if (hasActiveWineWorkload()) {
            JSONObject json = error("Stop all Wine processes before updating XInput");
            json.put("errorCode", "active_wine_processes");
            return json;
        }
        try {
            runtime.refreshLayerFromBundledAssetForUpdate("input-bridge-stack");
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "bundled_input_bridge_refresh_failed");
            return json;
        }
        boolean success = new BoxInstaller(context).installInputBridgeStackUpdate(null);
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("durationMs", System.currentTimeMillis() - startedAt);
        json.put("layers", verifyLayers());
        if (!success) json.put("errorCode", "input_bridge_stack_install_failed");
        return json;
    }

    private JSONObject installFrameGenerationStackLayer() throws JSONException {
        long startedAt = System.currentTimeMillis();
        if (XServerDisplayActivity.hasActiveSession()) {
            JSONObject json = error("Stop the active Box session before updating frame generation");
            json.put("errorCode", "active_box_session");
            return json;
        }
        try {
            runtime.refreshLayerFromBundledAssetForUpdate(
                    FrameGenerationManager.LAYER_TYPE);
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "bundled_frame_generation_refresh_failed");
            return json;
        }
        boolean success = new BoxInstaller(context)
                .installFrameGenerationStackUpdate(null);
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("durationMs", System.currentTimeMillis() - startedAt);
        json.put("frameGeneration", new FrameGenerationManager(context).buildStatus());
        json.put("layers", verifyLayers());
        if (!success) json.put("errorCode", "frame_generation_stack_install_failed");
        return json;
    }

    private JSONObject updateFrameGeneration(String body) throws JSONException {
        JSONObject request = body == null || body.trim().isEmpty()
                ? new JSONObject()
                : new JSONObject(body);
        FrameGenerationManager manager = new FrameGenerationManager(context);
        boolean enabled = request.has("enabled")
                ? request.optBoolean("enabled", false)
                : manager.isEnabled();
        int multiplier = request.has("multiplier")
                ? request.optInt("multiplier", manager.getMultiplier())
                : manager.getMultiplier();
        double requestedFlowScale = request.has("flowScale")
                ? request.optDouble("flowScale", manager.getFlowScale())
                : manager.getFlowScale();
        boolean performanceMode = request.has("performanceMode")
                ? request.optBoolean("performanceMode", manager.isPerformanceMode())
                : manager.isPerformanceMode();

        LosslessDllValidator.Validation dll = manager.validateInstalledDll();
        if (enabled && !dll.valid) {
            JSONObject json = error("compatible Lossless.dll is required");
            json.put("errorCode", "lossless_dll_invalid");
            json.put("frameGeneration", manager.buildStatus());
            return json;
        }

        boolean success = manager.saveSettings(
                enabled,
                multiplier,
                (float)requestedFlowScale,
                performanceMode);
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("frameGeneration", manager.buildStatus());
        if (!success) json.put("errorCode", "frame_generation_config_save_failed");
        return json;
    }

    private JSONObject installDxWrapperStackLayer() throws JSONException {
        long startedAt = System.currentTimeMillis();
        try {
            runtime.refreshLayerFromBundledAssetForUpdate("dx-wrapper-stack");
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "bundled_spec_refresh_failed");
            return json;
        }
        boolean success = new BoxInstaller(context).installDxWrapperStackUpdate(null);
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("durationMs", System.currentTimeMillis() - startedAt);
        json.put("selected", runtime.getSpec().graphics.dxvkVersion);
        json.put("layers", verifyLayers());
        if (!success) json.put("errorCode", "dx_wrapper_stack_install_failed");
        return json;
    }

    private JSONObject selectDxWrapperStack(String body) throws JSONException {
        long startedAt = System.currentTimeMillis();
        JSONObject request = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        String version = request.optString("version", "");
        try {
            JSONObject selected = runtime.selectDxvkCandidate(version);
            boolean success = new BoxInstaller(context).installDxWrapperStackUpdate(null);
            JSONObject json = new JSONObject();
            json.put("ok", success);
            json.put("durationMs", System.currentTimeMillis() - startedAt);
            json.put("selected", selected);
            json.put("layers", verifyLayers());
            if (!success) json.put("errorCode", "dx_wrapper_stack_install_failed");
            return json;
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "invalid_dxvk_candidate");
            return json;
        }
    }

    private JSONObject installWineRuntimeLayer() throws JSONException {
        long startedAt = System.currentTimeMillis();
        if (hasActiveWineWorkload()) {
            JSONObject json = error("Stop all Wine processes before updating Wine");
            json.put("errorCode", "active_wine_processes");
            return json;
        }
        BoxSpec candidate;
        BoxSpec previous;
        try {
            previous = BoxSpec.fromJson(runtime.getSpec().toJson());
            candidate = runtime.buildSpecWithBundledLayersForUpdate(
                    "wine-runtime",
                    "prefix-template");
        }
        catch (RuntimeException | JSONException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "bundled_spec_refresh_failed");
            return json;
        }
        BoxInstaller installer = new BoxInstaller(context);
        boolean success;
        synchronized (BoxInstaller.class) {
            success = installer.installWineRuntimeCandidate(candidate, null);
            if (success) {
                if (hasActiveWineWorkload()) {
                    installer.rollbackWineRuntimeCandidate(previous);
                    success = false;
                }
            }
            if (success) {
                synchronized (runtime) {
                    try {
                        if (!installer.wineRuntimeSpecMatches(previous)) {
                            throw new IllegalStateException(
                                    "Active Box spec changed during Wine runtime staging");
                        }
                        runtime.activateSpecForLayerUpdate(candidate);
                        success = installer.recordWineRuntimeCandidate(candidate);
                        if (!success) throw new IllegalStateException(
                                "Unable to record Wine runtime candidate");
                        installer.finalizeWineRuntimeCandidate(candidate);
                    }
                    catch (Exception e) {
                        Log.e(TAG, "Unable to activate Wine runtime candidate spec", e);
                        if (!hasActiveWineWorkload() &&
                                !installer.rollbackWineRuntimeCandidate(previous)) {
                            Log.e(TAG, "Unable to roll back failed Wine runtime candidate");
                        }
                        success = false;
                    }
                }
            }
        }
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("durationMs", System.currentTimeMillis() - startedAt);
        json.put("layers", verifyLayers());
        if (!success) json.put("errorCode", "wine_runtime_install_failed");
        return json;
    }

    private JSONObject reconcilePrefix() throws JSONException {
        long startedAt = System.currentTimeMillis();
        if (hasActiveWineWorkload()) {
            JSONObject json = error("Stop all Wine processes before reconciling the prefix");
            json.put("errorCode", "active_wine_processes");
            return json;
        }
        boolean success = new BoxInstaller(context).reconcileExistingPrefix(null);
        JSONObject json = new JSONObject();
        json.put("ok", success);
        json.put("durationMs", System.currentTimeMillis() - startedAt);
        json.put("layers", verifyLayers());
        if (!success) json.put("errorCode", "prefix_reconcile_failed");
        return json;
    }

    private JSONObject setLaunchTarget(String body) throws JSONException {
        JSONObject request = new JSONObject(body);
        String targetId = request.optString("targetId", "");
        try {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("target", runtime.setDefaultLaunchTarget(targetId));
            return json;
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "invalid_launch_target");
            return json;
        }
    }

    private JSONObject protectPrefix(String body) throws JSONException {
        JSONObject request = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        try {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("protection", runtime.protectPrefix(request.optString("reason", "battle.net-and-sc2-persistent-data")));
            return json;
        }
        catch (RuntimeException e) {
            JSONObject json = error(e.getMessage());
            json.put("errorCode", "prefix_protection_failed");
            return json;
        }
    }

    private JSONArray launchTargetsStatus() {
        JSONArray array = new JSONArray();
        BoxSpec spec = runtime.getSpec();
        for (BoxSpec.Target target : spec.launch.targets) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", target.id);
                item.put("kind", target.kind);
                item.put("guestPath", target.guestPath);
                item.put("exists", runtime.launchTargetExists(target));
                item.put("default", target.id.equals(spec.launch.defaultTargetId));
                item.put("launchable", !target.hidden && !(runtime.isPrefixProtected() && "installer".equalsIgnoreCase(target.kind)) &&
                        (target.requiresTargetId.isEmpty() || runtime.launchTargetExistsById(target.requiresTargetId)) &&
                        (target.hiddenWhenTargetIdExists.isEmpty() ||
                                !runtime.launchTargetExistsById(target.hiddenWhenTargetIdExists)) &&
                        (!target.requiresExists || runtime.launchTargetExists(target)));
            }
            catch (JSONException ignored) {}
            array.put(item);
        }
        return array;
    }

    private JSONObject permissions() throws JSONException {
        JSONArray required = new JSONArray();
        boolean readGranted = hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
        boolean writeGranted = hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        putPermission(required, Manifest.permission.READ_EXTERNAL_STORAGE, readGranted);
        putPermission(required, Manifest.permission.WRITE_EXTERNAL_STORAGE, writeGranted);

        File downloadDir = new File("/storage/emulated/0/Download");
        JSONObject storage = new JSONObject();
        storage.put("downloadPath", downloadDir.getAbsolutePath());
        storage.put("canReadDownload", downloadDir.canRead());
        storage.put("canWriteDownload", downloadDir.canWrite());

        JSONObject json = new JSONObject();
        json.put("ok", readGranted && writeGranted);
        json.put("errorCode", readGranted && writeGranted ? JSONObject.NULL : "missing_permission");
        json.put("required", required);
        json.put("storage", storage);
        return json;
    }

    private void putPermission(JSONArray array, String name, boolean granted) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("granted", granted);
        array.put(json);
    }

    private boolean hasPermission(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private JSONObject verifyLayers() throws JSONException {
        return verifyLayers(verifyRuntimeOverlay());
    }

    private static boolean hasActiveWineWorkload() {
        return XServerDisplayActivity.hasActiveSession() ||
                WineSessionProcessController.getInstance().hasActiveWineProcesses();
    }

    static boolean installedLayerIdentityMatches(
            BoxSpec.Layer layer,
            JSONObject installed) {
        if (layer == null || installed == null) return false;
        return installedLayerIdentityMatches(
                layer,
                installed.optString("source", ""),
                installed.optString("runtimeIdentifier", ""),
                installed.optString("checksum", ""),
                installed.optString("target", ""),
                installed.optString("winePrefixDeltaManifest", ""),
                installed.optString("winePrefixDeltaChecksum", ""));
    }

    static boolean installedLayerIdentityMatches(
            BoxSpec.Layer layer,
            String source,
            String runtimeIdentifier,
            String checksum,
            String target,
            String winePrefixDeltaManifest,
            String winePrefixDeltaChecksum) {
        if (layer == null) return false;
        return layer.source.equals(source) &&
                layer.runtimeIdentifier.equals(runtimeIdentifier) &&
                layer.checksum.equalsIgnoreCase(checksum) &&
                layer.target.equals(target) &&
                layer.winePrefixDeltaManifest.equals(winePrefixDeltaManifest) &&
                layer.winePrefixDeltaChecksum.equalsIgnoreCase(
                        winePrefixDeltaChecksum);
    }

    private JSONObject verifyLayers(JSONObject runtimeOverlay) throws JSONException {
        BoxSpec spec = runtime.getSpec();
        JSONArray installedLayers = runtime.buildInstalledLayersJson();
        JSONArray layers = new JSONArray();
        JSONArray issues = new JSONArray();
        LinkedHashMap<String, String> overlayHashes = verifiedRuntimeOverlayHashes(runtimeOverlay);
        BoxInstaller installer = new BoxInstaller(context);

        for (BoxSpec.Layer layer : spec.layers) {
            JSONObject installed = findInstalledLayer(installedLayers, layer.type);
            JSONObject item = new JSONObject();
            boolean installedPresent = installed != null && !installed.isNull("installedAt");
            String installedSource = installed != null ? installed.optString("source", "") : "";
            boolean sourceMatches = layer.source.equals(installedSource);
            boolean identityMatches = installedLayerIdentityMatches(layer, installed);
            JSONArray currentCriticalFiles = hashCriticalFiles(layer);
            JSONArray baselineCriticalFiles = installed != null ? installed.optJSONArray("criticalFiles") : null;
            boolean criticalBaselineAvailable = baselineCriticalFiles != null && baselineCriticalFiles.length() > 0;
            boolean criticalFilesPresent = allCriticalFilesExist(currentCriticalFiles);
            JSONArray overlayCoveredFiles = new JSONArray();
            boolean criticalFilesMatch = !criticalBaselineAvailable ||
                    criticalFilesMatch(baselineCriticalFiles, currentCriticalFiles, overlayHashes, overlayCoveredFiles);
            boolean winePrefixDeltaMatches = true;
            if ("prefix-template".equals(layer.type) &&
                    layer.winePrefixDeltaManifest != null &&
                    !layer.winePrefixDeltaManifest.isEmpty()) {
                try {
                    winePrefixDeltaMatches = WinePrefixDeltaReconciler.candidateStateMatches(
                            new File(
                                    runtime.getContainerRootDir(),
                                    ".wine/drive_c/windows"),
                            installer.loadWinePrefixDeltaEntries(
                                    layer,
                                    BoxRuntime.resolveRuntimeIdentifier(layer)));
                }
                catch (Exception error) {
                    Log.w(TAG, "Unable to verify Wine prefix delta", error);
                    winePrefixDeltaMatches = false;
                }
            }

            item.put("type", layer.type);
            item.put("source", layer.source);
            item.put("target", layer.target);
            item.put("installed", installedPresent);
            item.put("installedAt", installed != null ? installed.opt("installedAt") : JSONObject.NULL);
            item.put("installedSource", installedSource);
            item.put("sourceMatches", sourceMatches);
            item.put("identityMatches", identityMatches);
            item.put("criticalBaselineAvailable", criticalBaselineAvailable);
            item.put("criticalFilesPresent", criticalFilesPresent);
            item.put("criticalFilesMatch", criticalFilesMatch);
            item.put("winePrefixDeltaMatches", winePrefixDeltaMatches);
            item.put("criticalFilesCoveredByRuntimeOverlay", overlayCoveredFiles);
            item.put("baselineCriticalFiles", baselineCriticalFiles != null ? baselineCriticalFiles : new JSONArray());
            item.put("criticalFiles", currentCriticalFiles);
            layers.put(item);

            if (!installedPresent ||
                    !sourceMatches ||
                    !identityMatches ||
                    !criticalFilesPresent ||
                    !criticalFilesMatch ||
                    !winePrefixDeltaMatches) {
                JSONObject issue = new JSONObject();
                issue.put("errorCode", "layer_mismatch");
                issue.put("type", layer.type);
                issue.put("installed", installedPresent);
                issue.put("expectedSource", layer.source);
                issue.put("actualSource", installedSource);
                issue.put("identityMatches", identityMatches);
                issue.put("criticalFilesPresent", criticalFilesPresent);
                issue.put("criticalFilesMatch", criticalFilesMatch);
                issue.put("winePrefixDeltaMatches", winePrefixDeltaMatches);
                issues.put(issue);
            }
        }
        boolean pendingWineRuntimeCandidate =
                installer.hasPendingWineRuntimeCandidate(spec);
        if (pendingWineRuntimeCandidate) {
            JSONObject issue = new JSONObject();
            issue.put("errorCode", "pending_wine_runtime_candidate");
            issue.put("type", "wine-runtime");
            issues.put(issue);
        }
        boolean managedWineRuntimeBindingsMatch =
                BoxInstaller.managedWineRuntimeBindingsMatch(spec);
        if (!managedWineRuntimeBindingsMatch) {
            JSONObject issue = new JSONObject();
            issue.put("errorCode", "wine_runtime_binding_mismatch");
            issue.put("type", "wine-runtime");
            issues.put(issue);
        }

        JSONObject json = new JSONObject();
        json.put("ok", issues.length() == 0);
        json.put("errorCode", issues.length() == 0 ? JSONObject.NULL : "layer_mismatch");
        json.put("pendingWineRuntimeCandidate", pendingWineRuntimeCandidate);
        json.put("managedWineRuntimeBindingsMatch", managedWineRuntimeBindingsMatch);
        json.put("layers", layers);
        json.put("issues", issues);
        json.put("glibcRootAlias", new BoxInstaller(context).glibcLinkState());
        json.put("note", "Do not treat DLLs pushed directly into files/imagefs as valid test evidence; layer install state is authoritative.");
        return json;
    }

    private JSONObject registerRuntimeOverlay(String body) throws JSONException {
        JSONObject request = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        if (request.optBoolean("clear", false)) {
            runtime.saveRuntimeOverlay(null);
            return verifyRuntimeOverlay();
        }
        JSONArray requestedEntries = request.optJSONArray("entries");
        if (requestedEntries == null) {
            requestedEntries = new JSONArray();
            if (request.has("targetPath") || request.has("path")) requestedEntries.put(request);
        }

        JSONObject currentOverlay = runtime.getRuntimeOverlay();
        int overlayGeneration = request.has("overlayGeneration")
                ? request.optInt("overlayGeneration", 0)
                : currentOverlay.optInt("overlayGeneration", 0) + 1;
        String baseRuntimeSha = request.optString("baseRuntimeSha", currentWineRuntimeChecksum());
        long syncedAt = System.currentTimeMillis();

        JSONArray normalizedEntries = new JSONArray();
        JSONArray issues = new JSONArray();
        for (int i = 0; i < requestedEntries.length(); i++) {
            JSONObject requested = requestedEntries.optJSONObject(i);
            if (requested == null) continue;
            String targetPath = requested.optString("targetPath", requested.optString("path", ""));
            String expectedSha256 = requested.optString("sha256", requested.optString("expectedSha256", ""));
            JSONObject current = hashRuntimeOverlayFile(targetPath);
            boolean validPath = current.optBoolean("validPath", false);
            boolean exists = current.optBoolean("exists", false);
            boolean shaMatches = expectedSha256.isEmpty() || expectedSha256.equalsIgnoreCase(current.optString("sha256", ""));
            String actualSha256 = current.optString("sha256", "");
            File overlayFile = validPath && exists && shaMatches ? runtime.stageRuntimeOverlayPayload(targetPath, actualSha256) : null;

            JSONObject item = new JSONObject();
            item.put("targetPath", targetPath);
            item.put("sha256", actualSha256);
            item.put("size", current.optLong("size", 0));
            if (overlayFile != null) item.put("overlayFile", overlayFile.getAbsolutePath());
            item.put("syncedAt", syncedAt);
            normalizedEntries.put(item);

            if (!validPath || !exists || !shaMatches || overlayFile == null) {
                JSONObject issue = new JSONObject();
                issue.put("errorCode", overlayFile == null && validPath && exists && shaMatches
                        ? "runtime_overlay_stage_failed"
                        : "runtime_overlay_mismatch");
                issue.put("targetPath", targetPath);
                issue.put("validPath", validPath);
                issue.put("exists", exists);
                issue.put("expectedSha256", expectedSha256);
                issue.put("actualSha256", actualSha256);
                issues.put(issue);
            }
        }

        if (issues.length() > 0) {
            JSONObject json = new JSONObject();
            json.put("ok", false);
            json.put("errorCode", "runtime_overlay_mismatch");
            json.put("issues", issues);
            json.put("note", "Runtime overlay state was not saved because current files do not match the requested manifest.");
            return json;
        }

        JSONObject overlay = new JSONObject();
        overlay.put("baseRuntimeSha", baseRuntimeSha);
        overlay.put("overlayGeneration", overlayGeneration);
        overlay.put("syncedAt", syncedAt);
        overlay.put("entries", normalizedEntries);
        runtime.saveRuntimeOverlay(overlay);
        return verifyRuntimeOverlay();
    }

    private JSONObject verifyRuntimeOverlay() throws JSONException {
        JSONObject overlay = runtime.getRuntimeOverlay();
        JSONArray issues = new JSONArray();
        JSONArray entries = new JSONArray();

        if (overlay.length() == 0) {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("active", false);
            json.put("entries", entries);
            json.put("issues", issues);
            return json;
        }

        String expectedBaseRuntimeSha = overlay.optString("baseRuntimeSha", "");
        String currentBaseRuntimeSha = currentWineRuntimeChecksum();
        if (!expectedBaseRuntimeSha.isEmpty() && !expectedBaseRuntimeSha.equalsIgnoreCase(currentBaseRuntimeSha)) {
            JSONObject issue = new JSONObject();
            issue.put("errorCode", "runtime_overlay_base_mismatch");
            issue.put("expectedBaseRuntimeSha", expectedBaseRuntimeSha);
            issue.put("actualBaseRuntimeSha", currentBaseRuntimeSha);
            issues.put(issue);
        }

        JSONArray overlayEntries = overlay.optJSONArray("entries");
        if (overlayEntries != null) {
            for (int i = 0; i < overlayEntries.length(); i++) {
                JSONObject expected = overlayEntries.optJSONObject(i);
                if (expected == null) continue;
                String targetPath = expected.optString("targetPath", expected.optString("path", ""));
                JSONObject current = hashRuntimeOverlayFile(targetPath);
                boolean validPath = current.optBoolean("validPath", false);
                boolean exists = current.optBoolean("exists", false);
                boolean shaMatches = expected.optString("sha256", "").equalsIgnoreCase(current.optString("sha256", ""));
                String overlayFilePath = expected.optString("overlayFile", "");
                boolean overlayFilePresent = overlayFilePath.isEmpty() || new File(overlayFilePath).isFile();

                JSONObject item = new JSONObject();
                item.put("targetPath", targetPath);
                item.put("expectedSha256", expected.optString("sha256", ""));
                item.put("actualSha256", current.optString("sha256", ""));
                item.put("expectedSize", expected.optLong("size", 0));
                item.put("actualSize", current.optLong("size", 0));
                item.put("validPath", validPath);
                item.put("exists", exists);
                item.put("shaMatches", shaMatches);
                if (!overlayFilePath.isEmpty()) {
                    item.put("overlayFile", overlayFilePath);
                    item.put("overlayFilePresent", overlayFilePresent);
                }
                entries.put(item);

                if (!validPath || !exists || !shaMatches || !overlayFilePresent) {
                    JSONObject issue = new JSONObject();
                    issue.put("errorCode", !overlayFilePresent ? "runtime_overlay_payload_missing" : "runtime_overlay_mismatch");
                    issue.put("targetPath", targetPath);
                    issue.put("validPath", validPath);
                    issue.put("exists", exists);
                    issue.put("expectedSha256", expected.optString("sha256", ""));
                    issue.put("actualSha256", current.optString("sha256", ""));
                    issues.put(issue);
                }
            }
        }

        JSONObject json = new JSONObject();
        json.put("ok", issues.length() == 0);
        json.put("active", true);
        json.put("errorCode", issues.length() == 0 ? JSONObject.NULL : "runtime_overlay_mismatch");
        json.put("baseRuntimeSha", expectedBaseRuntimeSha);
        json.put("currentBaseRuntimeSha", currentBaseRuntimeSha);
        json.put("overlayGeneration", overlay.optInt("overlayGeneration", 0));
        json.put("syncedAt", overlay.optLong("syncedAt", 0));
        json.put("entries", entries);
        json.put("issues", issues);
        return json;
    }

    private JSONObject hashRuntimeOverlayFile(String targetPath) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("targetPath", targetPath);
        boolean validPath = isValidRuntimeOverlayPath(targetPath);
        json.put("validPath", validPath);
        if (!validPath) {
            json.put("exists", false);
            json.put("size", 0);
            json.put("sha256", "");
            return json;
        }

        File file = new File(ImageFs.find(context).getRootDir(), stripLeadingSlash(targetPath));
        json.put("exists", file.isFile());
        json.put("size", file.isFile() ? file.length() : 0);
        json.put("sha256", file.isFile() ? sha256(file) : "");
        return json;
    }

    private boolean isValidRuntimeOverlayPath(String targetPath) {
        if (targetPath == null || targetPath.isEmpty()) return false;
        if (!targetPath.startsWith("/")) return false;
        if (targetPath.contains("..")) return false;
        return targetPath.startsWith("/opt/") || targetPath.startsWith("/home/xuser-box/.wine/");
    }

    private String currentWineRuntimeChecksum() {
        for (BoxSpec.Layer layer : runtime.getSpec().layers) {
            if ("wine-runtime".equals(layer.type)) return layer.checksum != null ? layer.checksum : "";
        }
        return "";
    }

    private JSONObject findInstalledLayer(JSONArray installedLayers, String type) {
        if (installedLayers == null) return null;
        for (int i = 0; i < installedLayers.length(); i++) {
            JSONObject item = installedLayers.optJSONObject(i);
            if (item != null && type.equals(item.optString("type", ""))) return item;
        }
        return null;
    }

    private boolean criticalFilesMatch(JSONArray baseline, JSONArray current,
                                       Map<String, String> overlayHashes,
                                       JSONArray overlayCoveredFiles) {
        for (int i = 0; i < baseline.length(); i++) {
            JSONObject expected = baseline.optJSONObject(i);
            if (expected == null) continue;
            String path = expected.optString("path", "");
            JSONObject actual = findCriticalFile(current, path);
            if (actual == null) return false;
            boolean baselineMatches =
                    expected.optBoolean("exists", false) == actual.optBoolean("exists", false) &&
                    expected.optLong("size", -1) == actual.optLong("size", -2) &&
                    expected.optString("sha256", "").equals(actual.optString("sha256", ""));
            if (baselineMatches) continue;

            String overlaySha = overlayHashes.get(path);
            boolean overlayMatches = actual.optBoolean("exists", false) &&
                    overlaySha != null && overlaySha.equalsIgnoreCase(actual.optString("sha256", ""));
            if (!overlayMatches) return false;
            overlayCoveredFiles.put(path);
        }
        return true;
    }

    private LinkedHashMap<String, String> verifiedRuntimeOverlayHashes(JSONObject runtimeOverlay) {
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        if (runtimeOverlay == null || !runtimeOverlay.optBoolean("ok", false) ||
                !runtimeOverlay.optBoolean("active", false)) return hashes;

        JSONArray entries = runtimeOverlay.optJSONArray("entries");
        if (entries == null) return hashes;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !entry.optBoolean("validPath", false) ||
                    !entry.optBoolean("exists", false) || !entry.optBoolean("shaMatches", false)) continue;
            String targetPath = entry.optString("targetPath", "");
            String actualSha256 = entry.optString("actualSha256", "");
            if (!targetPath.isEmpty() && !actualSha256.isEmpty()) {
                hashes.put(targetPath, actualSha256);
            }
        }
        return hashes;
    }

    private JSONObject reconcileRootRuntimeOverlays(JSONObject rootStatus,
                                                    JSONObject runtimeOverlay) throws JSONException {
        JSONObject result = new JSONObject(rootStatus.toString());
        boolean rawOk = rootStatus.optBoolean("ok", false);
        boolean effectiveOk = true;
        LinkedHashMap<String, String> overlayHashes = verifiedRuntimeOverlayHashes(runtimeOverlay);
        JSONArray overlays = result.optJSONArray("overlays");

        if (overlays != null) {
            for (int i = 0; i < overlays.length(); i++) {
                JSONObject overlay = overlays.optJSONObject(i);
                if (overlay == null) {
                    effectiveOk = false;
                    continue;
                }
                boolean overlayEffectiveOk = true;
                JSONArray entries = overlay.optJSONArray("entries");
                if (entries == null || entries.length() == 0) {
                    overlayEffectiveOk = false;
                }
                else {
                    for (int j = 0; j < entries.length(); j++) {
                        JSONObject entry = entries.optJSONObject(j);
                        if (entry == null) {
                            overlayEffectiveOk = false;
                            continue;
                        }
                        boolean entryOk = entry.optBoolean("ok", false);
                        if (!entryOk) {
                            String targetPath = entry.optString("targetPath", "");
                            String actualSha256 = entry.optString("actualSha256", "");
                            String currentOverlaySha = overlayHashes.get(targetPath);
                            boolean superseded = currentOverlaySha != null &&
                                    currentOverlaySha.equalsIgnoreCase(actualSha256);
                            entry.put("supersededByRuntimeOverlay", superseded);
                            entry.put("effectiveOk", superseded);
                            entryOk = superseded;
                        }
                        else {
                            entry.put("effectiveOk", true);
                        }
                        overlayEffectiveOk &= entryOk;
                    }
                }
                overlay.put("rawOk", overlay.optBoolean("ok", false));
                overlay.put("ok", overlayEffectiveOk);
                effectiveOk &= overlayEffectiveOk;
            }
        }
        result.put("rawOk", rawOk);
        result.put("ok", effectiveOk);
        result.put("runtimeOverlaySupersessionActive", !overlayHashes.isEmpty());
        return result;
    }

    private boolean allCriticalFilesExist(JSONArray current) {
        if (current == null) return true;
        for (int i = 0; i < current.length(); i++) {
            JSONObject item = current.optJSONObject(i);
            if (item == null) continue;
            if (!item.optBoolean("exists", false)) return false;
        }
        return true;
    }

    private JSONObject findCriticalFile(JSONArray files, String path) {
        if (files == null) return null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item != null && path.equals(item.optString("path", ""))) return item;
        }
        return null;
    }

    private JSONArray hashCriticalFiles(BoxSpec.Layer layer) throws JSONException {
        JSONArray files = new JSONArray();
        String[] paths = new BoxInstaller(context).criticalPathsForLayer(layer);
        File rootDir = ImageFs.find(context).getRootDir();
        for (String path : paths) {
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

    private File resolveCriticalFile(File rootDir, String path) {
        final String appPrefix = "@app/";
        if (path != null && path.startsWith(appPrefix)) {
            return new File(context.getFilesDir(), path.substring(appPrefix.length()));
        }
        return new File(rootDir, stripLeadingSlash(path));
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
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        }
        catch (Exception e) {
            return "";
        }
    }

    private JSONObject stopContainer() throws JSONException {
        String currentSessionId = sessionManager.getCurrentSessionId();
        boolean hasSession = currentSessionId != null && !currentSessionId.isEmpty();
        boolean requested = XServerDisplayActivity.requestDebugStop();
        JSONObject json = new JSONObject();
        json.put("ok", requested || !hasSession);
        json.put("requested", requested);
        json.put("sessionId", currentSessionId != null ? currentSessionId : "");
        if (!requested && hasSession) {
            json.put("errorCode", "cleanup_failed");
            json.put("error", "active session exists but no foreground runtime accepted stop request");
        }
        else {
            json.put("message", requested ? "container stop requested" : "no active container");
            BoxDebugEventBus.publish("container-stop", json);
        }
        return json;
    }

    private JSONObject executables() throws JSONException {
        JSONArray array = new JSONArray();
        for (BoxExecutable executable : runtime.scanExecutables()) {
            JSONObject json = new JSONObject();
            json.put("name", executable.name);
            json.put("absolutePath", executable.absolutePath);
            json.put("relativePath", executable.relativePath);
            json.put("drive", executable.drive);
            json.put("lastModified", executable.lastModified);
            array.put(json);
        }
        return wrapArray("executables", array);
    }

    private JSONObject logs(String path, String body) throws JSONException {
        String kind = "box";
        String sessionId = sessionManager.getCurrentSessionId();
        int idx = path.indexOf('?');
        if (idx != -1) {
            String query = path.substring(idx + 1);
            for (String part : query.split("&")) {
                if (part.startsWith("kind=")) kind = part.substring("kind=".length());
                if (part.startsWith("session=")) {
                    String value = part.substring("session=".length());
                    if (!value.isEmpty() && !"current".equals(value)) sessionId = value;
                }
            }
        }
        File file = sessionManager.resolveLogFile(sessionId, kind);
        JSONArray lines = new JSONArray();
        if (file.isFile()) {
            ArrayList<String> raw = FileUtils.readLines(file);
            int start = Math.max(0, raw.size() - 200);
            for (int i = start; i < raw.size(); i++) lines.put(raw.get(i));
        }
        JSONObject json = wrapArray("lines", lines);
        json.put("sessionId", sessionId != null ? sessionId : "");
        json.put("kind", kind);
        return json;
    }

    private boolean isAuthorized(Map<String, String> headers) {
        String auth = headers.get("x-box-token");
        return runtime.getDebugToken().equals(auth);
    }

    private void handleWebSocket(Socket socket, OutputStream output, Map<String, String> headers) throws Exception {
        String key = headers.get("sec-websocket-key");
        if (key == null || !isAuthorized(headers)) {
            writeStatus(output, 401, "Unauthorized");
            return;
        }

        String accept = websocketAccept(key);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write("HTTP/1.1 101 Switching Protocols\r\n");
        writer.write("Upgrade: websocket\r\n");
        writer.write("Connection: Upgrade\r\n");
        writer.write("Sec-WebSocket-Accept: " + accept + "\r\n\r\n");
        writer.flush();

        synchronized (websocketClients) {
            websocketClients.add(socket);
        }
        sendWebSocketText(socket, BoxDebugEventBus.buildEvent("stream-connected", new JSONObject()).toString());
        sendWebSocketText(socket, BoxDebugEventBus.buildEvent("snapshot", runtime.buildDebugSnapshot()).toString());
        while (running && !socket.isClosed()) {
            try {
                if (socket.getInputStream().read() == -1) break;
            }
            catch (IOException ignored) {
                break;
            }
        }
        synchronized (websocketClients) {
            websocketClients.remove(socket);
        }
    }

    private void enqueueWebSocketBroadcast(String message) {
        ExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown()) return;
        currentExecutor.execute(() -> broadcastWebSocketText(message));
    }

    private void broadcastWebSocketText(String message) {
        byte[] bytes = buildWebSocketTextFrame(message);
        if (bytes == null) return;
        synchronized (websocketClients) {
            ArrayList<Socket> stale = new ArrayList<>();
            for (Socket client : websocketClients) {
                if (!sendWebSocketFrame(client, bytes)) stale.add(client);
            }
            websocketClients.removeAll(stale);
        }
    }

    private void sendWebSocketText(Socket client, String message) {
        byte[] bytes = buildWebSocketTextFrame(message);
        if (bytes == null) return;
        sendWebSocketFrame(client, bytes);
    }

    private static byte[] buildWebSocketTextFrame(String message) {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);
        if (payload.length <= 125) {
            frame.write(payload.length);
        } else if (payload.length <= 65535) {
            frame.write(126);
            frame.write((payload.length >> 8) & 0xff);
            frame.write(payload.length & 0xff);
        } else {
            frame.write(127);
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) ((length >> shift) & 0xff));
            }
        }
        frame.write(payload, 0, payload.length);
        return frame.toByteArray();
    }

    private boolean sendWebSocketFrame(Socket client, byte[] bytes) {
        try {
            client.getOutputStream().write(bytes);
            client.getOutputStream().flush();
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static String websocketAccept(String key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private void writeJson(OutputStream output, int status, JSONObject json) throws IOException {
        String text;
        try {
            text = json.toString(2);
        }
        catch (JSONException e) {
            text = json.toString();
        }
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length + "\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    private void writeStatus(OutputStream output, int code, String text) throws IOException {
        String body = text + "\n";
        String headers = "HTTP/1.1 " + code + " " + text + "\r\n" +
                "Content-Length: " + body.length() + "\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(body.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static JSONObject wrapArray(String key, JSONArray array) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(key, array);
        return json;
    }

    private static JSONObject ok(String message) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("ok", true);
        json.put("message", message);
        return json;
    }

    private static JSONObject okWithSession(String message, String sessionId) throws JSONException {
        JSONObject json = ok(message);
        json.put("sessionId", sessionId != null ? sessionId : "");
        return json;
    }

    private static JSONObject error(String message) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("ok", false);
        json.put("error", message);
        return json;
    }
}
