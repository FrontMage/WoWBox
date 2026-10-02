package com.winlator.box;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class TitanProfileCredentialStore {
    private static final String KEY_ALIAS = "wowbox_titan_profile_sync_v1";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String PASSWORD_FILE = "titan-profile-sync-password.json";

    private final Context context;

    public TitanProfileCredentialStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void savePassword(String password) throws Exception {
        if (password == null || password.isEmpty()) {
            clearPassword();
            return;
        }
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] ciphertext = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        JSONObject json = new JSONObject();
        json.put("version", 1);
        json.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        json.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        AtomicFileSupport.writeUtf8(getPasswordFile(), json.toString());
    }

    public synchronized String loadPassword() throws Exception {
        File file = getPasswordFile();
        if (!file.isFile()) return "";
        try {
            JSONObject json = new JSONObject(AtomicFileSupport.readUtf8(file));
            byte[] iv = Base64.decode(json.getString("iv"), Base64.DEFAULT);
            byte[] ciphertext = Base64.decode(json.getString("ciphertext"), Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        }
        catch (Exception error) {
            clearPassword();
            throw new IllegalStateException("Saved SSH password is unavailable; enter it again", error);
        }
    }

    public synchronized boolean hasPassword() {
        return getPasswordFile().isFile();
    }

    public synchronized void clearPassword() {
        getPasswordFile().delete();
    }

    private File getPasswordFile() {
        return new File(context.getNoBackupFilesDir(), PASSWORD_FILE);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
