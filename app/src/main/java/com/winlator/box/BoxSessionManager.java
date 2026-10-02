package com.winlator.box;

import android.content.Context;

import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public class BoxSessionManager {
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_FINISHED = "finished";
    private static final int MAX_SESSIONS = 20;

    private final Context context;
    private final BoxRuntime runtime;

    public BoxSessionManager(Context context) {
        this.context = context.getApplicationContext();
        this.runtime = BoxRuntime.get(context);
    }

    public synchronized String createSession(String launchMode, String targetExecutable) {
        return createSession(launchMode, targetExecutable, BoxDebugConfig.none());
    }

    public synchronized String createSession(String launchMode, String targetExecutable, BoxDebugConfig debugConfig) {
        File sessionsDir = getSessionsDir();
        if (!sessionsDir.isDirectory()) sessionsDir.mkdirs();

        String sessionId = buildSessionId(targetExecutable);
        File sessionDir = getSessionDir(sessionId);
        sessionDir.mkdirs();

        JSONObject meta = new JSONObject();
        try {
            meta.put("sessionId", sessionId);
            meta.put("createdAt", System.currentTimeMillis());
            meta.put("launchMode", launchMode);
            meta.put("targetExecutable", targetExecutable != null ? targetExecutable : "");
            meta.put("status", STATUS_RUNNING);
            meta.put("appVersion", 1);
        }
        catch (JSONException ignored) {}

        writeJson(getMetaFile(sessionId), meta);
        writeJson(getSpecFile(sessionId), safeRuntimeSpec());
        writeJson(getEnvFile(sessionId), safeEnv());
        writeJson(getMountsFile(sessionId), safeMounts());
        appendBoxLog(sessionId, "session-created launchMode=" + launchMode + " target=" + targetExecutable);
        publishSessionEvent("session-created", sessionId, meta);
        if (debugConfig != null && debugConfig.isEnabled()) {
            writeJson(getDebugMetaFile(sessionId), debugConfig.toJson());
            appendBoxLog(sessionId, "debug-config profile=" + debugConfig.profile + " focus=" + debugConfig.focus);
        }

        JSONObject state = runtime.getState();
        try {
            state.put("currentSessionId", sessionId);
        }
        catch (JSONException ignored) {}
        runtime.saveState(state);

        cleanupOldSessions();
        return sessionId;
    }

    public synchronized void markLaunchRequested(String sessionId) {
        appendBoxLog(sessionId, "launch-requested");
        publishSessionEvent("launch-requested", sessionId, readSessionMeta(sessionId));
    }

    public synchronized void updateGuestPid(String sessionId, int pid) {
        updateMeta(sessionId, "guestPid", pid);
        appendBoxLog(sessionId, "guest-pid=" + pid);
        publishSessionEvent("guest-pid", sessionId, readSessionMeta(sessionId));
    }

    public synchronized void updateContainerPid(String sessionId, int pid) {
        updateMeta(sessionId, "containerPid", pid);
        publishSessionEvent("container-pid", sessionId, readSessionMeta(sessionId));
    }

    public synchronized void markFinished(String sessionId, int exitStatus) {
        updateMeta(sessionId, "finishedAt", System.currentTimeMillis());
        updateMeta(sessionId, "exitStatus", exitStatus);
        updateMeta(sessionId, "status", STATUS_FINISHED);
        appendBoxLog(sessionId, "finished exitStatus=" + exitStatus);
        publishSessionEvent("session-finished", sessionId, readSessionMeta(sessionId));
    }

    public synchronized boolean markFinishedIfRunning(String sessionId, int exitStatus) {
        if (sessionId == null || sessionId.isEmpty()) return false;
        JSONObject meta = readSessionMeta(sessionId);
        if (STATUS_FINISHED.equals(meta.optString("status", ""))) return false;
        markFinished(sessionId, exitStatus);
        return true;
    }

    public synchronized void markEvent(String sessionId, String marker) {
        appendBoxLog(sessionId, marker);
        JSONObject payload = readSessionMeta(sessionId);
        try {
            payload.put("marker", marker != null ? marker : "");
        }
        catch (JSONException ignored) {}
        publishSessionEvent("session-event", sessionId, payload);
    }

    public synchronized String getCurrentSessionId() {
        return runtime.getState().optString("currentSessionId", "");
    }

    public synchronized JSONObject getCurrentSessionMeta() {
        String sessionId = getCurrentSessionId();
        return sessionId.isEmpty() ? new JSONObject() : readJson(getMetaFile(sessionId));
    }

    public synchronized JSONArray listSessions() {
        JSONArray array = new JSONArray();
        ArrayList<File> dirs = new ArrayList<>();
        File[] files = getSessionsDir().listFiles();
        if (files != null) {
            for (File file : files) if (file.isDirectory()) dirs.add(file);
        }
        Collections.sort(dirs, Comparator.comparing(File::getName).reversed());
        for (File dir : dirs) array.put(readJson(new File(dir, "meta.json")));
        return array;
    }

    public File getSessionsDir() {
        return new File(BoxPaths.getBoxDir(context), "sessions");
    }

    public File getSessionDir(String sessionId) {
        return new File(getSessionsDir(), sessionId);
    }

    public File getMetaFile(String sessionId) {
        return new File(getSessionDir(sessionId), "meta.json");
    }

    public File getSpecFile(String sessionId) {
        return new File(getSessionDir(sessionId), "spec.json");
    }

    public File getEnvFile(String sessionId) {
        return new File(getSessionDir(sessionId), "env.json");
    }

    public File getMountsFile(String sessionId) {
        return new File(getSessionDir(sessionId), "mounts.json");
    }

    public File getBoxLogFile(String sessionId) {
        return new File(getSessionDir(sessionId), "box.log");
    }

    public File getDebugMetaFile(String sessionId) {
        return new File(getSessionDir(sessionId), "debug-meta.json");
    }

    public File getGuestLogFile(String sessionId) {
        return new File(getSessionDir(sessionId), "guest.log");
    }

    public File getWrapperLogFile(String sessionId) {
        return new File(getSessionDir(sessionId), "wrapper.log");
    }

    public File getDxvkLogFile(String sessionId) {
        return new File(getSessionDir(sessionId), "dxvk.log");
    }

    public File getFexLogFile(String sessionId) {
        return new File(getSessionDir(sessionId), "fex.log");
    }

    public File getSummaryFile(String sessionId) {
        return new File(getSessionDir(sessionId), "summary.json");
    }

    public synchronized void writeEffectiveEnv(String sessionId, EnvVars envVars) {
        if (sessionId == null || sessionId.isEmpty() || envVars == null) return;
        writeJson(getEnvFile(sessionId), envToJson(envVars));
    }

    public File getTimelineFile(String sessionId) {
        return new File(getSessionDir(sessionId), "timeline.json");
    }

    public File getFailuresFile(String sessionId) {
        return new File(getSessionDir(sessionId), "failures.json");
    }

    public File getHotloopsFile(String sessionId) {
        return new File(getSessionDir(sessionId), "hotloops.json");
    }

    public JSONObject readSessionMeta(String sessionId) {
        return readJson(getMetaFile(sessionId));
    }

    public JSONObject readDebugMeta(String sessionId) {
        return readJson(getDebugMetaFile(sessionId));
    }

    public void writeDebugMeta(String sessionId, JSONObject json) {
        writeJson(getDebugMetaFile(sessionId), json != null ? json : new JSONObject());
    }

    public void writeArtifact(String sessionId, String name, String contents) {
        if (sessionId == null || sessionId.isEmpty() || name == null || name.isEmpty()) return;
        File file = new File(getSessionDir(sessionId), name);
        file.getParentFile().mkdirs();
        FileUtils.writeString(file, contents != null ? contents : "");
    }

    public synchronized void appendJsonLine(String sessionId, String name, JSONObject event) {
        if (sessionId == null || sessionId.isEmpty() || name == null || name.isEmpty() || event == null) return;
        File file = new File(getSessionDir(sessionId), name);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        byte[] bytes = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(bytes);
        }
        catch (IOException ignored) {}
    }

    public File resolveLogFile(String sessionId, String kind) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        if ("box".equals(kind)) return getBoxLogFile(sessionId);
        if ("wine".equals(kind) || "guest".equals(kind)) return getGuestLogFile(sessionId);
        if ("wrapper".equals(kind)) return getWrapperLogFile(sessionId);
        if ("dxvk".equals(kind)) return getDxvkLogFile(sessionId);
        if ("fex".equals(kind)) return getFexLogFile(sessionId);
        return new File(getSessionDir(sessionId), kind + ".log");
    }

    private void appendBoxLog(String sessionId, String line) {
        if (sessionId == null || sessionId.isEmpty()) return;
        File logFile = getBoxLogFile(sessionId);
        logFile.getParentFile().mkdirs();
        String existing = logFile.isFile() ? FileUtils.readString(logFile) : "";
        String prefix = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String entry = prefix + " " + line;
        FileUtils.writeString(logFile, existing + entry + "\n");

        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("line", line);
            payload.put("entry", entry);
        }
        catch (JSONException ignored) {}
        BoxDebugEventBus.publish("session-event", payload);
    }

    private void updateMeta(String sessionId, String key, Object value) {
        if (sessionId == null || sessionId.isEmpty()) return;
        JSONObject json = readJson(getMetaFile(sessionId));
        try {
            json.put(key, value);
        }
        catch (JSONException ignored) {}
        writeJson(getMetaFile(sessionId), json);
    }

    private void publishSessionEvent(String type, String sessionId, JSONObject payload) {
        if (sessionId == null || sessionId.isEmpty()) return;
        JSONObject eventPayload = payload != null ? payload : new JSONObject();
        try {
            eventPayload.put("sessionId", sessionId);
        }
        catch (JSONException ignored) {}
        BoxDebugEventBus.publish(type, eventPayload);
    }

    private JSONObject readJson(File file) {
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(FileUtils.readString(file));
        }
        catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private void writeJson(File file, JSONObject json) {
        file.getParentFile().mkdirs();
        try {
            FileUtils.writeString(file, json.toString(2));
        }
        catch (JSONException ignored) {
            FileUtils.writeString(file, json.toString());
        }
    }

    private JSONObject safeRuntimeSpec() {
        try {
            return runtime.getSpec().toJson();
        }
        catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private JSONObject safeEnv() {
        try {
            return runtime.buildEnvSnapshot().toJson();
        }
        catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private JSONObject envToJson(EnvVars envVars) {
        JSONObject json = new JSONObject();
        if (envVars == null) return json;
        try {
            for (String name : envVars) {
                JSONObject item = new JSONObject();
                item.put("value", envVars.get(name));
                item.put("source", "effective");
                json.put(name, item);
            }
        }
        catch (JSONException ignored) {}
        return json;
    }

    private JSONObject safeMounts() {
        JSONObject json = new JSONObject();
        try {
            json.put("mounts", runtime.buildMountsJson());
        }
        catch (JSONException ignored) {}
        return json;
    }

    private String buildSessionId(String targetExecutable) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String suffix = "desktop";
        if (targetExecutable != null && !targetExecutable.isEmpty()) {
            suffix = FileUtils.getBasename(targetExecutable)
                    .replaceAll("[^A-Za-z0-9._-]+", "-")
                    .toLowerCase(Locale.US);
            if (suffix.isEmpty()) suffix = "exe";
        }
        return timestamp + "-" + suffix;
    }

    private void cleanupOldSessions() {
        File[] files = getSessionsDir().listFiles();
        if (files == null || files.length <= MAX_SESSIONS) return;
        ArrayList<File> dirs = new ArrayList<>();
        for (File file : files) if (file.isDirectory()) dirs.add(file);
        Collections.sort(dirs, Comparator.comparing(File::getName).reversed());
        for (int i = MAX_SESSIONS; i < dirs.size(); i++) FileUtils.delete(dirs.get(i));
    }
}
