package com.vironite.retrievex.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.Charset;
import java.security.Key;
import java.security.KeyStore;
import java.security.ProviderException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small encrypted key-value store for secrets (the X session).
 *
 * <ul>
 *   <li>AES-256-GCM with a key generated inside the Android Keystore. The key material is
 *       non-exportable, so copying the app's data files off the device does not reveal the secrets.</li>
 *   <li>The key is hardware-backed where the device supports it (StrongBox is tried first, then the TEE).</li>
 *   <li>Every value is bound to its name through GCM associated data, so an entry cannot be swapped
 *       with another one, and any tampering makes decryption fail.</li>
 *   <li>A fresh random IV is used for every write.</li>
 *   <li>If an entry is permanently unreadable (key gone, data corrupted or tampered with) it is deleted and treated
 *       as absent. A <em>temporary</em> Keystore failure (common right after an app update or device unlock) never
 *       deletes anything: the entry is kept and the next read simply tries again.</li>
 *   <li>{@link #clear()} also deletes the Keystore key, so any leftover ciphertext becomes permanently unreadable.</li>
 * </ul>
 * No third-party crypto library is needed (the Jetpack security-crypto library is deprecated).
 */
public final class SecureStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "retrievex_secure_store_v1";
    private static final String FILE = "retrievex_secure";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final SharedPreferences sp;
    private boolean lastReadTemporary;
    private boolean lastReadLost;

    public SecureStore(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Encrypts and stores a value. Returns false if encryption or persisting failed (nothing is stored then). */
    public synchronized boolean put(String name, String value) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(name.getBytes(UTF8));
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(value.getBytes(UTF8));
            if (iv == null || iv.length == 0 || iv.length > 255) return false;
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return sp.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).commit();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the decrypted value, or null when absent or unreadable. Permanently unreadable entries are removed;
     * when the Keystore is only temporarily unavailable the entry is kept and {@link #lastReadWasTemporary()} is true.
     */
    public synchronized String get(String name) {
        lastReadTemporary = false;
        lastReadLost = false;
        String enc = sp.getString(name, null);
        if (enc == null) return null;
        // One quick retry covers a Keystore that is still starting up after an update.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return decrypt(name, enc);
            } catch (PermanentFailure e) {
                sp.edit().remove(name).commit();
                lastReadLost = true;
                return null;
            } catch (Exception e) {
                lastReadTemporary = true;
                if (attempt == 0) {
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return null;
    }

    /** True when the last {@link #get(String)} found an entry it could not read only because the Keystore failed for now. */
    public synchronized boolean lastReadWasTemporary() {
        return lastReadTemporary;
    }

    /** True when the last {@link #get(String)} found an entry that was permanently unreadable and removed it. */
    public synchronized boolean lastReadWasLost() {
        return lastReadLost;
    }

    /** True when an encrypted entry with this name is stored (it may or may not be readable at the moment). */
    public synchronized boolean has(String name) {
        return sp.getString(name, null) != null;
    }

    private static final class PermanentFailure extends Exception {
        PermanentFailure(String m, Throwable cause) {
            super(m, cause);
        }
    }

    private String decrypt(String name, String enc) throws Exception {
        byte[] in;
        int ivLen;
        try {
            in = Base64.decode(enc, Base64.NO_WRAP);
            ivLen = in.length > 0 ? (in[0] & 0xff) : 0;
        } catch (IllegalArgumentException e) {
            throw new PermanentFailure("corrupt entry", e);
        }
        if (ivLen == 0 || in.length <= 1 + ivLen) throw new PermanentFailure("bad entry", null);
        SecretKey key = key(false);
        if (key == null) throw new PermanentFailure("key missing", null);   // the Keystore answered: the key is really gone
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, in, 1, ivLen));
        cipher.updateAAD(name.getBytes(UTF8));
        try {
            byte[] plain = cipher.doFinal(in, 1 + ivLen, in.length - 1 - ivLen);
            return new String(plain, UTF8);
        } catch (BadPaddingException e) {
            // includes AEADBadTagException: wrong key or tampered / corrupted data
            throw new PermanentFailure("authentication failed", e);
        }
    }

    public synchronized void remove(String name) {
        sp.edit().remove(name).commit();
    }

    /** Deletes every stored value and the Keystore key itself. */
    public synchronized void clear() {
        sp.edit().clear().commit();
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // Nothing left to protect: the ciphertext was already deleted above.
        }
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        Key k = ks.getKey(KEY_ALIAS, null);
        if (k instanceof SecretKey) return (SecretKey) k;
        if (!create) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(true);
            } catch (ProviderException e) {
                // StrongBox is not available on this device; fall back to the normal secure hardware / TEE.
            }
        }
        return generate(false);
    }

    private SecretKey generate(boolean strongBox) throws Exception {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true);
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            b.setIsStrongBoxBacked(true);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(b.build());
        return kg.generateKey();
    }
}
