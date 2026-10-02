package com.winlator.box;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

public final class TitanProfileHostKeyStore {
    private static final String FILE_NAME = "titan-profile-sync-host-key.json";

    public static final class HostKeyInfo {
        public final String hostIdentity;
        public final byte[] key;
        public final String fingerprint;

        HostKeyInfo(String hostIdentity, byte[] key) {
            this.hostIdentity = hostIdentity;
            this.key = key.clone();
            this.fingerprint = fingerprint(key);
        }
    }

    private final File file;

    public TitanProfileHostKeyStore(Context context) {
        file = new File(context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME);
    }

    public synchronized HostKeyInfo load() {
        if (!file.isFile()) return null;
        try {
            JSONObject json = new JSONObject(AtomicFileSupport.readUtf8(file));
            String identity = json.getString("hostIdentity");
            byte[] key = Base64.getDecoder().decode(json.getString("key"));
            if (identity.isEmpty() || key.length == 0) throw new IllegalArgumentException();
            return new HostKeyInfo(identity, key);
        }
        catch (Exception error) {
            file.delete();
            return null;
        }
    }

    public synchronized void save(HostKeyInfo info) throws Exception {
        JSONObject json = new JSONObject();
        json.put("version", 1);
        json.put("hostIdentity", info.hostIdentity);
        json.put("key", Base64.getEncoder().encodeToString(info.key));
        AtomicFileSupport.writeUtf8(file, json.toString());
    }

    public synchronized boolean matches(TitanProfileSyncConfig config, byte[] key) {
        HostKeyInfo stored = load();
        return stored != null
                && stored.hostIdentity.equals(config.hostIdentity())
                && Arrays.equals(stored.key, key);
    }

    public synchronized boolean hasKeyFor(TitanProfileSyncConfig config) {
        HostKeyInfo stored = load();
        return stored != null && stored.hostIdentity.equals(config.hostIdentity());
    }

    public synchronized void clear() {
        file.delete();
    }

    static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        }
        catch (Exception error) {
            return "SHA256:unavailable";
        }
    }
}
