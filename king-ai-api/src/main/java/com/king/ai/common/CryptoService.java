package com.king.ai.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
public class CryptoService {

    private static final String V1_PREFIX = "enc:v1:";
    private static final String V2_PREFIX = "enc:v2:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec legacyKey;
    private final SecretKeySpec currentKey;
    private final String currentPrefix;
    private final List<SecretKeySpec> decryptionKeys = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();

    public CryptoService(com.king.ai.config.KingAiProperties properties) {
        this.legacyKey = new SecretKeySpec(sha256(properties.getAppSecret()), "AES");
        String encryptionKey = properties.getEncryptionKey();
        if (encryptionKey == null || encryptionKey.isBlank()) {
            this.currentKey = legacyKey;
            this.currentPrefix = V1_PREFIX;
            this.decryptionKeys.add(legacyKey);
        } else {
            this.currentKey = new SecretKeySpec(sha256(encryptionKey), "AES");
            this.currentPrefix = V2_PREFIX;
            this.decryptionKeys.add(currentKey);
            this.decryptionKeys.add(legacyKey);
            for (String previous : properties.getPreviousEncryptionKeys()) {
                if (previous != null && !previous.isBlank()) {
                    decryptionKeys.add(new SecretKeySpec(sha256(previous), "AES"));
                }
            }
        }
    }

    public String encrypt(String plaintext) {
        if (Texts.isBlank(plaintext)) {
            return "";
        }
        return currentPrefix + cipher(plaintext, currentKey, Cipher.ENCRYPT_MODE);
    }

    public String decrypt(String value) {
        if (Texts.isBlank(value)) {
            return value;
        }
        if (value.startsWith(V1_PREFIX)) {
            return decipher(value.substring(V1_PREFIX.length()), List.of(legacyKey));
        }
        if (value.startsWith(V2_PREFIX)) {
            return decipher(value.substring(V2_PREFIX.length()), decryptionKeys);
        }
        return value;
    }

    public boolean isEncrypted(String value) {
        return value != null && (value.startsWith(V1_PREFIX) || value.startsWith(V2_PREFIX));
    }

    private String cipher(String plaintext, SecretKeySpec key, int mode) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        } catch (Exception ex) {
            throw new IllegalStateException("加密失败", ex);
        }
    }

    private String decipher(String payload, List<SecretKeySpec> keys) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("密文格式不正确");
        }
        for (SecretKeySpec key : keys) {
            try {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                byte[] iv = new byte[IV_LENGTH];
                buffer.get(iv);
                byte[] encrypted = new byte[buffer.remaining()];
                buffer.get(encrypted);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // try the next key version
            }
        }
        log.error("Failed to decrypt stored secret; check KING_AI_ENCRYPTION_KEY and previous keys");
        throw BizException.badRequest("密钥解密失败，请确认加密密钥未发生变更");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
