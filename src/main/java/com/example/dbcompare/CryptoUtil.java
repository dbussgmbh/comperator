// src/main/java/com/example/dbcompare/CryptoUtil.java
package com.example.dbcompare;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

public final class CryptoUtil {
    private static final String PREFIX = "ENC:v1:";
    private static final int GCM_TAG_BITS = 128;   // 16 Byte
    private static final int IV_LEN = 12;          // 12 Byte für GCM
    private static volatile SecretKeySpec KEY;
    private static final SecureRandom RNG = new SecureRandom();

    private CryptoUtil() {}

    /** Initialisiere mit 16/24/32-Byte Key. Empfohlen: 32 Byte (AES-256). */
    public static void init(byte[] keyBytes) {
        if (keyBytes == null || !(keyBytes.length == 16 || keyBytes.length == 24 || keyBytes.length == 32)) {
            throw new IllegalArgumentException("crypto.key muss 16/24/32 Byte lang sein (Base64-decoded).");
        }
        KEY = new SecretKeySpec(keyBytes, "AES");
    }

    public static String encryptToString(String plaintext) {
        if (plaintext == null) plaintext = "";
        ensureKey();
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes("UTF-8"));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);

            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static String decryptToString(String value) {
        if (value == null || value.isEmpty()) return "";
        if (!value.startsWith(PREFIX)) {
            // unverschlüsselter Altbestand – einfach zurückgeben
            return value;
        }
        ensureKey();
        try {
            String b64 = value.substring(PREFIX.length());
            byte[] all = Base64.getDecoder().decode(b64);
            if (all.length < IV_LEN + 16) throw new IllegalArgumentException("Ciphertext too short");

            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private static void ensureKey() {
        if (KEY == null) throw new IllegalStateException("CryptoUtil.init(...) wurde nicht aufgerufen.");
    }
}
