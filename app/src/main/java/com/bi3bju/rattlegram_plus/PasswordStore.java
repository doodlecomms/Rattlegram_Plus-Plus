package com.bi3bju.rattlegram_plus;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Optional "remember password" storage.
 * <p>
 * The password is encrypted with AES-256-GCM using a key that lives in the Android Keystore,
 * so the key never leaves the phone's secure hardware and a copied preferences file is useless.
 * The ciphertext is kept in its own preferences file, which is excluded from backups
 * (see res/xml/backup_rules.xml and data_extraction_rules.xml).
 */
final class PasswordStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "rattlegram_password_key";
    private static final String PREFS = "password_store";
    private static final String KEY_CIPHERTEXT = "ciphertext";
    private static final String KEY_IV = "iv";
    private static final int TAG_BITS = 128;

    private PasswordStore() {}

    /** Whether a password is currently remembered. */
    static boolean isRemembered(Context context) {
        return prefs(context).contains(KEY_CIPHERTEXT);
    }

    /** Encrypts and stores the password. Returns false if the phone's keystore is unavailable. */
    static boolean save(Context context, String password) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            prefs(context).edit()
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The remembered password, or null if none is stored or it can no longer be decrypted. */
    static String load(Context context) {
        SharedPreferences prefs = prefs(context);
        String ciphertext = prefs.getString(KEY_CIPHERTEXT, null);
        String iv = prefs.getString(KEY_IV, null);
        if (ciphertext == null || iv == null)
            return null;
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (key == null)
                throw new IllegalStateException("key missing");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Key lost (e.g. restored onto another phone, or the phone's keystore was reset): forget it.
            clear(context);
            return null;
        }
    }

    /** Forgets the remembered password. */
    static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    /** Forgets the password and destroys its Keystore key, so nothing saved can ever be decrypted again. */
    static void destroy(Context context) {
        prefs(context).edit().clear().commit();
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null)
            return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
