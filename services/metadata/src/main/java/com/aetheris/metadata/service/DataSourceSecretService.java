package com.aetheris.metadata.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class DataSourceSecretService {

    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public DataSourceSecretService(@Value("${aetheris.datasource.master-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException("AETHERIS_DATASOURCE_MASTER_KEY must be configured");
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encodedKey.trim());
            if (keyBytes.length != 32) {
                throw new IllegalStateException("AETHERIS_DATASOURCE_MASTER_KEY must be base64-encoded 32 bytes");
            }
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("AETHERIS_DATASOURCE_MASTER_KEY must be valid base64", error);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || isEncrypted(plaintext)) {
            return plaintext;
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("数据源密码加密失败", error);
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isEmpty() || !isEncrypted(storedValue)) {
            return storedValue;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(storedValue.substring(PREFIX.length()));
            if (payload.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[payload.length - NONCE_BYTES];
            System.arraycopy(payload, 0, nonce, 0, nonce.length);
            System.arraycopy(payload, nonce.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("数据源密码解密失败", error);
        }
    }

    public boolean isEncrypted(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(value.substring(PREFIX.length())).length > NONCE_BYTES;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
