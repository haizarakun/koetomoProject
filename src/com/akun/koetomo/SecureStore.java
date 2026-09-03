package com.akun.koetomo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore(API 23+) の AES 鍵で値を暗号化して SharedPreferences に保存する。
 * 鍵はハードウェア/TEE 側に保持され、アプリからも取り出せない(root 化端末でも鍵は読めない)。
 * API 22 以下は Keystore の AES に対応しないため、そのまま保存する(minSdk 21 互換のため)。
 */
final class SecureStore {
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "koetomo_master_v1";
    private static final String PREFIX = "enc1:";
    private static final String PREFS = "koetomo_secure";
    private final Context ctx;

    SecureStore(Context c) { this.ctx = c.getApplicationContext(); }

    static boolean supported() { return Build.VERSION.SDK_INT >= 23; }

    private synchronized SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        // android.security.keystore.KeyGenParameterSpec は API 23 の android.jar に含まれる
        android.security.keystore.KeyGenParameterSpec spec = new android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build();
        KeyGenerator kg = KeyGenerator.getInstance("AES", KS);
        kg.init(spec);
        return kg.generateKey();
    }

    /** 平文 → "enc1:" + base64(iv(12) || ciphertext+tag) */
    String encrypt(String plain) {
        if (plain == null) return null;
        if (!supported()) return plain;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return plain; // 暗号化不能な環境では平文フォールバック(機能を止めない)
        }
    }

    /** "enc1:..." なら復号、それ以外(旧平文)はそのまま返す */
    String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            byte[] iv = new byte[12];
            byte[] ct = new byte[all.length - 12];
            System.arraycopy(all, 0, iv, 0, 12);
            System.arraycopy(all, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            return null; // 鍵が失われた(端末初期化等)場合は読めない → 再ログインを促す
        }
    }

    static boolean isEncrypted(String v) { return v != null && v.startsWith(PREFIX); }

    /** 汎用: JS 側の保存アカウントなどに使う */
    void put(String k, String plain) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, 0);
        if (plain == null) { sp.edit().remove(k).apply(); return; }
        sp.edit().putString(k, encrypt(plain)).apply();
    }

    String get(String k) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, 0);
        String v = sp.getString(k, null);
        return v == null ? null : decrypt(v);
    }
}
