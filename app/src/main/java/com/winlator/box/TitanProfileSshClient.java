package com.winlator.box;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TitanProfileSshClient {
    public interface ProgressListener {
        void onProgress(long files, long bytes);
    }

    public static final class RemoteSnapshot {
        public final int wtfFiles;
        public final int addonFiles;
        public final int totalFiles;
        public final long totalBytes;
        public final String snapshot;
        public final String contentSnapshot;

        RemoteSnapshot(JSONObject json) {
            this(
                    json.optInt("wtfFiles", -1),
                    json.optInt("addonFiles", -1),
                    json.optInt("totalFiles", -1),
                    json.optLong("totalBytes", -1),
                    json.optString("snapshot", ""),
                    json.optString("contentSnapshot", ""));
        }

        RemoteSnapshot(
                int wtfFiles,
                int addonFiles,
                int totalFiles,
                long totalBytes,
                String snapshot) {
            this(wtfFiles, addonFiles, totalFiles, totalBytes, snapshot, snapshot);
        }

        RemoteSnapshot(
                int wtfFiles,
                int addonFiles,
                int totalFiles,
                long totalBytes,
                String snapshot,
                String contentSnapshot) {
            this.wtfFiles = wtfFiles;
            this.addonFiles = addonFiles;
            this.totalFiles = totalFiles;
            this.totalBytes = totalBytes;
            this.snapshot = snapshot;
            this.contentSnapshot = contentSnapshot;
        }

        public boolean isValid() {
            return wtfFiles >= 0 && addonFiles >= 0
                    && totalFiles == wtfFiles + addonFiles
                    && totalBytes >= 0
                    && snapshot.matches("[0-9a-f]{64}")
                    && contentSnapshot.matches("[0-9a-f]{64}");
        }

        public boolean sameTree(RemoteSnapshot other) {
            return other != null
                    && wtfFiles == other.wtfFiles
                    && addonFiles == other.addonFiles
                    && totalFiles == other.totalFiles
                    && totalBytes == other.totalBytes
                    && snapshot.equals(other.snapshot);
        }
    }

    public static final class TransferResult {
        public final SafeTitanTarExtractor.Result extracted;
        public final String stderr;

        TransferResult(SafeTitanTarExtractor.Result extracted, String stderr) {
            this.extracted = extracted;
            this.stderr = stderr;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int COMMAND_TIMEOUT_MS = 120_000;
    private static final int MAX_STDOUT_BYTES = 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 64 * 1024;

    private final TitanProfileHostKeyStore hostKeyStore;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Session activeSession;
    private volatile Channel activeChannel;

    public TitanProfileSshClient(TitanProfileHostKeyStore hostKeyStore) {
        this.hostKeyStore = hostKeyStore;
    }

    public void resetCancellation() {
        cancelled.set(false);
    }

    public void cancel() {
        cancelled.set(true);
        Channel channel = activeChannel;
        if (channel != null) channel.disconnect();
        Session session = activeSession;
        if (session != null) session.disconnect();
    }

    public void throwIfCancelled() throws IOException {
        checkCancelled();
    }

    public TitanProfileHostKeyStore.HostKeyInfo probeHostKey(
            TitanProfileSyncConfig config) throws Exception {
        checkCancelled();
        CapturingRepository repository = new CapturingRepository();
        Session session = null;
        try {
            JSch jsch = new JSch();
            jsch.setHostKeyRepository(repository);
            session = jsch.getSession(config.username, config.host, config.port);
            session.setConfig("StrictHostKeyChecking", "ask");
            session.setConfig("PreferredAuthentications", "password");
            session.setUserInfo(new RejectUnknownHostUserInfo());
            activeSession = session;
            session.connect(CONNECT_TIMEOUT_MS);
        }
        catch (JSchException expected) {
            checkCancelled();
        }
        finally {
            if (session != null) session.disconnect();
            activeSession = null;
        }
        if (repository.captured == null || repository.captured.length == 0) {
            throw new IOException("Unable to obtain the SSH host key");
        }
        return new TitanProfileHostKeyStore.HostKeyInfo(
                config.hostIdentity(), repository.captured);
    }

    public RemoteSnapshot readRemoteSnapshot(
            TitanProfileSyncConfig config, String password) throws Exception {
        try (Connection connection = connectTrusted(config, password)) {
            CommandResult result = executeText(connection.session, config.buildMetadataCommand());
            if (result.exitStatus != 0) {
                throw remoteCommandError("Remote preflight failed", result.stderr);
            }
            String text = result.stdout.trim();
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') text = text.substring(1);
            RemoteSnapshot snapshot = new RemoteSnapshot(new JSONObject(text));
            if (!snapshot.isValid()) throw new IOException("Remote preflight returned invalid metadata");
            return snapshot;
        }
    }

    public TransferResult transfer(
            TitanProfileSyncConfig config,
            String password,
            File stagingDir,
            SafeTitanTarExtractor.Limits limits,
            ProgressListener progressListener) throws Exception {
        try (Connection connection = connectTrusted(config, password)) {
            ChannelExec channel = (ChannelExec) connection.session.openChannel("exec");
            channel.setPty(false);
            channel.setCommand(config.buildTarCommand());
            InputStream stdout = channel.getInputStream();
            InputStream stderrStream = channel.getExtInputStream();
            LimitedBuffer stderr = new LimitedBuffer(MAX_STDERR_BYTES);
            Thread stderrThread = drainAsync(stderrStream, stderr);
            activeChannel = channel;
            channel.connect(CONNECT_TIMEOUT_MS);
            SafeTitanTarExtractor.Result extracted;
            int exitStatus;
            try {
                extracted = SafeTitanTarExtractor.extract(
                        stdout,
                        stagingDir,
                        limits,
                        cancelled::get,
                        progressListener::onProgress
                );
                waitForClose(channel, System.currentTimeMillis() + COMMAND_TIMEOUT_MS);
                exitStatus = channel.getExitStatus();
            }
            finally {
                channel.disconnect();
                activeChannel = null;
                joinQuietly(stderrThread);
            }
            String errorText = stderr.asString();
            if (exitStatus != 0) {
                throw remoteCommandError("Remote tar failed", errorText);
            }
            return new TransferResult(extracted, errorText);
        }
    }

    private Connection connectTrusted(
            TitanProfileSyncConfig config, String password) throws Exception {
        checkCancelled();
        TitanProfileHostKeyStore.HostKeyInfo pinned = hostKeyStore.load();
        if (pinned == null || !pinned.hostIdentity.equals(config.hostIdentity())) {
            throw new IOException("SSH host key is not trusted");
        }
        PinnedRepository repository = new PinnedRepository(pinned.key);
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(repository);
        Session session = jsch.getSession(config.username, config.host, config.port);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setServerAliveInterval(15_000);
        session.setServerAliveCountMax(4);
        session.setTimeout(COMMAND_TIMEOUT_MS);
        session.setUserInfo(new PasswordUserInfo(password));
        activeSession = session;
        try {
            session.connect(CONNECT_TIMEOUT_MS);
            return new Connection(session);
        }
        catch (JSchException error) {
            session.disconnect();
            activeSession = null;
            if (repository.lastStatus == HostKeyRepository.CHANGED) {
                throw new IOException("SSH host key changed; forget it and trust the new key", error);
            }
            if (repository.lastStatus == HostKeyRepository.NOT_INCLUDED) {
                throw new IOException("SSH host key is not trusted", error);
            }
            throw new IOException("SSH connection failed: " + safeMessage(error), error);
        }
    }

    private CommandResult executeText(Session session, String command) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setPty(false);
        channel.setCommand(command);
        InputStream stdoutStream = channel.getInputStream();
        InputStream stderrStream = channel.getExtInputStream();
        LimitedBuffer stdout = new LimitedBuffer(MAX_STDOUT_BYTES);
        LimitedBuffer stderr = new LimitedBuffer(MAX_STDERR_BYTES);
        Thread stderrThread = drainAsync(stderrStream, stderr);
        activeChannel = channel;
        channel.connect(CONNECT_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;
        try {
            copyLimited(stdoutStream, stdout);
            waitForClose(channel, deadline);
            return new CommandResult(channel.getExitStatus(), stdout.asString(), stderr.asString());
        }
        finally {
            channel.disconnect();
            activeChannel = null;
            joinQuietly(stderrThread);
        }
    }

    private void waitForClose(Channel channel, long deadlineMs) throws Exception {
        while (!channel.isClosed()) {
            checkCancelled();
            if (deadlineMs > 0 && System.currentTimeMillis() > deadlineMs) {
                throw new IOException("SSH command timed out");
            }
            try {
                Thread.sleep(20);
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("SSH command interrupted", error);
            }
        }
    }

    private void checkCancelled() throws IOException {
        if (cancelled.get()) throw new IOException("Sync cancelled");
    }

    private static IOException remoteCommandError(String prefix, String stderr) {
        String detail = sanitizeRemoteError(stderr);
        return new IOException(detail.isEmpty() ? prefix : prefix + ": " + detail);
    }

    static String sanitizeRemoteError(String stderr) {
        String value = stderr != null ? stderr.trim() : "";
        if (value.contains("WOW_RUNNING")) return "Windows source WoW is running";
        if (value.contains("WTF_MISSING")) return "Windows source WTF directory is missing";
        if (value.contains("ADDONS_MISSING")) return "Windows source AddOns directory is missing";
        if (value.contains("TAR_MISSING")) return "Windows tar.exe is unavailable";
        if (value.contains("REPARSE_POINT_FOUND")) {
            return "Windows source contains a reparse point";
        }
        if (value.startsWith("#< CLIXML")) {
            value = value.replaceAll("(?s)<Objs.*", "").replace("#< CLIXML", "").trim();
        }
        if (value.length() > 512) value = value.substring(0, 512) + "…";
        return value;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }

    private Thread drainAsync(InputStream input, OutputStream output) {
        Thread thread = new Thread(() -> {
            try {
                copyLimited(input, output);
            }
            catch (IOException ignored) {}
        }, "titan-profile-ssh-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void copyLimited(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkCancelled();
            output.write(buffer, 0, read);
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(1000);
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Connection implements AutoCloseable {
        final Session session;

        Connection(Session session) {
            this.session = session;
        }

        @Override
        public void close() {
            session.disconnect();
            if (activeSession == session) activeSession = null;
        }
    }

    private static final class CommandResult {
        final int exitStatus;
        final String stdout;
        final String stderr;

        CommandResult(int exitStatus, String stdout, String stderr) {
            this.exitStatus = exitStatus;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static final class LimitedBuffer extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        LimitedBuffer(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int value) {
            if (output.size() < limit) output.write(value);
        }

        @Override
        public synchronized void write(byte[] data, int offset, int length) {
            int accepted = Math.min(length, limit - output.size());
            if (accepted > 0) output.write(data, offset, accepted);
        }

        synchronized String asString() {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class CapturingRepository implements HostKeyRepository {
        volatile byte[] captured;

        @Override
        public int check(String host, byte[] key) {
            captured = key != null ? key.clone() : null;
            return NOT_INCLUDED;
        }

        @Override public void add(HostKey hostkey, UserInfo ui) {}
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
        @Override public String getKnownHostsRepositoryID() { return "capture-only"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    private static final class PinnedRepository implements HostKeyRepository {
        private final byte[] pinned;
        volatile int lastStatus = NOT_INCLUDED;

        PinnedRepository(byte[] pinned) {
            this.pinned = pinned.clone();
        }

        @Override
        public int check(String host, byte[] key) {
            lastStatus = Arrays.equals(pinned, key) ? OK : CHANGED;
            return lastStatus;
        }

        @Override public void add(HostKey hostkey, UserInfo ui) {}
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
        @Override public String getKnownHostsRepositoryID() { return "pinned"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    private static class RejectUnknownHostUserInfo implements UserInfo {
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return null; }
        @Override public boolean promptPassword(String message) { return false; }
        @Override public boolean promptPassphrase(String message) { return false; }
        @Override public boolean promptYesNo(String message) { return false; }
        @Override public void showMessage(String message) {}
    }

    private static final class PasswordUserInfo extends RejectUnknownHostUserInfo
            implements UIKeyboardInteractive {
        private final String password;

        PasswordUserInfo(String password) {
            this.password = password;
        }

        @Override public String getPassword() { return password; }
        @Override public boolean promptPassword(String message) { return true; }

        @Override
        public String[] promptKeyboardInteractive(
                String destination,
                String name,
                String instruction,
                String[] prompt,
                boolean[] echo) {
            String[] answers = new String[prompt.length];
            Arrays.fill(answers, password);
            return answers;
        }
    }
}
