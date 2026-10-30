package com.king.ai.auth;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP (SHA-1, 6 digits, 30 second step) with the RFC 4648 Base32
 * alphabet used by every authenticator app.
 */
@Service
public class TotpService {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;

    private final SecureRandom random = new SecureRandom();

    public String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public String otpauthUrl(String issuer, String account, String secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer) + "&algorithm=SHA1&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }

    public boolean verify(String secret, String code, int window) {
        if (secret == null || secret.isBlank() || code == null) {
            return false;
        }
        String value = code.replace(" ", "").trim();
        if (!value.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        long counter = System.currentTimeMillis() / 1000 / STEP_SECONDS;
        for (long offset = -window; offset <= window; offset++) {
            String expected = generateCode(secret, counter + offset);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    value.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    public static String generateCode(String base32Secret, long counter) {
        try {
            byte[] key = base32Decode(base32Secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成 TOTP 验证码", ex);
        }
    }

    static String base32Encode(byte[] data) {
        StringBuilder builder = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                builder.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            builder.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return builder.toString();
    }

    static byte[] base32Decode(String value) {
        String normalized = value.replace("=", "").replace(" ", "").toUpperCase();
        ByteArrayOutputStream out = new ByteArrayOutputStream(normalized.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int index = ALPHABET.indexOf(normalized.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException("非法的 Base32 密钥");
            }
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
