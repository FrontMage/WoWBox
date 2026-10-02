package com.winlator.box;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.winlator.XServerDisplayActivity;
import com.winlator.core.FileUtils;
import com.winlator.core.WineSessionProcessController;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TitanProfileSyncManager {
    public enum Action {
        TEST,
        SYNC
    }

    public interface Listener {
        void onStateChanged(State state);
        void onHostKeyTrustRequired(TitanProfileHostKeyStore.HostKeyInfo hostKey);
    }

    public static final class State {
        public final boolean active;
        public final boolean awaitingTrust;
        public final boolean success;
        public final int progress;
        public final String phase;
        public final String message;

        State(
                boolean active,
                boolean awaitingTrust,
                boolean success,
                int progress,
                String phase,
                String message) {
            this.active = active;
            this.awaitingTrust = awaitingTrust;
            this.success = success;
            this.progress = progress;
            this.phase = phase;
            this.message = message;
        }
    }

    private static final long MAX_FILES = 250_000L;
    private static final long MAX_BYTES = 16L * 1024L * 1024L * 1024L;
    private static final long MIN_FREE_MARGIN = 512L * 1024L * 1024L;

    private final Context context;
    private final BoxRuntime runtime;
    private final TitanProfileCredentialStore credentialStore;
    private final TitanProfileHostKeyStore hostKeyStore;
    private final TitanProfileSshClient sshClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();

    private volatile Listener listener;
    private volatile State state =
            new State(false, false, false, 0, "idle", "Ready");
    private volatile boolean activating;
    private volatile boolean closed;
    private volatile long lastProgressUpdate;
    private Action pendingAction;
    private TitanProfileSyncConfig pendingConfig;
    private TitanProfileHostKeyStore.HostKeyInfo pendingHostKey;

    public TitanProfileSyncManager(Context context) {
        this.context = context.getApplicationContext();
        runtime = BoxRuntime.get(context);
        credentialStore = new TitanProfileCredentialStore(context);
        hostKeyStore = new TitanProfileHostKeyStore(context);
        sshClient = new TitanProfileSshClient(hostKeyStore);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) dispatchState(state);
    }

    public State getState() {
        return state;
    }

    public boolean hasSavedPassword() {
        return credentialStore.hasPassword();
    }

    public String getTrustedFingerprint(TitanProfileSyncConfig config) {
        TitanProfileHostKeyStore.HostKeyInfo info = hostKeyStore.load();
        return info != null && info.hostIdentity.equals(config.hostIdentity())
                ? info.fingerprint : "";
    }

    public String getLastSummary() {
        File file = getLastResultFile();
        if (!file.isFile()) return "No successful sync recorded";
        try {
            JSONObject json = new JSONObject(AtomicFileSupport.readUtf8(file));
            return String.format(
                    Locale.US,
                    "Last sync: %s\nWTF: %,d files\nAddOns: %,d files\nBytes: %,d\nSnapshot: %s",
                    json.optString("completedAt", "unknown"),
                    json.optLong("wtfFiles", 0),
                    json.optLong("addonFiles", 0),
                    json.optLong("totalBytes", 0),
                    json.optString("snapshot", "unknown"));
        }
        catch (Exception error) {
            return "Last sync record is unreadable";
        }
    }

    public void start(
            Action action,
            TitanProfileSyncConfig config,
            String newPassword) {
        synchronized (stateLock) {
            if (state.active || state.awaitingTrust || closed) return;
        }
        String validation = config.validate();
        if (!validation.isEmpty()) {
            updateState(false, false, false, 0, "error", validation);
            return;
        }
        try {
            config.save(context);
            if (newPassword != null && !newPassword.isEmpty()) {
                credentialStore.savePassword(newPassword);
            }
            if (!credentialStore.hasPassword()) {
                updateState(false, false, false, 0, "error", "SSH password is required");
                return;
            }
        }
        catch (Exception error) {
            updateState(false, false, false, 0, "error", safeMessage(error));
            return;
        }
        sshClient.resetCancellation();
        pendingAction = action;
        pendingConfig = config;
        if (!hostKeyStore.hasKeyFor(config)) {
            probeHostKey(config);
            return;
        }
        runPendingAction();
    }

    public void trustAndContinue() {
        TitanProfileHostKeyStore.HostKeyInfo info = pendingHostKey;
        if (info == null || pendingConfig == null || pendingAction == null) return;
        try {
            hostKeyStore.save(info);
            pendingHostKey = null;
            updateState(false, false, false, 0, "trusted",
                    "Trusted " + info.fingerprint);
            runPendingAction();
        }
        catch (Exception error) {
            updateState(false, false, false, 0, "error", safeMessage(error));
        }
    }

    public void rejectTrust() {
        pendingHostKey = null;
        pendingAction = null;
        pendingConfig = null;
        updateState(false, false, false, 0, "cancelled", "Host key was not trusted");
    }

    public void forgetTrustedHost() {
        if (state.active) return;
        hostKeyStore.clear();
        updateState(false, false, false, 0, "idle", "Trusted SSH host key removed");
    }

    public void cancel() {
        if (activating) return;
        sshClient.cancel();
        pendingHostKey = null;
        pendingAction = null;
        pendingConfig = null;
        if (state.active || state.awaitingTrust) {
            updateState(false, false, false, state.progress, "cancelled", "Sync cancelled");
        }
    }

    public void close() {
        closed = true;
        listener = null;
        cancel();
        executor.shutdown();
    }

    private void probeHostKey(TitanProfileSyncConfig config) {
        updateState(true, false, false, 0, "host-key", "Reading SSH host key…");
        executor.execute(() -> {
            try {
                TitanProfileHostKeyStore.HostKeyInfo info = sshClient.probeHostKey(config);
                if (closed) return;
                pendingHostKey = info;
                updateState(false, true, false, 0, "host-key", "Confirm SSH host key");
                Listener current = listener;
                if (current != null) {
                    mainHandler.post(() -> {
                        Listener target = listener;
                        if (target != null) target.onHostKeyTrustRequired(info);
                    });
                }
            }
            catch (Exception error) {
                fail(error);
            }
        });
    }

    private void runPendingAction() {
        Action action = pendingAction;
        TitanProfileSyncConfig config = pendingConfig;
        pendingAction = null;
        pendingConfig = null;
        if (action == null || config == null) return;
        updateState(true, false, false, 0, "connecting", "Connecting to SSH host…");
        executor.execute(() -> {
            try {
                String password = credentialStore.loadPassword();
                if (password.isEmpty()) throw new IOException("SSH password is required");
                if (action == Action.TEST) runTest(config, password);
                else runSync(config, password);
            }
            catch (Exception error) {
                fail(error);
            }
        });
    }

    private void runTest(TitanProfileSyncConfig config, String password) throws Exception {
        updateState(true, false, false, 20, "preflight", "Checking the Windows source…");
        TitanProfileSshClient.RemoteSnapshot snapshot =
                sshClient.readRemoteSnapshot(config, password);
        requireRemoteLimits(snapshot);
        updateState(
                false,
                false,
                true,
                100,
                "complete",
                String.format(
                        Locale.US,
                        "Connection ready: %,d WTF files, %,d AddOns files, %,d bytes",
                        snapshot.wtfFiles,
                        snapshot.addonFiles,
                        snapshot.totalBytes));
    }

    private void runSync(TitanProfileSyncConfig config, String password) throws Exception {
        File targetRoot = requireLocalReady();
        File workDir = new File(runtime.getContainerRootDir(), ".winlator/titan-profile-sync");
        TitanProfileSyncTransaction transaction =
                new TitanProfileSyncTransaction(targetRoot, workDir);
        transaction.recover();

        updateState(true, false, false, 5, "preflight", "Checking the Windows source…");
        TitanProfileSshClient.RemoteSnapshot before =
                sshClient.readRemoteSnapshot(config, password);
        requireRemoteLimits(before);
        requireFreeSpace(workDir, before.totalBytes);

        File stage = transaction.prepareStaging();
        try {
            updateState(true, false, false, 10, "transfer", "Receiving WTF and AddOns…");
            lastProgressUpdate = 0;
            TitanProfileSshClient.TransferResult transfer = sshClient.transfer(
                    config,
                    password,
                    stage,
                    new SafeTitanTarExtractor.Limits(MAX_FILES, MAX_BYTES),
                    (files, bytes) -> updateTransferProgress(before, files, bytes)
            );
            SafeTitanTarExtractor.Result extracted = transfer.extracted;
            requireExtractedMatches(before, extracted);

            updateState(true, false, false, 92, "verify-source",
                    "Checking that the Windows source stayed unchanged…");
            TitanProfileSshClient.RemoteSnapshot after =
                    sshClient.readRemoteSnapshot(config, password);
            if (!before.sameTree(after)) {
                throw new IOException("The Windows source changed during sync; nothing was installed");
            }

            sshClient.throwIfCancelled();
            requireLocalReady();
            updateState(true, false, false, 96, "activate",
                    "Activating the verified Titan profile…");
            activating = true;
            try {
                transaction.activate(
                        extracted.wtfFiles,
                        extracted.addonFiles,
                        extracted.totalBytes);
            }
            finally {
                activating = false;
            }
            saveLastResult(after, extracted);
            updateState(
                    false,
                    false,
                    true,
                    100,
                    "complete",
                    String.format(
                            Locale.US,
                            "Sync complete: %,d WTF files and %,d AddOns files",
                            extracted.wtfFiles,
                            extracted.addonFiles));
        }
        catch (Exception error) {
            transaction.discardStaging();
            throw error;
        }
    }

    private File requireLocalReady() throws IOException {
        if (!runtime.isInstalled()) throw new IOException("Box is not installed");
        if (XServerDisplayActivity.hasActiveSession()
                || WineSessionProcessController.getInstance().hasActiveWineProcesses()) {
            throw new IOException("Stop every Windows program before syncing");
        }
        File target = runtime.resolveGuestDirectory(TitanProfileSyncConfig.TARGET_GUEST_PATH);
        if (target == null || !target.isDirectory()) {
            throw new IOException("China Titan is not installed in the standard Box");
        }
        return target;
    }

    private static void requireRemoteLimits(
            TitanProfileSshClient.RemoteSnapshot snapshot) throws IOException {
        if (snapshot.totalFiles > MAX_FILES) {
            throw new IOException("Remote profile exceeds the 250,000-file limit");
        }
        if (snapshot.totalBytes > MAX_BYTES) {
            throw new IOException("Remote profile exceeds the 16 GiB limit");
        }
    }

    static void requireFreeSpace(File path, long sourceBytes) throws IOException {
        File probe = path;
        while (probe != null && !probe.exists()) probe = probe.getParentFile();
        if (probe == null) throw new IOException("Unable to determine free storage");
        if (!hasRequiredSpace(probe.getUsableSpace(), sourceBytes)) {
            throw new IOException("Not enough free space for safe staging");
        }
    }

    static boolean hasRequiredSpace(long usableBytes, long sourceBytes) {
        if (usableBytes < 0 || sourceBytes < 0) return false;
        long margin = Math.max(MIN_FREE_MARGIN, sourceBytes / 10);
        return sourceBytes <= Long.MAX_VALUE - margin
                && usableBytes >= sourceBytes + margin;
    }

    private static void requireExtractedMatches(
            TitanProfileSshClient.RemoteSnapshot remote,
            SafeTitanTarExtractor.Result extracted) throws IOException {
        if (extracted.wtfFiles != remote.wtfFiles
                || extracted.addonFiles != remote.addonFiles
                || extracted.totalFiles != remote.totalFiles
                || extracted.totalBytes != remote.totalBytes
                || !extracted.contentSnapshot.equals(remote.contentSnapshot)) {
            throw new IOException("Received files do not match the Windows snapshot");
        }
    }

    private void updateTransferProgress(
            TitanProfileSshClient.RemoteSnapshot remote, long files, long bytes) {
        long now = System.currentTimeMillis();
        if (now - lastProgressUpdate < 100 && bytes < remote.totalBytes) return;
        lastProgressUpdate = now;
        int progress = remote.totalBytes > 0
                ? 10 + (int) Math.min(80, (bytes * 80) / remote.totalBytes)
                : 90;
        updateState(
                true,
                false,
                false,
                progress,
                "transfer",
                String.format(
                        Locale.US,
                        "Receiving: %,d / %,d files, %,d / %,d bytes",
                        files,
                        remote.totalFiles,
                        bytes,
                        remote.totalBytes));
    }

    private void saveLastResult(
            TitanProfileSshClient.RemoteSnapshot snapshot,
            SafeTitanTarExtractor.Result extracted) throws Exception {
        JSONObject json = new JSONObject();
        json.put("version", 1);
        json.put("completedAt", new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()));
        json.put("wtfFiles", extracted.wtfFiles);
        json.put("addonFiles", extracted.addonFiles);
        json.put("totalBytes", extracted.totalBytes);
        json.put("snapshot", snapshot.snapshot);
        json.put("contentSnapshot", extracted.contentSnapshot);
        json.put("tarSha256", extracted.tarSha256);
        AtomicFileSupport.writeUtf8(getLastResultFile(), json.toString());
    }

    private File getLastResultFile() {
        return new File(BoxPaths.getBoxDir(context), "titan-profile-sync-last.json");
    }

    private void fail(Exception error) {
        if (closed) return;
        String message = safeMessage(error);
        boolean cancelled = message.toLowerCase(Locale.ROOT).contains("cancel");
        updateState(
                false,
                false,
                false,
                state.progress,
                cancelled ? "cancelled" : "error",
                message);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }

    private void updateState(
            boolean active,
            boolean awaitingTrust,
            boolean success,
            int progress,
            String phase,
            String message) {
        State next = new State(active, awaitingTrust, success, progress, phase, message);
        state = next;
        dispatchState(next);
    }

    private void dispatchState(State value) {
        Listener current = listener;
        if (current == null || closed) return;
        mainHandler.post(() -> {
            Listener target = listener;
            if (target != null && !closed) target.onStateChanged(value);
        });
    }
}
